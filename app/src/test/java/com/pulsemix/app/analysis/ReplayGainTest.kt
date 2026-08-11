package com.pulsemix.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests du gain de normalisation mesuré (AudioAnalyzer.gainDbFor /
 * gainFactor) : le mapping RMS → dB → facteur linéaire est pur.
 */
class ReplayGainTest {

    @Test
    fun `au niveau cible - aucun gain`() {
        assertEquals(
            0f,
            AudioAnalyzer.gainDbFor(AudioAnalyzer.LOUDNESS_TARGET),
            1e-4f
        )
    }

    @Test
    fun `deux fois trop fort - environ -6 dB, et le facteur rend 0_5`() {
        val db = AudioAnalyzer.gainDbFor(AudioAnalyzer.LOUDNESS_TARGET * 2f)
        assertEquals(-6.02f, db, 0.01f)
        assertEquals(0.5f, AudioAnalyzer.gainFactor(db), 1e-3f)
    }

    @Test
    fun `deux fois trop doux - environ +6 dB, et le facteur rend 2`() {
        val db = AudioAnalyzer.gainDbFor(AudioAnalyzer.LOUDNESS_TARGET / 2f)
        assertEquals(6.02f, db, 0.01f)
        assertEquals(2f, AudioAnalyzer.gainFactor(db), 1e-3f)
    }

    @Test
    fun `borne a plus ou moins 8 dB aux extremes`() {
        // Master écrasé à fond : la correction s'arrête à -8 dB
        assertEquals(-8f, AudioAnalyzer.gainDbFor(1f), 1e-4f)
        // Très doux mais mesurable : +8 dB au plus
        assertEquals(8f, AudioAnalyzer.gainDbFor(0.02f), 1e-4f)
    }

    @Test
    fun `trop silencieux pour etre fiable - 0 dB, formule historique`() {
        // <= 0,01 : pas de mesure exploitable, gainDb = 0 signifie
        // « retombe sur l'ancienne formule à base d'energyMean »
        assertEquals(0f, AudioAnalyzer.gainDbFor(0.005f), 0f)
        assertEquals(0f, AudioAnalyzer.gainDbFor(0f), 0f)
    }

    @Test
    fun `zero dB rend un facteur de 1`() {
        assertEquals(1f, AudioAnalyzer.gainFactor(0f), 1e-6f)
    }
}
