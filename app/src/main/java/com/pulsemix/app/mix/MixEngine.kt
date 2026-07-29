package com.pulsemix.app.mix

import com.pulsemix.app.data.Track
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
        rnd: Random = Random.Default
    ): List<Track> {
        if (tracks.size <= 1) return tracks
        val pool = tracks.toMutableList()
        val result = ArrayList<Track>(tracks.size)
        val startCands = pool.sortedBy { if (ascending) it.bpm else -it.bpm }.take(3)
        val start = startCands[rnd.nextInt(startCands.size)]
        result.add(start)
        pool.remove(start)
        while (pool.isNotEmpty()) {
            val prev = result.last()
            val cands = pool.sortedBy { cost(prev, it, ascending) }.take(3)
            val r = rnd.nextFloat()
            val pick = when {
                cands.size == 1 || r < 0.6f -> 0
                cands.size == 2 || r < 0.85f -> 1
                else -> 2
            }
            val next = cands[pick]
            result.add(next)
            pool.remove(next)
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
            out.add(window.removeAt(rnd.nextInt(window.size)))
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
        return delta + dirPenalty - harmonic
    }

    // -------------------------------------------------------- propositions

    fun proposeMixes(all: List<Track>): List<MixPlan> {
        val tracks = all.filter { it.analyzed && it.bpm > 0f }
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
                val ordered = chainOrder(sorted, ascending = true)
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
            // léger bruit sur le tri : les accalmies varient d'un lancement à l'autre
            val soft = (calme + groove)
                .sortedBy { it.energyMean * (0.85f + 0.3f * Random.nextFloat()) }
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

        // 6. Flow : toute la bibliothèque, enchaînée proprement (toujours proposé)
        run {
            val ordered = chainOrder(tracks, ascending = true)
            val phases = splitIntoPhases(
                ordered.take(30),
                listOf("Partie 1", "Partie 2", "Partie 3", "Partie 4")
            )
            plans.add(
                MixPlan(
                    "flow", "Flow continu",
                    "Toute la bibliothèque enchaînée au plus fluide (BPM et tonalités).",
                    phases
                )
            )
        }

        return plans
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
        val analyzed = all.filter { it.analyzed && it.bpm > 0f }
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
        // Ordre doux -> moins doux, avec un léger bruit pour varier
        return eligible.sortedBy { softScore(it) + (rnd.nextFloat() - 0.5f) * 0.06f }
    }
}
