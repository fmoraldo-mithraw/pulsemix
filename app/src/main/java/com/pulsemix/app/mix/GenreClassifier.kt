package com.pulsemix.app.mix

import com.pulsemix.app.data.Track

/**
 * Déduit un type de musique quand le fichier n'a pas de tag genre :
 * d'abord par mots-clés du titre/artiste, sinon par la signature acoustique
 * (BPM, énergie, brillance, densité d'attaques) issue de l'analyse.
 * Les familles déduites par signature sont préfixées « ≈ » pour les
 * distinguer des vrais tags.
 */
object GenreClassifier {

    private val keywordGenres: List<Pair<Regex, String>> = listOf(
        "drum ?(&|and|'?n'?) ?bass|\\bdnb\\b|neurofunk" to "drum & bass",
        "dubstep" to "dubstep",
        "hardstyle|hardcore|gabber" to "hardstyle",
        "techno" to "techno",
        "trance" to "trance",
        "house" to "house",
        "hip.?hop|\\brap\\b|\\btrap\\b" to "hip-hop",
        "m[ée]tal\\b|metalcore" to "metal",
        "punk" to "punk",
        "\\brock\\b" to "rock",
        "jazz" to "jazz",
        "blues" to "blues",
        "funk" to "funk",
        "disco" to "disco",
        "reggae" to "reggae",
        "\\bska\\b" to "ska",
        "classi(cal|que)|symphon|orchestr|concerto|sonat" to "classique",
        "lo.?fi" to "lo-fi",
        "ambient" to "ambient",
        "chill" to "chill",
        "\\bost\\b|soundtrack|bande originale|\\btheme\\b|thème" to "bande originale",
        "electro|électro|\\bedm\\b" to "électro",
        "acousti(c|que)|unplugged|piano" to "acoustique",
        "\\bpop\\b" to "pop"
    ).map { (p, g) -> Regex(p, RegexOption.IGNORE_CASE) to g }

    /** @return genre déduit, ou « - » si rien de fiable. */
    fun infer(t: Track): String {
        val text = "${t.title} ${t.artist}"
        for ((rx, genre) in keywordGenres) {
            if (rx.containsMatchIn(text)) return genre
        }
        if (!t.analyzed || t.bpm <= 0f) return "-"
        // Signature acoustique -> famille de style
        val e = t.energyMean
        val o = t.onsetRate
        return when {
            e < 0.06f && o < 1.2f -> "≈ calme/ambient"
            t.bpm >= 158f && o >= 2f -> "≈ électro rapide"
            t.bpm in 118f..150f && o >= 2f && e >= 0.12f -> "≈ électro/danse"
            t.bpm in 70f..106f && o in 0.8f..3f -> "≈ groove/hip-hop"
            t.centroid > 2600f && e > 0.15f -> "≈ rock/énergique"
            else -> "≈ pop/variété"
        }
    }
}
