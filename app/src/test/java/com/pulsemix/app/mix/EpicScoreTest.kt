package com.pulsemix.app.mix

import com.pulsemix.app.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sélection des morceaux épiques. Le contrat : les morceaux nommés épiques
 * définissent la référence de la bibliothèque et leurs semblables les
 * rejoignent ; sans référence, un classement relatif strict ; et une
 * bibliothèque sans épique n'en propose jamais.
 */
class EpicScoreTest {

    /** Un orchestral cinématique analysé avec le jeu de mesures courant. */
    private fun epique(
        uri: String,
        title: String = "Sun of Pearl",
        artist: String = "Inconnu"
    ) = Track(
        uri = uri, title = title, artist = artist, durationMs = 240_000,
        bpm = 92f, energyMean = 0.11f, energyPeak = 0.30f, centroid = 1900f,
        onsetRate = 1.2f, analyzed = true,
        lowMidRatio = 0.52f, sustainRatio = 0.80f,
        energySlope = 2.0f, dynamicSpread = 5.0f, featuresVersion = 2
    )

    /** Électro de club : plate, dense en attaques, sans montée. */
    private fun club(uri: String) = Track(
        uri = uri, title = "Pump It", artist = "DJ Y", durationMs = 200_000,
        bpm = 128f, energyMean = 0.24f, energyPeak = 0.30f, centroid = 3200f,
        onsetRate = 3.8f, analyzed = true,
        lowMidRatio = 0.22f, sustainRatio = 0.30f,
        energySlope = 1.05f, dynamicSpread = 1.5f, featuresVersion = 2
    )

    private fun anchors(n: Int) = List(n) {
        epique("a:$it", title = "Rise $it", artist = "Two Steps From Hell")
    }

    // ------------------------------------------------------------- noms

    @Test
    fun `les editeurs connus sont reconnus au nom`() {
        assertTrue(EpicScore.namedEpic(epique("x", artist = "Two Steps From Hell")))
        assertTrue(EpicScore.namedEpic(epique("x", artist = "Audiomachine")))
        assertTrue(EpicScore.namedEpic(epique("x", title = "Epic Trailer Music")))
        assertTrue(EpicScore.namedEpic(epique("x", title = "Cinematic Rise")))
    }

    @Test
    fun `un morceau ordinaire n est pas reconnu au nom`() {
        assertFalse(EpicScore.namedEpic(club("x")))
        assertFalse(EpicScore.namedEpic(epique("x", title = "Slow Burn")))
    }

    // ------------------------------------------------- voie par ancres

    @Test
    fun `les semblables des ancres rejoignent la selection`() {
        val lib = anchors(4) +
            List(6) { epique("jumeau:$it", title = "Voyage $it") } +
            List(30) { club("c:$it") }
        val sel = EpicScore.select(lib)
        assertTrue("les ancres entrent de droit", sel.any { it.uri.startsWith("a:") })
        assertTrue(
            "les jumeaux acoustiques doivent suivre les ancres",
            List(6) { "jumeau:$it" }.all { u -> sel.any { it.uri == u } }
        )
        assertTrue(
            "aucun morceau de club ne ressemble aux ancres",
            sel.none { it.uri.startsWith("c:") }
        )
    }

    @Test
    fun `un profil eloigne des ancres reste dehors meme nombreux`() {
        val lib = anchors(3) + List(60) { club("c:$it") }
        val sel = EpicScore.select(lib)
        assertEquals(
            "seules les ancres : la masse de club ne force pas l'entrée",
            3, sel.size
        )
    }

    @Test
    fun `des ancres pas encore reanalysees restent seules selectionnees`() {
        // featuresVersion 0 : le profil de référence serait fait de zéros.
        // Les ancres nommées restent sûres, la ressemblance attend.
        val vieilles = anchors(4).map {
            it.copy(
                lowMidRatio = 0f, sustainRatio = 0f,
                energySlope = 0f, dynamicSpread = 0f, featuresVersion = 0
            )
        }
        val lib = vieilles +
            List(6) { epique("libre:$it", title = "Voyage $it") } +
            List(20) { club("c:$it") }
        val sel = EpicScore.select(lib)
        assertTrue(sel.isNotEmpty())
        assertTrue(
            "sans profil mesuré, seuls les noms font foi",
            sel.all { EpicScore.namedEpic(it) }
        )
    }

    // -------------------------------------------------- voie par rangs

    @Test
    fun `sans ancres une minorite epique est trouvee par les rangs`() {
        val lib = List(10) { epique("e:$it", title = "Voyage $it") } +
            List(30) { club("c:$it") }
        val sel = EpicScore.select(lib)
        assertTrue("la minorité épique doit ressortir", sel.isNotEmpty())
        assertTrue(sel.all { it.uri.startsWith("e:") })
    }

    @Test
    fun `une bibliotheque uniforme n en propose aucun`() {
        // Tout le monde à égalité : personne n'est « plus épique », et le
        // plancher refuse de désigner un moins pire.
        val lib = List(40) { club("c:$it") }
        assertTrue(EpicScore.select(lib).isEmpty())
    }

    // ------------------------------------------------------- « pas épique »

    @Test
    fun `un morceau marque pas epique n est jamais retenu meme nomme`() {
        val banni = epique("banni", title = "Epic Party", artist = "Two Steps From Hell")
            .copy(notEpic = true)
        val lib = anchors(4) + banni + List(20) { club("c:$it") }
        assertTrue(EpicScore.select(lib).none { it.uri == "banni" })
    }

    @Test
    fun `un morceau ecarte ne sert plus de reference`() {
        // Trois ancres dont une écartée : il n'en reste que deux de
        // confiance — pas assez pour un profil, seuls les nommés restants
        // sont sélectionnés. L'écartée ne réapparaît nulle part.
        val lib = anchors(2) +
            anchors(1).map { it.copy(uri = "banni", notEpic = true) } +
            List(6) { epique("jumeau:$it", title = "Voyage $it") } +
            List(20) { club("c:$it") }
        val sel = EpicScore.select(lib)
        assertEquals(2, sel.size)
        assertTrue(sel.none { it.uri == "banni" })
    }

    @Test
    fun `un jumeau acoustique ecarte ne suit pas les ancres`() {
        val lib = anchors(4) +
            epique("jumeau:ok") +
            epique("jumeau:non").copy(notEpic = true) +
            List(20) { club("c:$it") }
        val sel = EpicScore.select(lib)
        assertTrue(sel.any { it.uri == "jumeau:ok" })
        assertTrue(sel.none { it.uri == "jumeau:non" })
    }

    // ---------------------------------------------------------- bornes

    @Test
    fun `les morceaux exclus ne sont jamais retenus`() {
        val lib = anchors(4).map { it.copy(excluded = true) } +
            List(10) { club("c:$it") }
        assertTrue(EpicScore.select(lib).isEmpty())
    }

    @Test
    fun `le plafond demande est respecte`() {
        val lib = anchors(4) + List(40) { epique("jumeau:$it", title = "V $it") }
        assertEquals(10, EpicScore.select(lib, max = 10).size)
    }

    @Test
    fun `une bibliotheque trop petite ne propose rien`() {
        assertTrue(EpicScore.select(anchors(2)).isEmpty())
    }

    // --------------------------------------------------------- intensité

    @Test
    fun `l intensite range du plus retenu au plus ecrasant`() {
        val doux = epique("d").copy(energyPeak = 0.14f)
        val fort = epique("f").copy(energyPeak = 0.34f)
        assertTrue(EpicScore.intensity(doux) < EpicScore.intensity(fort))
    }

    @Test
    fun `a sommet egal la masse tenue l emporte sur le coup sec`() {
        val tenu = epique("t").copy(energyPeak = 0.30f, sustainRatio = 0.85f)
        val sec = epique("s").copy(energyPeak = 0.30f, sustainRatio = 0.25f)
        assertTrue(EpicScore.intensity(sec) < EpicScore.intensity(tenu))
    }
}
