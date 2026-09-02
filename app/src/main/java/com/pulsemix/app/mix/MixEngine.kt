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
     * Poids de tirage d'un morceau dans les sélections : c'est LUI qui fait
     * tourner la bibliothèque. Un morceau jamais joué pèse trois fois plus
     * qu'un morceau ordinaire ; un morceau sur-joué ou entendu dans les
     * dernières 48 h ne pèse presque plus rien. L'ancien mécanisme (deux
     * re-tirages uniformes) laissait toujours revenir les mêmes tops
     * pendant que des centaines de morceaux restaient jamais joués.
     */
    internal fun drawWeight(t: Track): Float {
        val base = if (PlayHistory.count(t.uri) == 0) 3f else 1f
        val malus = 0.8f * PlayHistory.overplayPenalty(t.uri) +
            0.6f * PlayHistory.penalty(t.uri)
        return (base - malus).coerceAtLeast(0.1f)
    }

    /**
     * Prend n morceaux vers le haut d'un classement, en piochant au hasard
     * dans une fenêtre deux fois plus large que le besoin : la sélection
     * change d'un lancement à l'autre tout en restant fidèle au critère.
     * Tirage PONDÉRÉ par l'historique ([drawWeight]) : les jamais-joués
     * sortent en priorité, les sur-joués s'effacent sans être interdits.
     */
    private fun sampleTop(sorted: List<Track>, n: Int, rnd: Random): List<Track> {
        val window = sorted.take(min(sorted.size, n * 2 + 1)).toMutableList()
        // Poids figés une fois (les pénalités lisent l'horloge : les figer
        // garde le tirage cohérent pendant toute la sélection)
        val weights = FloatArray(window.size) { drawWeight(window[it]) }
        var total = 0f
        for (w in weights) total += w
        var count = window.size
        val out = ArrayList<Track>(min(n, count))
        repeat(min(n, count)) {
            var idx = 0
            var pick = rnd.nextFloat() * total
            for (i in 0 until count) {
                pick -= weights[i]
                if (pick <= 0f) {
                    idx = i
                    break
                }
                idx = i
            }
            // favoris légèrement avantagés
            if (rnd.nextFloat() < 0.25f) {
                var fav = -1
                for (i in 0 until count) {
                    if (window[i].favorite) {
                        fav = i
                        break
                    }
                }
                if (fav >= 0) idx = fav
            }
            out.add(window.removeAt(idx))
            total -= weights[idx]
            // Compacte le tableau de poids en miroir de la liste
            for (i in idx until count - 1) weights[i] = weights[i + 1]
            count--
        }
        return out
    }

    internal fun cost(prev: Track, cand: Track, ascending: Boolean): Float {
        val ref = if (prev.bpm > 0) prev.bpm else 120f
        var delta = abs(cand.bpm - prev.bpm) / ref * 100f
        // Tolérance double/moitié de tempo
        val deltaHalf = abs(cand.bpm / 2f - prev.bpm) / ref * 100f
        val deltaDouble = abs(cand.bpm * 2f - prev.bpm) / ref * 100f
        delta = min(delta, min(deltaHalf + 4f, deltaDouble + 4f))
        // MARCHE à la fenêtre de calage du moteur DJ (±4 % par deck, calage
        // partagé : 8 % d'écart entre les deux morceaux) : en deçà, les
        // deux se battent ensemble ; au-delà, c'est une coupe sèche. Un
        // coût linéaire ne voyait pas cette falaise — 5 % coûtait à peine
        // plus que 3 % — et les plans enchaînaient des morceaux non
        // mixables sans le savoir.
        if (delta > LOCK_WINDOW_PCT) delta += 6f
        // Direction souhaitée
        val dir = if (ascending) prev.bpm - cand.bpm else cand.bpm - prev.bpm
        val dirPenalty = if (dir > 0) dir * 0.15f else 0f
        val harmonic = camelotScore(prev.camelot, cand.camelot) * 4f
        // Anti-répétition (48 h) et petit bonus favori
        val recency = PlayHistory.penalty(cand.uri) * 4f
        // Compteur de lectures : un morceau beaucoup trop joué s'efface
        // nettement au profit des autres (sans devenir interdit). Poids
        // fort à dessein : à 3f, les mêmes tops revenaient dans chaque
        // mix pendant que des centaines de morceaux restaient vierges.
        val overplay = PlayHistory.overplayPenalty(cand.uri) * 7f
        // Découverte : un morceau jamais joué est avantagé
        val freshBonus = if (PlayHistory.count(cand.uri) == 0) 1f else 0f
        val favBonus = if (cand.favorite) 0.5f else 0f
        // Jamais deux morceaux du même artiste d'affilée (si évitable)
        val sameArtist = if (cand.artist.isNotBlank() &&
            cand.artist.equals(prev.artist, ignoreCase = true)
        ) 6f else 0f
        // Paires marquées « transition ratée » par l'utilisateur
        val badPair = if (TransitionFeedback.isBad(prev.uri, cand.uri)) 10f else 0f
        // Continuité d'ÉNERGIE : toujours comptée, même genre ou pas. Deux
        // morceaux du même genre peuvent être aux antipodes d'intensité, et
        // c'est le saut d'énergie qui choque l'oreille dans un mix — BPM
        // proche et tonalité voisine n'y changent rien (l'énergie était
        // avalée par le garde-genre ci-dessous, d'où des enchaînements
        // doux → surexcité dans « écouter comme »).
        val energyJump = (abs(prev.energyMean - cand.energyMean) / 0.15f)
            .coerceAtMost(3f) * 1.5f
        // Continuité de style : même genre = neutre ; sinon pénaliser l'écart
        // de signature sonore (brillance, densité d'attaques) pour ne pas
        // choquer l'oreille d'un morceau à l'autre
        val sameGenre = prev.genre.isNotBlank() && prev.genre != "-" &&
            prev.genre == cand.genre
        val styleDist = if (sameGenre) 0f else (
            abs(prev.centroid - cand.centroid) / 2_000f +
                abs(prev.onsetRate - cand.onsetRate) / 3f
            ).coerceAtMost(2f) * 1.2f
        return delta + dirPenalty - harmonic + recency + overplay - favBonus -
            freshBonus + sameArtist + badPair + styleDist + energyJump
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

    /** Durée minimale d'un mix ou d'un set DJ : en dessous d'une heure,
     *  le set s'arrête au moment où la soirée démarre. */
    /** Écart de tempo (%) au-delà duquel le moteur DJ ne bat plus deux
     *  morceaux ensemble (2 × ±4 %, calage partagé) : marche du coût. */
    const val LOCK_WINDOW_PCT = 8f
    const val MIN_MIX_MINUTES = 60
    const val MIN_MIX_MS = MIN_MIX_MINUTES * 60_000L

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

        // 7. Épique : chœurs, cuivres et crescendos, rangés en arc
        run {
            val epic = EpicScore.select(tracks)
            if (epic.size >= 5) {
                // Rangés du plus retenu au plus écrasant : dans ce
                // répertoire, l'arc compte plus que la continuité de tempo,
                // qui n'a de toute façon guère de sens sur de l'orchestral.
                val ordered = epic.sortedBy { EpicScore.intensity(it) }
                plans.add(
                    MixPlan(
                        "epique", "Épique",
                        "Chœurs, cuivres et crescendos : ça part retenu et " +
                            "ça finit en apothéose.",
                        splitIntoPhases(
                            ordered,
                            listOf("Prélude", "Montée", "Apothéose", "Épilogue")
                        )
                    )
                )
            }
        }

        // 8. Auto-DJ : toute la bibliothèque, jusqu'au bout de la nuit
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

        // Jamais deux fois le même morceau dans un plan (y compris le même
        // titre présent dans deux dossiers différents)
        val deduped = plans.map { dedupePlan(it) }

        // Durée cible : réduire les plans trop longs, mais aussi ALLONGER
        // les trop courts — les générateurs ont des tailles de pool fixes
        // (4-6 morceaux par phase), donc sans extension un « 2 h » en DJ
        // plafonnait à ~20 min quelle que soit la durée choisie.
        // Dans tous les cas, un mix ne descend jamais sous l'heure (tant
        // que la bibliothèque a de quoi) : une cible plus courte est
        // remontée au plancher, et « Auto » (sans cible) rallonge aussi —
        // sans jamais raccourcir un plan naturellement plus long.
        // La rallonge pioche dans le vivier du plan, pas dans toute la
        // bibliothèque : allonger le mix épique avec les morceaux les
        // plus compatibles au tempo l'aurait rempli de variété.
        val epicPool by lazy { EpicScore.select(tracks, max = 200) }
        fun poolFor(p: MixPlan) = if (p.id == "epique") epicPool else tracks
        if (targetMinutes != null) {
            val targetMs = targetMinutes.coerceAtLeast(MIN_MIX_MINUTES) * 60_000L
            return deduped.map { p ->
                extendToDuration(
                    trimToDuration(p, targetMs, dj), targetMs, dj, poolFor(p),
                    floorMs = MIN_MIX_MS
                )
            }
        }
        return deduped.map { p ->
            extendToDuration(p, MIN_MIX_MS, dj, poolFor(p), floorMs = MIN_MIX_MS)
        }
    }

    /**
     * Titre réduit à sa substance : sans numéro de piste, sans extension,
     * sans mentions de habillage, sans ponctuation ni accents. « 03 - Le
     * Bien Qui Fait Mal (Official Video).mp3 » et « le bien qui fait
     * mal.flac » donnent la même chose.
     */
    internal fun normTitle(raw: String): String {
        var s = raw.lowercase().trim()
        s = s.replace(
            Regex("\\.(mp3|m4a|aac|flac|ogg|oga|opus|wav|wma|mp4)$"), ""
        )
        s = s.replace(Regex("^\\s*\\d{1,3}\\s*[-._)]\\s*"), "")
        s = s.replace(
            Regex(
                "[\\(\\[][^\\)\\]]*(official|clip|video|lyrics?|audio|hd|hq|4k" +
                    "|paroles|visuali[sz]er|explicit|remaster(ed)?|feat\\.?|ft\\.?" +
                    ")[^\\)\\]]*[\\)\\]]"
            ), " "
        )
        s = s.replace(Regex("\\s*\\[[a-z0-9_-]{6,}\\]\\s*$"), " ")
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        s = s.replace(Regex("[^a-z0-9]+"), "")
        return s
    }

    /**
     * Clés d'identité d'un morceau, pour n'en garder qu'un exemplaire dans
     * un plan. Le fichier ne suffit pas : la même chanson traîne souvent en
     * plusieurs copies aux tags différents (numéro de piste en tête,
     * artiste « Downloads », suffixe YouTube). On la reconnaît alors par
     * son titre réduit, seul ou avec l'artiste, et par sa durée — deux
     * copies du même enregistrement durent la même chose.
     */
    internal fun dupKeys(t: Track): List<String> {
        val title = normTitle(t.title)
        if (title.isEmpty() || title == "?") return listOf(t.uri)
        val keys = ArrayList<String>(3)
        keys.add(t.uri)
        keys.add("t:$title")
        if (t.durationMs > 0) {
            // Durée au pas de 4 s : absorbe les silences de fin et les
            // encodages différents sans confondre deux morceaux distincts
            keys.add("d:$title:${t.durationMs / 4_000}")
        }
        return keys
    }

    /** Supprime les doublons d'un plan (même fichier ou même chanson). */
    private fun dedupePlan(plan: MixPlan): MixPlan {
        val seen = HashSet<String>()
        val phases = plan.phases.mapNotNull { ph ->
            val kept = ph.tracks.filter { t ->
                val keys = dupKeys(t)
                if (keys.any { it in seen }) false
                else {
                    seen.addAll(keys)
                    true
                }
            }
            if (kept.isEmpty()) null else Phase(ph.name, kept)
        }
        return MixPlan(plan.id, plan.name, plan.description, phases)
    }

    private fun trackLenMs(t: Track, dj: Boolean): Long =
        // Mode DJ : plancher aligné sur DjMixer.MIN_SEGMENT_MS, sinon les
        // estimations de durée d'un plan sont trop courtes
        if (dj) t.segmentMs.coerceAtLeast(60_000L)
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

    /**
     * Allonge un plan trop court pour la durée cible : enchaîne des morceaux
     * compatibles supplémentaires au bout de la dernière phase, tant que ça
     * rapproche de la cible. L'arc des phases existantes est préservé.
     *
     * [floorMs] est un PLANCHER dur : tant qu'il n'est pas atteint, on
     * continue d'ajouter même si ça éloigne de la cible (l'arrêt « au plus
     * proche » pouvait laisser un plan juste sous l'heure quand le dernier
     * candidat dépassait). Le vivier peut évidemment s'épuiser avant.
     */
    private fun extendToDuration(
        plan: MixPlan,
        targetMs: Long,
        dj: Boolean,
        all: List<Track>,
        floorMs: Long = 0L
    ): MixPlan {
        val phases = plan.phases.map { it.tracks.toMutableList() }.toMutableList()
        val last = phases.lastOrNull()?.takeIf { it.isNotEmpty() } ?: return plan
        var total = phases.sumOf { ph -> ph.sumOf { trackLenMs(it, dj) } }
        if (total >= targetMs && total >= floorMs) return plan

        // Mêmes clés d'identité que dedupePlan : la rallonge ne doit pas
        // réintroduire une chanson déjà présente sous un autre fichier.
        val used = HashSet<String>()
        for (t in phases.flatten()) used.addAll(dupKeys(t))
        val pool = all.filter {
            it.analyzed && it.bpm > 0f && !it.excluded &&
                dupKeys(it).none { k -> k in used }
        }.toMutableList()

        while (pool.isNotEmpty()) {
            val prev = last.last()
            var bestIdx = 0
            var bestCost = Float.MAX_VALUE
            for (i in pool.indices) {
                val c = cost(prev, pool[i], ascending = true)
                if (c < bestCost) {
                    bestCost = c
                    bestIdx = i
                }
            }
            val next = pool.removeAt(bestIdx)
            val newTotal = total + trackLenMs(next, dj)
            // On s'arrête dès qu'ajouter éloigne de la cible plus que ça
            // ne l'approche (au plus proche, léger dépassement compris) —
            // mais jamais sous le plancher.
            if (total >= floorMs &&
                abs(newTotal - targetMs) >= abs(total - targetMs)
            ) break
            last.add(next)
            used.addAll(dupKeys(next))
            total = newTotal
        }
        return MixPlan(
            plan.id, plan.name, plan.description,
            plan.phases.mapIndexed { i, ph -> Phase(ph.name, phases[i].toList()) }
        )
    }

    // ------------------------------------------------------- mix par similarité

    /**
     * Construit un mix « comme ce morceau » : les morceaux les plus proches en
     * tempo (double/moitié compris), tonalité, énergie et brillance, enchaînés
     * à partir du morceau de départ.
     */
    fun similarPlan(
        all: List<Track>,
        seed: Track,
        rnd: Random = Random.Default,
        dj: Boolean = false
    ): MixPlan? {
        val candidates = all.filter {
            it.analyzed && it.bpm > 0f && !it.excluded && it.uri != seed.uri
        }
        // Un seul autre morceau analysé suffit : un mix court vaut mieux
        // qu'un bouton qui ne fait rien.
        if (candidates.isEmpty() || seed.bpm <= 0f) return null

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
            // Historique appuyé (1,0/1,5 contre 0,5/0,5 avant) : le mix
            // « comme ce morceau » repiochait toujours les mêmes voisins
            val fresh = if (PlayHistory.count(t.uri) == 0) 0.4f else 0f
            return 2.5f * bpmDist + 0.8f * harmonic + 1.2f * energy +
                0.6f * bright + 0.4f * onset + 1.0f * PlayHistory.penalty(t.uri) +
                1.5f * PlayHistory.overplayPenalty(t.uri) - fresh
        }

        // Distances figées avant le tri (la pénalité d'historique varie avec
        // l'horloge : sélecteur instable = crash TimSort)
        val dists = HashMap<String, Float>(candidates.size)
        for (c in candidates) dists[c.uri] = distance(c)
        val pool = sampleTop(candidates.sortedBy { dists[it.uri] }, 18, rnd)
        val ordered = chainOrder(listOf(seed) + pool, ascending = true, rnd, startWith = seed)
        val plan = dedupePlan(
            MixPlan(
                "similar",
                "Comme « ${seed.title} »",
                "Morceaux du même style et de la même énergie, enchaînés depuis celui-ci.",
                splitIntoPhases(ordered, listOf("Même veine 1", "Même veine 2", "Même veine 3", "Même veine 4"))
            )
        )
        // Même plancher d'une heure que les autres mix. La rallonge pioche
        // dans les plus proches du morceau-graine (pas toute la
        // bibliothèque) pour rester « dans la même veine » : les 18
        // premiers seuls faisaient ~20 min en mode DJ.
        val reserve = candidates.sortedBy { dists[it.uri] }.take(80)
        return extendToDuration(
            plan, MIN_MIX_MS, dj, reserve, floorMs = MIN_MIX_MS
        )
    }

    // --------------------------------------------------------- « et ensuite ? »

    /**
     * Les 5 meilleurs candidats pour enchaîner après [current] : mêmes
     * ingrédients que [similarPlan] (tempo double/moitié admis, roue
     * Camelot, continuité d'énergie), mais en fonction PURE — les pénalités
     * d'historique ([penalize], 0..1 et plus = à éviter) et les paires
     * marquées ratées ([isBadPair]) sont INJECTÉES au lieu de lire les
     * singletons, pour être testable en JVM. Les morceaux exclus des mix ne
     * sont jamais proposés ; notEpic ne concerne que le mix Épique et est
     * ignoré ici.
     */
    fun suggestNext(
        current: Track,
        all: List<Track>,
        penalize: (String) -> Float = { 0f },
        isBadPair: (String, String) -> Boolean = { _, _ -> false }
    ): List<Track> {
        val candidates = all.filter {
            it.analyzed && it.bpm > 0f && !it.excluded &&
                it.uri != current.uri && !isBadPair(current.uri, it.uri)
        }
        if (candidates.isEmpty()) return emptyList()
        val ref = if (current.bpm > 0f) current.bpm else 120f

        fun score(t: Track): Float {
            // Écart de tempo relatif, double/moitié admis (comme similarPlan)
            val bpmDist = minOf(
                abs(t.bpm - ref),
                abs(t.bpm * 2f - ref),
                abs(t.bpm / 2f - ref)
            ) / ref
            val harmonic = camelotScore(current.camelot, t.camelot)
            // Continuité d'énergie : même échelle que cost() (0,15 ≈ un
            // grand écart d'ambiance), bornée pour qu'un cas extrême
            // n'écrase pas tout le reste du score
            val energy = (abs(t.energyMean - current.energyMean) / 0.15f)
                .coerceAtMost(3f)
            return 1.5f * harmonic - 2.5f * bpmDist - 1.2f * energy -
                1.5f * penalize(t.uri)
        }

        // Scores figés avant le tri : un sélecteur instable (pénalité
        // recalculée à chaque comparaison) est un crash TimSort assuré
        val scores = HashMap<String, Float>(candidates.size)
        for (c in candidates) scores[c.uri] = score(c)
        return candidates.sortedByDescending { scores[it.uri] }.take(5)
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
