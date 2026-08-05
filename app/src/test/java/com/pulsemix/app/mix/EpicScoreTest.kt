package com.pulsemix.app.mix

import com.pulsemix.app.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reconnaissance des morceaux épiques. Le piège est de confondre l'épique
 * avec « fort », avec « calme » ou avec « qui monte » : chacun de ces
 * signaux se retrouve ailleurs, c'est leur réunion qui signe.
 */
class EpicScoreTest {

    /** Un orchestral cinématique : chœurs tenus, crescendo, grande dynamique. */
    private fun epique(title: String = "Sun of Pearl", artist: String = "X") = Track(
        uri = "u:$title", title = title, artist = artist, durationMs = 240_000,
        bpm = 92f, energyMean = 0.11f, energyPeak = 0.30f, centroid = 1900f,
        onsetRate = 1.2f, analyzed = true,
        lowMidRatio = 0.52f, sustainRatio = 0.80f,
        energySlope = 2.0f, dynamicSpread = 5.0f, featuresVersion = 2
    )

    /** Électro de club : plate, dense en attaques, sans montée. */
    private fun club() = Track(
        uri = "u:club", title = "Pump It", artist = "DJ Y", durationMs = 200_000,
        bpm = 128f, energyMean = 0.24f, energyPeak = 0.30f, centroid = 3200f,
        onsetRate = 3.8f, analyzed = true,
        lowMidRatio = 0.22f, sustainRatio = 0.30f,
        energySlope = 1.05f, dynamicSpread = 1.5f, featuresVersion = 2
    )

    /** Ambient : tenu et calme, mais ne monte pas et ne tape jamais. */
    private fun ambient() = Track(
        uri = "u:amb", title = "Drifting", artist = "Z", durationMs = 300_000,
        bpm = 70f, energyMean = 0.04f, energyPeak = 0.06f, centroid = 1400f,
        onsetRate = 0.5f, analyzed = true,
        lowMidRatio = 0.42f, sustainRatio = 0.90f,
        energySlope = 1.0f, dynamicSpread = 2.0f, featuresVersion = 2
    )

    /** Ballade rock : respire et monte, mais peu de tenu et peu de bas-médium. */
    private fun ballade() = Track(
        uri = "u:bal", title = "Slow Burn", artist = "W", durationMs = 260_000,
        bpm = 76f, energyMean = 0.10f, energyPeak = 0.26f, centroid = 2900f,
        onsetRate = 2.4f, analyzed = true,
        lowMidRatio = 0.24f, sustainRatio = 0.45f,
        energySlope = 1.9f, dynamicSpread = 4.2f, featuresVersion = 2
    )

    // ------------------------------------------------------------ le tri

    @Test
    fun `l orchestral cinematique est reconnu`() {
        assertTrue("score = ${EpicScore.of(epique())}", EpicScore.of(epique()) >= EpicScore.FLOOR)
    }

    @Test
    fun `l electro de club ne l est pas`() {
        assertTrue(EpicScore.of(club()) < EpicScore.FLOOR)
    }

    @Test
    fun `l ambient ne l est pas malgre son son tenu`() {
        assertTrue(
            "le tenu seul ne fait pas l'épique : il faut une montée et un sommet",
            EpicScore.of(ambient()) < EpicScore.FLOOR
        )
    }

    @Test
    fun `la ballade rock ne l est pas malgre sa montee`() {
        assertTrue(
            "monter et respirer ne suffit pas sans chœurs ni cuivres",
            EpicScore.of(ballade()) < EpicScore.of(epique())
        )
    }

    @Test
    fun `l epique devance tous les autres`() {
        val e = EpicScore.of(epique())
        assertTrue(e > EpicScore.of(club()))
        assertTrue(e > EpicScore.of(ambient()))
        assertTrue(e > EpicScore.of(ballade()))
    }

    // ------------------------------------------------------------- noms

    @Test
    fun `les editeurs connus sont reconnus au nom`() {
        assertTrue(EpicScore.namedEpic(epique(artist = "Two Steps From Hell")))
        assertTrue(EpicScore.namedEpic(epique(artist = "Audiomachine")))
        assertTrue(EpicScore.namedEpic(epique(title = "Epic Trailer Music")))
        assertTrue(EpicScore.namedEpic(epique(title = "Cinematic Rise")))
    }

    @Test
    fun `un morceau ordinaire n est pas reconnu au nom`() {
        assertFalse(EpicScore.namedEpic(club()))
        assertFalse(EpicScore.namedEpic(ballade()))
    }

    @Test
    fun `un nom evocateur ne rachete pas un son plat`() {
        val platMaisNomme = club().copy(title = "Epic Party Anthem")
        assertTrue(
            "le son doit garder le dernier mot",
            EpicScore.of(platMaisNomme) < EpicScore.of(epique())
        )
    }

    @Test
    fun `un morceau non analyse ne compte que sur son nom`() {
        val brut = Track(
            uri = "u:brut", title = "Epic Trailer", artist = "?", durationMs = 0
        )
        assertTrue(EpicScore.of(brut) > 0f)
        assertTrue("sans analyse, jamais la certitude", EpicScore.of(brut) < 0.7f)
    }

    @Test
    fun `un morceau analyse avec l ancien jeu de mesures ne passe pas seul`() {
        // featuresVersion 0 : ni montée ni son tenu enregistrés
        val ancien = epique().copy(
            lowMidRatio = 0f, sustainRatio = 0f,
            energySlope = 0f, dynamicSpread = 0f, featuresVersion = 0
        )
        assertTrue(EpicScore.of(ancien) < EpicScore.FLOOR)
    }

    // --------------------------------------------------------- sélection

    @Test
    fun `une bibliotheque sans epique n en propose aucun`() {
        val ordinaire = List(40) { club().copy(uri = "u:$it") }
        assertTrue(
            "être le moins plat d'une discothèque plate ne rend pas épique",
            EpicScore.select(ordinaire).isEmpty()
        )
    }

    @Test
    fun `la selection garde les plus marques`() {
        val lib = List(12) { epique(title = "E$it").copy(uri = "e:$it") } +
            List(30) { club().copy(uri = "c:$it") }
        val sel = EpicScore.select(lib)
        assertTrue(sel.isNotEmpty())
        assertTrue(
            "aucun morceau de club ne doit entrer",
            sel.none { it.uri.startsWith("c:") }
        )
    }

    @Test
    fun `la selection ne depasse pas le plafond demande`() {
        val lib = List(60) { epique(title = "E$it").copy(uri = "e:$it") }
        assertEquals(10, EpicScore.select(lib, max = 10).size)
    }

    @Test
    fun `les morceaux exclus ne sont jamais retenus`() {
        val lib = List(12) { epique(title = "E$it").copy(uri = "e:$it", excluded = true) }
        assertTrue(EpicScore.select(lib).isEmpty())
    }

    // --------------------------------------------------------- intensité

    @Test
    fun `l intensite range du plus retenu au plus ecrasant`() {
        val doux = epique(title = "Doux").copy(uri = "d", energyPeak = 0.14f)
        val fort = epique(title = "Fort").copy(uri = "f", energyPeak = 0.34f)
        assertTrue(EpicScore.intensity(doux) < EpicScore.intensity(fort))
    }
}
