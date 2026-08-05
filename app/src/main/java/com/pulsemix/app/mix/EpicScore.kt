package com.pulsemix.app.mix

import com.pulsemix.app.data.Track

/**
 * Reconnaît les morceaux « épiques » : musique de film et de bande-annonce,
 * orchestral cinématique, tout ce qui se construit vers un sommet.
 *
 * « Épique » n'est pas un genre mais une FORME, et un timbre. Ce qui la
 * caractérise :
 *
 *  - **des chœurs et des cuivres** — des voix massées et des cors tiennent
 *    des notes longues, et concentrent leur énergie dans le bas-médium.
 *    D'où deux mesures : la part de son tenu, et la part d'énergie entre
 *    180 et 1200 Hz.
 *  - **une montée** — ça finit bien plus fort que ça n'a commencé.
 *  - **une respiration** — du murmure au tutti, là où une production
 *    compressée reste plate.
 *  - **de l'intensité** — le sommet tape vraiment.
 *  - **peu d'attaques** — la percussion est rare mais énorme, à l'opposé
 *    d'une batterie qui remplit chaque temps.
 *
 * Aucun de ces signaux ne suffit seul : une ballade rock respire aussi, un
 * morceau d'ambient est aussi tenu. C'est leur réunion qui signe l'épique.
 *
 * Les noms parlent souvent d'eux-mêmes (Two Steps From Hell, « trailer »,
 * « cinematic ») : quand c'est le cas, on le croit.
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

    /**
     * Rampe douce : 0 en deçà de [lo], 1 au-delà de [hi], proportionnel
     * entre les deux. Évite les seuils francs, qui feraient basculer un
     * morceau d'un extrême à l'autre pour un cheveu.
     */
    private fun ramp(v: Float, lo: Float, hi: Float): Float =
        when {
            hi <= lo -> 0f
            v <= lo -> 0f
            v >= hi -> 1f
            else -> (v - lo) / (hi - lo)
        }

    /**
     * Score de 0 à 1. Les morceaux analysés avec un jeu de mesures ancien
     * ([Track.featuresVersion] à 0) n'ont ni montée ni son tenu : seul leur
     * nom peut alors les trahir.
     */
    fun of(t: Track): Float {
        val named = if (namedEpic(t)) 1f else 0f
        if (!t.analyzed) return named * 0.5f

        // Timbre : voix massées et cuivres, tenus
        val choirBrass = ramp(t.lowMidRatio, 0.34f, 0.62f)
        val sustained = ramp(t.sustainRatio, 0.60f, 0.88f)
        // Forme : ça monte, et ça respire
        val build = ramp(t.energySlope, 1.25f, 2.40f)
        val breathe = ramp(t.dynamicSpread, 2.6f, 6.0f)
        // Le sommet tape
        val punch = ramp(t.energyPeak, 0.14f, 0.34f)
        // Peu d'attaques : la percussion est rare mais énorme
        val sparse = 1f - ramp(t.onsetRate, 1.5f, 3.2f)

        val acoustic =
            0.30f * choirBrass +
                0.24f * sustained +
                0.18f * build +
                0.14f * breathe +
                0.08f * punch +
                0.06f * sparse

        // Verrou de timbre : sans chœurs ni cuivres TENUS, pas d'épique.
        // Une somme pondérée seule laissait un morceau compenser par sa
        // forme (montée, respiration) ce qui lui manque en matière — c'est
        // par là que passaient les faux positifs. Le score est plafonné par
        // le plus faible des deux signaux de timbre.
        val gate = 0.5f + 0.5f * minOf(choirBrass, sustained)

        // Un nom qui annonce la couleur aide, sans jamais suffire : un
        // morceau nommé « epic » mais qui sonne comme une pop plate reste
        // sous le plancher.
        return (acoustic * gate + named * 0.30f).coerceIn(0f, 1f)
    }

    /**
     * Plancher en deçà duquel on refuse de parler d'épique. Il évite de
     * proposer un mix épique à une bibliothèque qui n'en contient pas :
     * sans lui, un classement relatif finirait toujours par désigner un
     * « moins pire », aussi plat soit-il.
     *
     * Relevé (0,42 → 0,55) avec le verrou de timbre : seuls les morceaux
     * qui ont À LA FOIS la matière (chœurs/cuivres tenus) et l'intensité
     * passent. Un chant choral calme, une ballade qui monte, un nom
     * évocateur sur un son plat : tous restent dehors.
     */
    const val FLOOR = 0.55f

    /**
     * Les morceaux épiques d'une bibliothèque, du plus au moins marqué.
     * Sélection RELATIVE — le quart le mieux placé — mais bornée par
     * [FLOOR], parce qu'être le plus épique d'une discothèque de variété
     * ne rend épique en rien.
     */
    fun select(all: List<Track>, max: Int = 24): List<Track> {
        val scored = all
            .filter { it.analyzed && !it.excluded }
            .map { it to of(it) }
            .filter { it.second >= FLOOR }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return emptyList()
        val quarter = (scored.size + 3) / 4
        // Au moins huit quand il y en a assez — un quart de dix morceaux
        // ferait un mix de trois minutes — et jamais plus de [max].
        val n = maxOf(quarter, minOf(scored.size, 8)).coerceAtMost(max)
        return scored.take(n).map { it.first }
    }

    /**
     * Intensité ressentie, pour ranger un set du plus retenu au plus
     * écrasant : le sommet doit tomber à la fin, pas au milieu.
     */
    fun intensity(t: Track): Float = t.energyPeak * (0.5f + 0.5f * of(t))
}
