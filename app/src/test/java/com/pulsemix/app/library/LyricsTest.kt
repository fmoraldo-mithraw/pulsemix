package com.pulsemix.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du parseur LRC (Lyrics.parseLrc). Fonction pure : rien ici ne
 * touche à Android ni au réseau.
 */
class LyricsTest {

    @Test
    fun `lrc basique - lignes horodatees dans l ordre`() {
        val lines = Lyrics.parseLrc(
            "[00:12.00]Première ligne\n[00:15.50]Deuxième ligne"
        )
        assertEquals(
            listOf(12_000L to "Première ligne", 15_500L to "Deuxième ligne"),
            lines
        )
    }

    @Test
    fun `plusieurs timestamps sur une ligne - un refrain par temps`() {
        val lines = Lyrics.parseLrc("[00:10.00][01:10.00][02:10.00]Refrain")
        assertEquals(
            listOf(10_000L to "Refrain", 70_000L to "Refrain", 130_000L to "Refrain"),
            lines
        )
    }

    @Test
    fun `metadonnees et lignes sans timestamp ecartees`() {
        val lines = Lyrics.parseLrc(
            "[ar:Artiste]\n[ti:Titre]\ntexte nu sans timestamp\n[00:05.00]Seule vraie ligne"
        )
        assertEquals(listOf(5_000L to "Seule vraie ligne"), lines)
    }

    @Test
    fun `minutages en desordre - tries a la sortie`() {
        val lines = Lyrics.parseLrc(
            "[01:00.00]Après\n[00:30.00]Avant\n[02:00.00]Fin"
        )
        assertEquals(listOf(30_000L, 60_000L, 120_000L), lines.map { it.first })
        assertEquals("Avant", lines.first().second)
    }

    @Test
    fun `fractions a 1, 2 ou 3 chiffres - meme demi-seconde`() {
        assertEquals(1_500L, Lyrics.parseLrc("[00:01.5]x").first().first)
        assertEquals(1_500L, Lyrics.parseLrc("[00:01.50]x").first().first)
        assertEquals(1_500L, Lyrics.parseLrc("[00:01.500]x").first().first)
        // Sans fraction : la seconde ronde
        assertEquals(1_000L, Lyrics.parseLrc("[00:01]x").first().first)
    }

    @Test
    fun `texte vide ou sans aucun timestamp - aucune ligne`() {
        assertTrue(Lyrics.parseLrc("").isEmpty())
        assertTrue(Lyrics.parseLrc("juste du texte\nsur deux lignes").isEmpty())
    }
}
