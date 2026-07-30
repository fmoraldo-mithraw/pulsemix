package com.pulsemix.app.mix

import com.pulsemix.app.data.PlayHistory
import com.pulsemix.app.data.Track
import com.pulsemix.app.data.TransitionFeedback
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * Construit des propositions de mix à partir de la bibliothèque analysée.
 *
 * Un mix = une suite de phases (chauffe, montée, peak, pause, relance...)
 * dont les morceaux s'enchaînent avec des BPM cohérents et, si possible,
 * des tonalités compatibles (roue Camelot).
 */
object MixEngine {

    enum class Band { CALME, GROOVE, DANCE, INTENSE }

    data class Phase(val name: String, val tracks: List<Track>)

    data class MixPlan(
        val id: String,
        val name: String,
        val description: String,
        val phases: List<Phase>
    ) {
        val trackCount: Int get() = phases.sumOf { it.tracks.size }
    }

    fun bandOf(bpm: Float): Band = when {
        bpm < 95f -> Band.CALME
        bpm < 115f -> Band.GROOVE
        bpm < 135f -> Band.DANCE
        else -> Band.INTENSE
    }

    // -------------------------------------------------------------- Camelot

    /** 1.0 = même clé, 0.8 = voisine (±1 ou relative), 0 sinon. */
    fun camelotScore(a: String, b: String): Float {
        if (a.length < 2 || b.length < 2 || a == "--" || b == "--") return 0f
        val na = a.dropLast(1).toIntOrNull() ?: return 0f
        val nb = b.dropLast(1).toIntOrNull() ?: return 0f
        val la = a.last()
        val lb = b.last()
        if (na == nb && la == lb) return 1f
        if (na == nb && la != lb) return 0.8f
        val diff = min((na - nb + 12) % 12, (nb - na + 12) % 12)
        if (diff == 1 && la == lb) return 0.8f
        return 0f
    }

    // ------------------------------------------------------- enchaînement

    /**
     * Ordonne un ensemble de morceaux en chaîne : chaque morceau suivant est
     * choisi pour minimiser l'écart de BPM et maximiser la compatibilité
     * harmonique avec le précédent — avec un hasard contrôlé : on tire parmi
     * les 3 meilleurs candidats (biaisé vers le meilleur), pour que deux
     * lancements du même mix ne donnent pas le même enchaînement.
     */
    fun chainOrder(
        tracks: List<Track>,
        ascending: Boolean = true,
        rnd: Random = Random.Default,
        startWith: Track? = null,
        maxLength: Int = Int.MAX_VALUE
    ): List<Track> {
        if (tracks.size <= 1) return tracks
        val pool = ArrayList(tracks)
        val result = ArrayList<Track>(min(tracks.size, maxLength))
        val start = if (startWith != null && pool.remove(startWith)) startWith
        else {
            val startCands = pool.sortedBy { if (ascending) it.bpm else -it.bpm }.take(3)
            startCands[rnd.nextInt(startCands.size)]
        }
        result.add(start)
        pool.remove(start)
        // Grande bibliothèque : un seul passage O(n) par étape qui retient les
        // 3 meilleurs candidats — pas de tri complet ni d'allocation par étape
        // (l'ancien tri par étape rendait la génération O(n² log n), plusieurs
        // secondes à 780+ morceaux).
        while (pool.isNotEmpty() && result.size < maxLength) {
            val prev = result.last()
            var i1 = -1
            var i2 = -1
            var i3 = -1
            var c1 = Float.MAX_VALUE
            var c2 = Float.MAX_VALUE
            var c3 = Float.MAX_VALUE
            for (i in pool.indices) {
                val c = cost(prev, pool[i], ascending)
                when {
                    c < c1 -> {
                        c3 = c2; i3 = i2
                        c2 = c1; i2 = i1
                        c1 = c; i1 = i
                    }
                    c < c2 -> {
                        c3 = c2; i3 = i2
                        c2 = c; i2 = i
                    }
                    c < c3 -> {
                        c3 = c; i3 = i
                    }
                }
            }
            // hasard contrôlé : ~60 % le meilleur, ~25 % le 2e, ~15 % le 3e
            val pick = when {
                i2 < 0 || rnd.nextFloat() < 0.6f -> i1
                i3 < 0 || rnd.nextFloat() < 0.625f -> i2
                else -> i3
            }
            result.add(pool[pick])
            pool.removeAt(pick)
        }
        return result
    }

    /**
     * Prend n morceaux vers le haut d'un classement, en piochant au hasard
     * dans une fenêtre légèrement plus large (n + 50 %) : la sélection change
     * d'un lancement à l'autre tout en restant fidèle au critère.
     */
    private fun sampleTop(sorted: List<Track>, n: Int, rnd: Random): List<Track> {
        val window = sorted.take(min(sorted.size, n + n / 2 + 1)).toMutableList()
        val out = ArrayList<Track>(min(n, window.size))
        repeat(min(n, window.size)) {
            var idx = rnd.nextInt(window.size)
            // anti-répétition : un morceau joué récemment obtient un re-tirage
            if (!window[idx].favorite && PlayHistory.penalty(window[idx].uri) > 0.5f) {
                idx = rnd.nextInt(window.size)
            }
            // favoris légèrement avantagés
            if (rnd.nextFloat() < 0.25f) {
                val fav = window.indexOfFirst { it.favorite }
                if (fav >= 0) idx = fav
            }
            out.add(window.removeAt(idx))
        }
        return out
    }

    private fun cost(prev: Track, cand: Track, ascending: Boolean): Float {
        val ref = if (prev.bpm > 0) prev.bpm else 120f
        var delta = abs(cand.bpm - prev.bpm) / ref * 100f
        // Tolérance double/moitié de tempo
        val deltaHalf = abs(cand.bpm / 2f - prev.bpm) / ref * 100f
        val deltaDouble = abs(cand.bpm * 2f - prev.bpm) / ref * 100f
        delta = min(delta, min(deltaHalf + 4f, deltaDouble + 4f))
        // Direction souhaitée
        val dir = if (ascending) prev.bpm - cand.bpm else cand.bpm - prev.bpm
        val dirPenalty = if (dir > 0) dir * 0.15f else 0f
        val harmonic = camelotScore(prev.camelot, cand.camelot) * 4f
        // Anti-répétition (48 h) et petit bonus favori
        val recency = PlayHistory.penalty(cand.uri) * 3f
        val favBonus = if (cand.favorite) 0.5f else 0f
        // Jamais deux morceaux du même artiste d'affilée (si évitable)
        val sameArtist = if (cand.artist.isNotBlank() &&
            cand.artist.equals(prev.artist, ignoreCase = true)
        ) 6f else 0f
        // Paires marquées « transition ratée » par l'utilisateur
        val badPair = if (TransitionFeedback.isBad(prev.uri, cand.uri)) 10f else 0f
        return delta + dirPenalty - harmonic + recency - favBonus + sameArtist + badPair
    }

    // -------------------------------------------------------- propositions

    // --------------------------------------------------------------- genres

    fun normalizeGenre(raw: String?): String =
        raw?.trim()?.lowercase()?.substringBefore(';')?.substringBefore('/')
            ?.trim() ?: ""

    /** Genres présents (normalisés) avec leur nombre de morceaux analysés. */
    fun genresOf(all: List<Track>): List<Pair<String, Int>> =
        all.filter { it.analyzed && it.genre.isNotBlank() && it.genre != "-" }
            .groupBy { it.genre }
            .map { it.key to it.value.size }
            .sortedByDescending { it.second }

    /**
     * Morceaux d'un genre ET des genres « adjacents » : ceux dont le profil
     * acoustique moyen (BPM, énergie, brillance) est proche du genre choisi.
     */
    fun filterByGenre(all: List<Track>, genre: String): List<Track> {
        val analyzed = all.filter { it.analyzed && it.bpm > 0f }
        val byGenre = analyzed
            .filter { it.genre.isNotBlank() && it.genre != "-" }
            .groupBy { it.genre }
        val seedTracks = byGenre[genre] ?: return analyzed

        fun profile(tracks: List<Track>): Triple<Float, Float, Float> = Triple(
            tracks.map { it.bpm }.average().toFloat(),
            tracks.map { it.energyMean }.average().toFloat(),
            tracks.map { it.centroid }.average().toFloat()
        )

        val bpmSpan = (analyzed.maxOf { it.bpm } - analyzed.minOf { it.bpm })
            .coerceAtLeast(1f)
        val eSpan = (analyzed.maxOf { it.energyMean } - analyzed.minOf { it.energyMean })
            .coerceAtLeast(1e-4f)
        val cSpan = (analyzed.maxOf { it.centroid } - analyzed.minOf { it.centroid })
            .coerceAtLeast(1f)

        val seed = profile(seedTracks)
        val adjacent = byGenre.filterKeys { it != genre }
            .filter { (_, tracks) ->
                val p = profile(tracks)
                val d = abs(p.first - seed.first) / bpmSpan +
                    abs(p.second - seed.second) / eSpan +
                    abs(p.third - seed.third) / cSpan
                d < 0.45f
            }
            .keys

        return analyzed.filter { it.genre == genre || it.genre in adjacent }
    }

    fun proposeMixes(
        all: List<Track>,
        dj: Boolean = false,
        targetMinutes: Int? = null,
        genre: String? = null
    ): List<MixPlan> {
        val base = if (genre != null) filterByGenre(all, genre) else all
        val tracks = base.filter { it.analyzed && it.bpm > 0f && !it.excluded }
        if (tracks.size < 4) return emptyList()

        val plans = ArrayList<MixPlan>()
        val byBand = tracks.groupBy { bandOf(it.bpm) }
        val calme = byBand[Band.CALME].orEmpty()
        val groove = byBand[Band.GROOVE].orEmpty()
        val dance = byBand[Band.DANCE].orEmpty()
        val intense = byBand[Band.INTENSE].orEmpty()
        val energetic = dance + intense

        // 1. Soirée complète : chauffe → montée → peak → pause → relance → final
        if (tracks.size >= 12 && calme.size + groove.size >= 4 && energetic.size >= 4) {
            val used = HashSet<String>()
            fun take(from: List<Track>, n: Int, comparator: Comparator<Track>): List<Track> {
                val picked = sampleTop(
                    from.filter { it.uri !in used }.sortedWith(comparator),
                    n, Random.Default
                )
                picked.forEach { used.add(it.uri) }
                return picked
            }

            val warmupPool = take(
                (calme + groove), min(4, (calme.size + groove.size) / 2),
                compareBy { it.energyMean }
            )
            val buildPool = take(
                (groove + dance), min(5, groove.size + dance.size),
                compareBy { it.bpm }
            )
            val peakPool = take(
                energetic, min(6, energetic.size),
                compareByDescending { it.energyPeak }
            )
            val pausePool = take(
                (calme + groove), min(2, calme.size + groove.size),
                compareBy { it.energyMean }
            )
            val reprisePool = take(
                energetic, min(4, energetic.size),
                compareByDescending { it.energyMean }
            )

            val phases = listOfNotNull(
                phaseOrNull("Chauffe", chainOrder(warmupPool, ascending = true)),
                phaseOrNull("Montée", chainOrder(buildPool, ascending = true)),
                phaseOrNull("Peak", chainOrder(peakPool, ascending = true)),
                phaseOrNull("Pause", chainOrder(pausePool, ascending = false)),
                phaseOrNull("Relance", chainOrder(reprisePool, ascending = true))
            )
            if (phases.size >= 4) {
                plans.add(
                    MixPlan(
                        "soiree", "Soirée complète",
                        "L'arc classique d'une soirée : ça chauffe, ça monte, ça tape, on souffle, et ça repart.",
                        phases
                    )
                )
            }
        }

        // 2. Montée progressive : BPM strictement croissants
        run {
            val sorted = tracks.sortedBy { it.bpm }
            val spread = sorted.last().bpm - sorted.first().bpm
            if (tracks.size >= 6 && spread >= 25f) {
                val ordered = chainOrder(sorted, ascending = true, maxLength = 36)
                val phases = splitIntoPhases(
                    ordered,
                    listOf("Décollage", "Croisière", "Accélération", "Apogée")
                )
                plans.add(
                    MixPlan(
                        "montee", "Montée progressive",
                        "Du plus posé au plus rapide, sans jamais redescendre.",
                        phases
                    )
                )
            }
        }

        // 3. Chill : uniquement les morceaux doux, arc léger
        run {
            val soft = sampleTop(
                (calme + groove).sortedBy { it.energyMean }, 14, Random.Default
            )
            if (soft.size >= 4) {
                val ordered = chainOrder(soft, ascending = true)
                val phases = splitIntoPhases(ordered, listOf("Pose", "Flottement", "Descente"))
                plans.add(
                    MixPlan(
                        "chill", "Chill",
                        "Que du doux : BPM bas, énergie contenue, idéal en fond ou en fin de soirée.",
                        phases
                    )
                )
            }
        }

        // 4. Peak time : que des morceaux qui tapent
        if (energetic.size >= 6) {
            val ordered = chainOrder(
                sampleTop(energetic.sortedByDescending { it.energyPeak }, 16, Random.Default),
                ascending = true
            )
            val phases = splitIntoPhases(ordered, listOf("Impact", "Pression", "Explosion"))
            plans.add(
                MixPlan(
                    "peak", "Peak time",
                    "Zéro temps mort : uniquement le haut du panier en BPM et en énergie.",
                    phases
                )
            )
        }

        // 5. Vagues : alternance montée / pause
        if (energetic.size >= 8 && calme.size + groove.size >= 3) {
            // léger bruit sur le tri (clé précalculée : tri stable)
            val softKeys = HashMap<String, Float>()
            for (t in calme + groove) {
                softKeys[t.uri] = t.energyMean * (0.85f + 0.3f * Random.nextFloat())
            }
            val soft = (calme + groove)
                .sortedBy { softKeys[it.uri] }
                .toMutableList()
            val hard = chainOrder(energetic, ascending = true).toMutableList()
            val phases = ArrayList<Phase>()
            var wave = 1
            while (hard.size >= 3 && phases.size < 8) {
                val up = ArrayList<Track>()
                repeat(min(4, hard.size)) { up.add(hard.removeAt(0)) }
                phases.add(Phase("Vague $wave", up))
                if (soft.isNotEmpty() && hard.isNotEmpty()) {
                    val rest = ArrayList<Track>()
                    repeat(min(2, soft.size)) { rest.add(soft.removeAt(0)) }
                    phases.add(Phase("Accalmie $wave", rest))
                }
                wave++
            }
            if (phases.size >= 3) {
                plans.add(
                    MixPlan(
                        "vagues", "Vagues",
                        "Des montées en puissance entrecoupées d'accalmies, en cycles.",
                        phases
                    )
                )
            }
        }

        // 6. Flow : sélection enchaînée proprement (toujours proposé)
        run {
            val ordered = chainOrder(tracks, ascending = true, maxLength = 30)
            val phases = splitIntoPhases(
                ordered,
                listOf("Partie 1", "Partie 2", "Partie 3", "Partie 4")
            )
            plans.add(
                MixPlan(
                    "flow", "Flow continu",
                    "Une sélection enchaînée au plus fluide (BPM et tonalités).",
                    phases
                )
            )
        }

        // 7. Auto-DJ : toute la bibliothèque, jusqu'au bout de la nuit
        if (tracks.size >= 8) {
            val ordered = chainOrder(tracks, ascending = true)
            plans.add(
                MixPlan(
                    "auto", "Auto-DJ (toute la bibliothèque)",
                    "Enchaîne tous les morceaux par compatibilité, sans s'arrêter.",
                    splitIntoPhases(
                        ordered,
                        listOf("Set 1", "Set 2", "Set 3", "Set 4", "Set 5", "Set 6")
                    )
                )
            )
        }

        // Durée cible : réduire chaque plan à la durée demandée
        if (targetMinutes != null) {
            val targetMs = targetMinutes * 60_000L
            return plans.map { trimToDuration(it, targetMs, dj) }
        }
        return plans
    }

    private fun trackLenMs(t: Track, dj: Boolean): Long =
        if (dj) t.segmentMs.coerceAtLeast(20_000L)
        else t.durationMs.coerceAtLeast(60_000L)

    /** Retire des morceaux (par la fin des phases les plus longues) jusqu'à
     *  tenir dans la durée cible. */
    private fun trimToDuration(plan: MixPlan, targetMs: Long, dj: Boolean): MixPlan {
        val phases = plan.phases.map { it.tracks.toMutableList() }.toMutableList()
        var total = phases.sumOf { ph -> ph.sumOf { trackLenMs(it, dj) } }
        while (total > targetMs) {
            val idx = phases.indices
                .filter { phases[it].size > 1 }
                .maxByOrNull { phases[it].size } ?: break
            val removed = phases[idx].removeAt(phases[idx].size - 1)
            total -= trackLenMs(removed, dj)
        }
        return MixPlan(
            plan.id, plan.name, plan.description,
            plan.phases.mapIndexedNotNull { i, ph ->
                if (phases[i].isEmpty()) null else Phase(ph.name, phases[i].toList())
            }
        )
    }

    // ------------------------------------------------------- mix par similarité

    /**
     * Construit un mix « comme ce morceau » : les morceaux les plus proches en
     * tempo (double/moitié compris), tonalité, énergie et brillance, enchaînés
     * à partir du morceau de départ.
     */
    fun similarPlan(all: List<Track>, seed: Track, rnd: Random = Random.Default): MixPlan? {
        val candidates = all.filter {
            it.analyzed && it.bpm > 0f && !it.excluded && it.uri != seed.uri
        }
        if (candidates.size < 4 || seed.bpm <= 0f) return null

        fun norm(sel: (Track) -> Float): Pair<Float, Float> {
            val values = candidates.map(sel)
            val lo = values.minOrNull() ?: 0f
            val hi = values.maxOrNull() ?: 1f
            return lo to (hi - lo).coerceAtLeast(1e-4f)
        }

        val (eLo, eSpan) = norm { it.energyMean }
        val (cLo, cSpan) = norm { it.centroid }
        val (oLo, oSpan) = norm { it.onsetRate }

        fun distance(t: Track): Float {
            val bpmDist = minOf(
                abs(t.bpm - seed.bpm),
                abs(t.bpm * 2 - seed.bpm),
                abs(t.bpm / 2 - seed.bpm)
            ) / seed.bpm
            val harmonic = 1f - camelotScore(seed.camelot, t.camelot)
            val energy = abs((t.energyMean - eLo) / eSpan - (seed.energyMean - eLo) / eSpan)
            val bright = abs((t.centroid - cLo) / cSpan - (seed.centroid - cLo) / cSpan)
            val onset = abs((t.onsetRate - oLo) / oSpan - (seed.onsetRate - oLo) / oSpan)
            return 2.5f * bpmDist + 0.8f * harmonic + 1.2f * energy +
                0.6f * bright + 0.4f * onset + 0.5f * PlayHistory.penalty(t.uri)
        }

        // Distances figées avant le tri (la pénalité d'historique varie avec
        // l'horloge : sélecteur instable = crash TimSort)
        val dists = HashMap<String, Float>(candidates.size)
        for (c in candidates) dists[c.uri] = distance(c)
        val pool = sampleTop(candidates.sortedBy { dists[it.uri] }, 18, rnd)
        val ordered = chainOrder(listOf(seed) + pool, ascending = true, rnd, startWith = seed)
        return MixPlan(
            "similar",
            "Comme « ${seed.title} »",
            "Morceaux du même style et de la même énergie, enchaînés depuis celui-ci.",
            splitIntoPhases(ordered, listOf("Même veine 1", "Même veine 2", "Même veine 3", "Même veine 4"))
        )
    }

    private fun phaseOrNull(name: String, tracks: List<Track>): Phase? =
        if (tracks.isEmpty()) null else Phase(name, tracks)

    private fun splitIntoPhases(ordered: List<Track>, names: List<String>): List<Phase> {
        if (ordered.isEmpty()) return emptyList()
        val n = min(names.size, (ordered.size + 3) / 4).coerceAtLeast(1)
        val per = (ordered.size + n - 1) / n
        val phases = ArrayList<Phase>()
        var i = 0
        var p = 0
        while (i < ordered.size && p < n) {
            val end = min(ordered.size, i + per)
            phases.add(Phase(names[p], ordered.subList(i, end).toList()))
            i = end
            p++
        }
        return phases
    }

    // ------------------------------------------------------- musique douce

    /**
     * Sélectionne les morceaux vraiment « doux » : un score de douceur global
     * (énergie, brillance, densité d'attaques ET BPM, en rangs percentiles de
     * la bibliothèque) sert de filtre strict — seuls les morceaux sous le
     * seuil entrent dans la sélection.
     *
     * @param softness seuil 0..1 : ~0,25 = le quart le plus doux de la
     * bibliothèque (défaut de l'interface, très doux).
     */
    fun softSelection(
        all: List<Track>,
        softness: Float,
        rnd: Random = Random.Default
    ): List<Track> {
        val analyzed = all.filter { it.analyzed && it.bpm > 0f && !it.excluded }
        if (analyzed.isEmpty()) return emptyList()

        // Rang percentile via tableau trié + recherche binaire (appelé à
        // chaque cran du curseur : doit rester léger)
        fun sortedOf(sel: (Track) -> Float): FloatArray =
            analyzed.map(sel).sorted().toFloatArray()

        fun pct(sorted: FloatArray, v: Float): Float {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (sorted[mid] <= v) lo = mid + 1 else hi = mid
            }
            return lo.toFloat() / sorted.size
        }

        val energies = sortedOf { it.energyMean }
        val centroids = sortedOf { it.centroid }
        val onsets = sortedOf { it.onsetRate }
        val bpms = sortedOf { it.bpm }

        fun softScore(t: Track): Float =
            0.45f * pct(energies, t.energyMean) +
                0.20f * pct(centroids, t.centroid) +
                0.15f * pct(onsets, t.onsetRate) +
                0.20f * pct(bpms, t.bpm)

        val eligible = analyzed.filter { softScore(it) <= softness.coerceIn(0.05f, 1f) }
        // Ordre doux -> moins doux, avec un léger bruit pour varier.
        // Clé précalculée UNE FOIS par morceau : un sélecteur aléatoire évalué
        // à chaque comparaison rend le tri incohérent (crash TimSort,
        // « Comparison method violates its general contract »).
        val keys = HashMap<String, Float>(eligible.size)
        for (t in eligible) {
            keys[t.uri] = softScore(t) + (rnd.nextFloat() - 0.5f) * 0.06f
        }
        return eligible.sortedBy { keys[it.uri] }
    }
}
