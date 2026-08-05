package com.pulsemix.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Départager plusieurs enregistrements qui partagent une même empreinte
 * sonore. AcoustID leur donne le même score — c'est le même son — donc
 * seul le nom du fichier peut dire lequel on a sous la main.
 */
class NameMatchTest {

    // ------------------------------------------------------- nom de fichier

    @Test
    fun `le nom sort d une uri de l explorateur de documents`() {
        val uri = "content://com.android.externalstorage.documents/tree/" +
            "primary%3AMusic/document/primary%3AMusic%2F03%20-%20Everlong.mp3"
        assertEquals("03 - Everlong", NameMatch.fileNameOf(uri))
    }

    @Test
    fun `un chemin de fichier simple marche aussi`() {
        assertEquals("Everlong", NameMatch.fileNameOf("file:///music/Everlong.flac"))
    }

    @Test
    fun `un plus dans le nom n est pas transforme en espace`() {
        val uri = "content://x/document/primary%3AMusic%2FBlink+182%20-%20Dammit.mp3"
        assertEquals("Blink+182 - Dammit", NameMatch.fileNameOf(uri))
    }

    // ---------------------------------------------------------------- mots

    @Test
    fun `l habillage youtube ne compte pas comme un mot`() {
        assertEquals(
            NameMatch.tokens("Everlong"),
            NameMatch.tokens("Everlong (Official Video) [HD]")
        )
    }

    @Test
    fun `le numero de piste ne compte pas`() {
        assertEquals(NameMatch.tokens("Everlong"), NameMatch.tokens("03 - Everlong"))
    }

    @Test
    fun `un titre entierement numerique reste un mot`() {
        assertTrue("1979" in NameMatch.tokens("1979"))
    }

    @Test
    fun `les accents ne comptent pas`() {
        assertEquals(NameMatch.tokens("Ou est passee"), NameMatch.tokens("Où est passée"))
    }

    @Test
    fun `live et remaster restent des mots significatifs`() {
        // Ce sont eux qui distinguent deux versions : les effacer
        // reviendrait à renoncer à choisir
        assertTrue("live" in NameMatch.tokens("Everlong (Live)"))
        assertTrue("remastered" in NameMatch.tokens("Everlong (Remastered)"))
    }

    // ---------------------------------------------------------- proximité

    private fun sim(file: String, title: String, artist: String) =
        NameMatch.similarityToFile(NameMatch.tokens(file), title, artist)

    @Test
    fun `la version studio bat la version live quand le fichier ne dit rien`() {
        val fichier = "Foo Fighters - Everlong"
        assertTrue(
            sim(fichier, "Everlong", "Foo Fighters") >
                sim(fichier, "Everlong (Live at Wembley Stadium)", "Foo Fighters")
        )
    }

    @Test
    fun `la version live gagne quand le fichier le dit`() {
        val fichier = "Foo Fighters - Everlong (Live at Wembley Stadium)"
        assertTrue(
            sim(fichier, "Everlong (Live at Wembley Stadium)", "Foo Fighters") >
                sim(fichier, "Everlong", "Foo Fighters")
        )
    }

    @Test
    fun `le vrai artiste bat un credit de compilation`() {
        val fichier = "Foo Fighters - Everlong"
        assertTrue(
            sim(fichier, "Everlong", "Foo Fighters") >
                sim(fichier, "Everlong", "Various Artists")
        )
    }

    @Test
    fun `la version simple bat la remasterisee quand le fichier se tait`() {
        val fichier = "Nirvana - Come As You Are"
        assertTrue(
            sim(fichier, "Come As You Are", "Nirvana") >
                sim(fichier, "Come As You Are (Remastered 2011)", "Nirvana")
        )
    }

    @Test
    fun `un fichier bien nomme reconnait exactement son enregistrement`() {
        assertEquals(
            1f,
            sim("Foo Fighters - Everlong", "Everlong", "Foo Fighters"),
            1e-6f
        )
    }

    @Test
    fun `un nom de fichier sans rapport donne une proximite nulle`() {
        assertEquals(0f, sim("piste 04", "Everlong", "Foo Fighters"), 1e-6f)
    }

    @Test
    fun `l habillage youtube du fichier ne penalise pas le candidat`() {
        assertEquals(
            1f,
            sim("Foo Fighters - Everlong (Official Video)", "Everlong", "Foo Fighters"),
            1e-6f
        )
    }

    @Test
    fun `un ensemble vide ne ressemble a rien`() {
        assertEquals(0f, NameMatch.similarity(emptySet(), setOf("a")), 1e-6f)
        assertEquals(0f, NameMatch.similarity(setOf("a"), emptySet()), 1e-6f)
    }
}
