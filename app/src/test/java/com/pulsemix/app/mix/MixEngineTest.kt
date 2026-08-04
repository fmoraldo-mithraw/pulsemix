package com.pulsemix.app.mix

import com.pulsemix.app.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des fonctions pures du moteur de mix. Elles décident quels
 * morceaux entrent dans un mix et lesquels sont des doublons : une
 * régression y est silencieuse à l'usage (un morceau joué deux fois, un
 * autre jamais choisi) et donc difficile à repérer sans filet.
 *
 * Rien ici ne touche à Android : ni PlayHistory, ni org.json.
 */
class MixEngineTest {

    private val EPS = 1e-6f

    private fun track(
        uri: String,
        title: String,
        artist: String = "",
        durationMs: Long = 0L
    ) = Track(uri = uri, title = title, artist = artist, durationMs = durationMs)

    // ------------------------------------------------------------ camelot

    @Test
    fun `meme cle donne 1`() {
        assertEquals(1f, MixEngine.camelotScore("8A", "8A"), EPS)
    }

    @Test
    fun `relative majeur mineur donne 0_8`() {
        assertEquals(0.8f, MixEngine.camelotScore("8A", "8B"), EPS)
    }

    @Test
    fun `voisine sur la roue donne 0_8`() {
        assertEquals(0.8f, MixEngine.camelotScore("8A", "9A"), EPS)
        assertEquals(0.8f, MixEngine.camelotScore("9A", "8A"), EPS)
    }

    @Test
    fun `la roue boucle entre 12 et 1`() {
        assertEquals(0.8f, MixEngine.camelotScore("12A", "1A"), EPS)
        assertEquals(0.8f, MixEngine.camelotScore("1A", "12A"), EPS)
    }

    @Test
    fun `cles eloignees donnent 0`() {
        assertEquals(0f, MixEngine.camelotScore("1A", "6A"), EPS)
    }

    @Test
    fun `cle inconnue donne 0`() {
        assertEquals(0f, MixEngine.camelotScore("--", "8A"), EPS)
        assertEquals(0f, MixEngine.camelotScore("8A", "--"), EPS)
        assertEquals(0f, MixEngine.camelotScore("", "8A"), EPS)
        assertEquals(0f, MixEngine.camelotScore("xA", "8A"), EPS)
    }

    // ------------------------------------------------------------- bandes

    @Test
    fun `les bandes de tempo se suivent sans trou`() {
        assertEquals(MixEngine.Band.CALME, MixEngine.bandOf(80f))
        assertEquals(MixEngine.Band.GROOVE, MixEngine.bandOf(95f))
        assertEquals(MixEngine.Band.DANCE, MixEngine.bandOf(115f))
        assertEquals(MixEngine.Band.INTENSE, MixEngine.bandOf(135f))
    }

    // ------------------------------------------------------------- genres

    @Test
    fun `le genre est normalise en minuscules sans suffixe`() {
        assertEquals("rock", MixEngine.normalizeGenre("Rock"))
        assertEquals("rock", MixEngine.normalizeGenre("  ROCK  "))
        assertEquals("rock", MixEngine.normalizeGenre("Rock;Pop"))
        assertEquals("rock", MixEngine.normalizeGenre("Rock/Metal"))
        assertEquals("", MixEngine.normalizeGenre(null))
    }

    // ---------------------------------------------------------- normTitle

    @Test
    fun `le numero de piste et l extension disparaissent`() {
        assertEquals(
            MixEngine.normTitle("le bien qui fait mal"),
            MixEngine.normTitle("03 - Le Bien Qui Fait Mal.mp3")
        )
    }

    @Test
    fun `l habillage youtube disparait`() {
        assertEquals(
            MixEngine.normTitle("Believer"),
            MixEngine.normTitle("Believer (Official Video)")
        )
        assertEquals(
            MixEngine.normTitle("Believer"),
            MixEngine.normTitle("Believer [Lyrics]")
        )
    }

    @Test
    fun `le suffixe d identifiant youtube disparait`() {
        assertEquals(
            MixEngine.normTitle("Believer"),
            MixEngine.normTitle("Believer [dQw4w9WgXcQ]")
        )
    }

    @Test
    fun `les accents et la ponctuation ne comptent pas`() {
        assertEquals(
            MixEngine.normTitle("Ou est passee ma tete"),
            MixEngine.normTitle("Où est passée ma tête !")
        )
    }

    @Test
    fun `deux chansons differentes ne se confondent pas`() {
        assertNotEquals(
            MixEngine.normTitle("Believer"),
            MixEngine.normTitle("Thunder")
        )
    }

    // ------------------------------------------------------------ dupKeys

    @Test
    fun `deux copies de la meme chanson partagent une cle`() {
        val a = track("content://a", "03 - Le Bien Qui Fait Mal.mp3", "Downloads", 245_000)
        val b = track("content://b", "le bien qui fait mal", "Mozart l'Opéra Rock", 246_500)
        val ka = MixEngine.dupKeys(a)
        val kb = MixEngine.dupKeys(b)
        assertTrue(
            "les deux copies devraient se reconnaître",
            ka.any { it in kb }
        )
    }

    @Test
    fun `deux chansons distinctes ne partagent aucune cle`() {
        val a = track("content://a", "Believer", "Imagine Dragons", 204_000)
        val b = track("content://b", "Thunder", "Imagine Dragons", 187_000)
        assertTrue(MixEngine.dupKeys(a).none { it in MixEngine.dupKeys(b) })
    }

    @Test
    fun `un titre vide retombe sur l uri seule`() {
        val t = track("content://a", "")
        assertEquals(listOf("content://a"), MixEngine.dupKeys(t))
    }

    @Test
    fun `l uri est toujours une cle`() {
        val t = track("content://a", "Believer", "Imagine Dragons", 204_000)
        assertTrue("content://a" in MixEngine.dupKeys(t))
    }
}
