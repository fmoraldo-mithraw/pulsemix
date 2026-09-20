package com.pulsemix.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du profil phrase par phrase (fonctions pures) : grille, calcul sur
 * des niveaux synthétiques, encodage, hook et voix.
 */
class PhraseProfileTest {

    @Test
    fun `grille - origine avant la premiere phrase, indice par ms`() {
        // 128 BPM : phrase de 7,5 s ; premier beat à 10 s → origine 2,5 s
        assertEquals(7_500.0, PhraseProfile.phraseMs(128f), 1e-9)
        assertEquals(2_500.0, PhraseProfile.originMs(128f, 10_000L), 1e-6)
        assertEquals(0, PhraseProfile.phraseIndex(0L, 128f, 10_000L))
        assertEquals(0, PhraseProfile.phraseIndex(9_999L, 128f, 10_000L))
        assertEquals(1, PhraseProfile.phraseIndex(10_000L, 128f, 10_000L))
        assertEquals(3, PhraseProfile.phraseIndex(25_000L, 128f, 10_000L))
        assertEquals(0, PhraseProfile.phraseIndex(25_000L, 0f, 10_000L))
    }

    @Test
    fun `compute - une phrase calme puis un drop, encodage aller-retour`() {
        // Blocs de 100 ms ; 128 BPM, premier beat à 0 : phrases de 75 blocs.
        // Phrase 0 : calme, peu de basses, médiums présents (couplet chanté)
        // Phrase 1 : drop, fort, basses pleines, médiums en retrait
        val n = 150
        val rms = FloatArray(n) { if (it < 75) 0.3f else 1.0f }
        val bass = FloatArray(n) { if (it < 75) 0.06f else 0.8f }
        val mid = FloatArray(n) { if (it < 75) 0.2f else 0.3f }
        val prof = PhraseProfile.compute(rms, bass, mid, 100.0, 128f, 0L, 15_000L)
        assertEquals(2, prof.size)
        assertEquals(0.3f, prof[0].energy, 0.02f)
        assertEquals(1f, prof[1].energy, 0.02f)
        assertEquals(0.2f, prof[0].bass, 0.02f)
        assertEquals(0.8f, prof[1].bass, 0.02f)
        assertTrue(prof[0].mid > prof[1].mid)
        val text = PhraseProfile.encode(prof)
        assertEquals(2, text.split(';').size)
        val back = PhraseProfile.decode(text)
        assertEquals(prof.size, back.size)
        for (i in prof.indices) {
            assertEquals(prof[i].energy, back[i].energy, 0.011f)
            assertEquals(prof[i].bass, back[i].bass, 0.011f)
            assertEquals(prof[i].mid, back[i].mid, 0.011f)
        }
        assertTrue(PhraseProfile.decode("").isEmpty())
        assertEquals(3, PhraseProfile.decode("10:20:30;n importe quoi;1:2:3").size)
        assertTrue(PhraseProfile.compute(rms, bass, mid, 100.0, 0f, 0L, 15_000L).isEmpty())
    }

    @Test
    fun `hook et voix - le relief des mediums et le niveau font le hook`() {
        val prof = listOf(
            PhraseProfile.Phrase(0.4f, 0.5f, 0.30f), // couplet
            PhraseProfile.Phrase(0.5f, 0.5f, 0.32f),
            PhraseProfile.Phrase(0.9f, 0.7f, 0.45f), // refrain chanté
            PhraseProfile.Phrase(0.9f, 0.8f, 0.28f), // drop instrumental
            PhraseProfile.Phrase(0.2f, 0.2f, 0.31f)  // break
        )
        assertTrue(PhraseProfile.isVocal(prof, 2))
        assertFalse(PhraseProfile.isVocal(prof, 3))
        assertFalse(PhraseProfile.isVocal(prof, 4)) // trop calme
        assertFalse(PhraseProfile.isVocal(prof, 99))
        assertTrue(PhraseProfile.hookScore(prof, 2) > PhraseProfile.hookScore(prof, 3))
        assertTrue(PhraseProfile.hookScore(prof, 3) > PhraseProfile.hookScore(prof, 4))
        assertEquals(0f, PhraseProfile.hookScore(prof, 99), 0f)
    }
}
