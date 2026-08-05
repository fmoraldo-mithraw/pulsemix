package com.pulsemix.app.mix

import com.pulsemix.app.analysis.AudioAnalyzer
import com.pulsemix.app.data.Track
import kotlin.math.abs

/**
 * Sélection des morceaux « épiques » : musique de film et de bande-annonce,
 * orchestral cinématique — chœurs, cors, intensité.
 *
 * L'épique ne se reconnaît PAS à des seuils absolus. Deux tentatives l'ont
 * montré : des seuils larges laissaient passer n'importe quelle ballade à
 * grande dynamique, des seuils serrés rejetaient l'épique réel — le
 * trailer moderne est fort, compressé, percussif, à mille lieues du
 * portrait « nappes douces qui montent » qu'un barème absolu suppose.
 *
 * La bibliothèque elle-même sait mieux :
 *
 *  - **Par ancrage** : les morceaux dont le nom annonce l'épique sans
 *    ambiguïté (Two Steps From Hell, Audiomachine, « trailer »,
 *    « cinematic »…) servent de référence. Leur profil acoustique MESURÉ
 *    définit ce que « épique » sonne dans CETTE bibliothèque, et les
 *    morceaux qui leur ressemblent rejoignent la sélection — même
 *    mécanisme que les genres adjacents de [MixEngine.filterByGenre].
 *  - **Par rangs** , quand il n'y a pas assez d'ancres : classement en
 *    percentiles de la bibliothèque (comme la sélection « douce ») sur les
 *    signaux de l'épique — son tenu, bas-médium (chœurs et cuivres),
 *    respiration, montée, sommet. Relatif, donc insensible aux biais de
 *    mesure ; un plancher évite d'inventer de l'épique dans une
 *    bibliothèque qui n'en a pas.
 *
 * Sans Android : vérifiable par des tests.
 */
object EpicScore {

    /** Éditeurs et mots qui annoncent la couleur sans ambiguïté. */
    private val NAME_HINTS = Regex(
        "\\bepic\\b|épique|trailer|cinematic|cinématique|" +
            "two steps from hell|audiomachine|really slow motion|" +
            "thomas bergersen|immediate music|epic score|position music|" +
            "future world music|brand ?x music|ninja tracks|" +
            "\\borchestral\\b|epic music",
        RegexOption.IGNORE_CASE
    )

    /** Le nom annonce explicitement de la musique épique. */
    fun namedEpic(t: Track): Boolean =
        NAME_HINTS.containsMatchIn("${t.title} ${t.artist} ${t.genre}")

    /** Nombre d'ancres nommées à partir duquel on leur fait confiance. */
    private const val MIN_ANCHORS = 3

    /**
     * Distance maximale au profil des ancres (moyenne par mesure, en
     * fractions de l'étendue de la bibliothèque) pour rejoindre la
     * sélection. Un jumeau acoustique est à ~0 ; un morceau de club face à
     * un profil orchestral dépasse largement.
     */
    private const val ANCHOR_RADIUS = 0.20f

    /**
     * Plancher du score en percentiles (voie sans ancres). Une bibliothèque
     * uniforme met tout le monde à 0,5 : exiger nettement plus, c'est
     * refuser de désigner un « moins pire ».
     */
    private const val RANK_FLOOR = 0.72f

    /** Les mesures qui décrivent un profil sonore. */
    private val FEATURES: List<(Track) -> Float> = listOf(
        { it.energyMean },
        { it.energyPeak },
        { it.centroid },
        { it.onsetRate },
        { it.sustainRatio },
        { it.lowMidRatio },
        { it.dynamicSpread },
        { it.energySlope }
    )

    /**
     * Les morceaux épiques de la bibliothèque, les plus sûrs d'abord.
     * Liste vide si rien n'y ressemble : le mix n'est alors pas proposé.
     */
    fun select(all: List<Track>, max: Int = 24): List<Track> {
        // Le marquage « pas épique » l'emporte sur tout — nom compris. Un
        // morceau écarté ne sert pas non plus de référence : sinon un faux
        // « epic » continuerait de déformer le profil des ancres.
        val pool = all.filter { it.analyzed && !it.excluded && !it.notEpic }
        if (pool.size < 4) return emptyList()
        // Les ancres n'ont un profil exploitable qu'analysées avec le jeu
        // de mesures courant : les anciennes analyses n'ont ni son tenu ni
        // bas-médium, et pollueraient la référence avec des zéros.
        val anchors = pool.filter {
            namedEpic(it) && it.featuresVersion >= AudioAnalyzer.FEATURES_VERSION
        }
        return when {
            anchors.size >= MIN_ANCHORS -> byAnchors(pool, anchors, max)
            // Des ancres nommées mais pas encore réanalysées : elles seules
            // sont sûres. La ressemblance attendra leurs mesures.
            pool.any { namedEpic(it) } ->
                pool.filter { namedEpic(it) }.take(max)
            else -> byRanks(pool, max)
        }
    }

    // ------------------------------------------------------- voie par ancres

    private fun byAnchors(pool: List<Track>, anchors: List<Track>, max: Int): List<Track> {
        val med = FloatArray(FEATURES.size)
        val span = FloatArray(FEATURES.size)
        for (i in FEATURES.indices) {
            val f = FEATURES[i]
            med[i] = median(anchors.map(f))
            val values = pool.map(f)
            span[i] = ((values.maxOrNull() ?: 0f) - (values.minOrNull() ?: 0f))
                .coerceAtLeast(1e-4f)
        }

        fun dist(t: Track): Float {
            var s = 0f
            for (i in FEATURES.indices) s += abs(FEATURES[i](t) - med[i]) / span[i]
            return s / FEATURES.size
        }

        // Les ancres entrent de droit ; les autres, par ressemblance.
        val named = pool.filter { namedEpic(it) }.sortedBy { dist(it) }
        val kin = pool.asSequence()
            .filterNot { namedEpic(it) }
            .map { it to dist(it) }
            .filter { it.second <= ANCHOR_RADIUS }
            .sortedBy { it.second }
            .map { it.first }
            .toList()
        return (named + kin).take(max)
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    // -------------------------------------------------------- voie par rangs

    private fun byRanks(pool: List<Track>, max: Int): List<Track> {
        val sustain = pool.map { it.sustainRatio }.sorted()
        val lowMid = pool.map { it.lowMidRatio }.sorted()
        val spread = pool.map { it.dynamicSpread }.sorted()
        val slope = pool.map { it.energySlope }.sorted()
        val peak = pool.map { it.energyPeak }.sorted()
        val onsets = pool.map { it.onsetRate }.sorted()

        fun score(t: Track): Float =
            0.24f * midRank(sustain, t.sustainRatio) +
                0.22f * midRank(lowMid, t.lowMidRatio) +
                0.18f * midRank(spread, t.dynamicSpread) +
                0.14f * midRank(slope, t.energySlope) +
                0.12f * midRank(peak, t.energyPeak) +
                0.10f * (1f - midRank(onsets, t.onsetRate))

        return pool.asSequence()
            .map { it to score(it) }
            .filter { it.second >= RANK_FLOOR }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(max)
            .toList()
    }

    /**
     * Rang médian (0..1) de [v] dans [sorted] : moyenne du rang « strictement
     * en dessous » et du rang « en dessous ou égal ». Les ex æquo tombent à
     * 0,5 au lieu de 1 — dans une bibliothèque uniforme, personne n'est
     * au-dessus des autres, et le plancher fait son travail.
     */
    private fun midRank(sorted: List<Float>, v: Float): Float {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < v) lo = mid + 1 else hi = mid
        }
        val below = lo
        hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] <= v) lo = mid + 1 else hi = mid
        }
        return (below + lo) / (2f * sorted.size)
    }

    /**
     * Intensité ressentie, pour ranger un set du plus retenu au plus
     * écrasant : le sommet doit tomber à la fin. À sommet égal, la masse
     * tenue — chœurs, cuivres, nappes — écrase davantage qu'un coup sec.
     */
    fun intensity(t: Track): Float = t.energyPeak * (0.6f + 0.4f * t.sustainRatio)
}
