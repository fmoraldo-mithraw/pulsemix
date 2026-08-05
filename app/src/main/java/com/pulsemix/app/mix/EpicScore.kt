package com.pulsemix.app.mix

import com.pulsemix.app.analysis.AudioAnalyzer
import com.pulsemix.app.data.Track
import kotlin.math.sqrt

/**
 * Sélection des morceaux « épiques » : musique de film et de bande-annonce,
 * orchestral cinématique — chœurs, cors, intensité.
 *
 * Règle unique : **pas de référence, pas de sélection.** Trois tentatives
 * l'ont établie. Des seuils absolus ne savent pas dire « épique » (trop
 * larges ou trop étroits selon la calibration). Un classement relatif de
 * la bibliothèque ne le sait pas non plus : il désigne TOUJOURS une queue
 * haute — les morceaux les plus doux-tenus-dynamiques de n'importe quelle
 * discothèque en sortent « épiques », ballades et acoustique comprises.
 * C'était la source des sélections pleines de morceaux sans rapport.
 *
 * La seule référence fiable, ce sont les morceaux que l'utilisateur ou
 * leurs tags désignent : noms sans ambiguïté (Two Steps From Hell,
 * Audiomachine, « trailer », « cinematic »…) ou genre « epic » posé à la
 * main. Leur profil acoustique MESURÉ définit ce que l'épique sonne dans
 * CETTE bibliothèque, et seuls les morceaux qui leur ressemblent de près
 * les rejoignent :
 *
 *  - distance quadratique — un morceau très différent sur deux mesures ne
 *    compense pas par les six autres ;
 *  - normalisation par l'étendue centrale (p10-p90) de la bibliothèque —
 *    les valeurs extrêmes d'un seul morceau ne dilatent plus l'échelle,
 *    ce qui rétrécissait toutes les distances et faisait entrer
 *    n'importe quoi ;
 *  - rayon d'admission calibré sur la dispersion des ancres ELLES-MÊMES :
 *    un style épique homogène admet peu, un style éclectique admet
 *    davantage — mais jamais au-delà d'une borne stricte.
 *
 * Sans ancres, le mix Épique n'est simplement pas proposé. Marquer deux
 * ou trois morceaux (« epic » en genre) suffit à le faire naître.
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

    /** Nombre d'ancres mesurées à partir duquel on calcule un profil. */
    private const val MIN_ANCHORS = 3

    /**
     * Bornes du rayon d'admission. Le rayon vient de la dispersion des
     * ancres (voir [select]) ; ces bornes l'empêchent de dégénérer — trop
     * petit avec des ancres quasi identiques (plus aucun semblable
     * n'entrerait), trop grand avec des ancres disparates (retour à la
     * permissivité qu'on corrige).
     */
    private const val RADIUS_MIN = 0.06f
    private const val RADIUS_MAX = 0.16f

    /** Marge sur la dispersion des ancres : un peu plus loin que la plus
     *  excentrée, pas dix fois plus loin. */
    private const val RADIUS_SLACK = 1.5f

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
     * Liste vide sans référence : plutôt rien que n'importe quoi.
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
        if (anchors.size < MIN_ANCHORS) {
            // Trop peu de références mesurées : seuls les nommés sont sûrs.
            // La ressemblance attendra leurs mesures — ou d'autres ancres.
            return pool.filter { namedEpic(it) }.take(max)
        }

        // Profil de référence : médiane des ancres, mesure par mesure.
        // Étendue de normalisation : p10-p90 de la bibliothèque, pour que
        // les valeurs extrêmes d'un morceau isolé ne dilatent pas l'échelle.
        val med = FloatArray(FEATURES.size)
        val span = FloatArray(FEATURES.size)
        for (i in FEATURES.indices) {
            med[i] = median(anchors.map(FEATURES[i]))
            val sorted = pool.map(FEATURES[i]).sorted()
            val p10 = sorted[(sorted.size - 1) / 10]
            val p90 = sorted[(sorted.size - 1) * 9 / 10]
            span[i] = (p90 - p10).coerceAtLeast(1e-4f)
        }

        // Distance quadratique : les gros écarts pèsent plus que ce que la
        // moyenne leur accordait — être à l'opposé sur deux mesures ne se
        // rachète pas en collant sur les six autres.
        fun dist(t: Track): Float {
            var s = 0f
            for (i in FEATURES.indices) {
                val d = (FEATURES[i](t) - med[i]) / span[i]
                s += d * d
            }
            return sqrt(s / FEATURES.size)
        }

        // Rayon d'admission : à peine plus loin que l'ancre la plus
        // excentrée. Les ancres disent aussi par leur dispersion à quel
        // point « épique » est homogène dans cette bibliothèque.
        val radius = (anchors.maxOf { dist(it) } * RADIUS_SLACK)
            .coerceIn(RADIUS_MIN, RADIUS_MAX)

        val named = pool.filter { namedEpic(it) }.sortedBy { dist(it) }
        val kin = pool.asSequence()
            .filterNot { namedEpic(it) }
            .map { it to dist(it) }
            .filter { it.second <= radius }
            .sortedBy { it.second }
            .map { it.first }
            .toList()
        return (named + kin).take(max)
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    /**
     * Intensité ressentie, pour ranger un set du plus retenu au plus
     * écrasant : le sommet doit tomber à la fin. À sommet égal, la masse
     * tenue — chœurs, cuivres, nappes — écrase davantage qu'un coup sec.
     */
    fun intensity(t: Track): Float = t.energyPeak * (0.6f + 0.4f * t.sustainRatio)
}
