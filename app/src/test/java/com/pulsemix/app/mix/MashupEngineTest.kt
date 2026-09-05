package com.pulsemix.app.mix

import com.pulsemix.app.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du moteur de mashup (fonctions pures) : calage de tempo, tonalités,
 * sélection des partenaires, forme du plan et graphe ffmpeg.
 */
class MashupEngineTest {

    private fun track(
        uri: String,
        bpm: Float,
        camelot: String,
        durationMs: Long = 240_000L,
        energy: Float = 0.5f,
        lowMid: Float = 0.3f,
        excluded: Boolean = false,
        analyzed: Boolean = true
    ) = Track(
        uri = uri, title = uri, artist = "", durationMs = durationMs,
        bpm = bpm, camelot = camelot, energyMean = energy, lowMidRatio = lowMid,
        bestStartMs = 60_000L, segmentMs = 60_000L, firstBeatMs = 60_400L,
        analyzed = analyzed, excluded = excluded
    )

    @Test
    fun `tempoMatch - meme tempo, moyenne geometrique et vitesses unitaires`() {
        val t = MashupEngine.tempoMatch(128f, 128f)
        assertNotNull(t)
        assertEquals(128f, t!!.target, 1e-3f)
        assertEquals(1f, t.rateA, 1e-6f)
        assertEquals(1f, t.rateB, 1e-6f)
        assertEquals(1f, t.factorB, 0f)
    }

    @Test
    fun `tempoMatch - ecart de 6 pourcents accepte, chacun fait la moitie du chemin`() {
        val t = MashupEngine.tempoMatch(128f, 121f)
        assertNotNull(t)
        assertTrue(t!!.rateA < 1f && t.rateB > 1f)
        assertEquals(t.target, 128f * t.rateA, 1e-3f)
        assertEquals(t.target, 121f * t.rateB, 1e-3f)
        assertTrue(t.deviationPct < 8f)
    }

    @Test
    fun `tempoMatch - octave inferieure retenue, ecart trop grand refuse`() {
        val half = MashupEngine.tempoMatch(128f, 64f)
        assertNotNull(half)
        assertEquals(2f, half!!.factorB, 0f)
        assertEquals(1f, half.rateB, 1e-6f)
        assertNull(MashupEngine.tempoMatch(128f, 110f))
        assertNull(MashupEngine.tempoMatch(0f, 110f))
    }

    @Test
    fun `keyScore - meme cle, relative, voisine, etrangere`() {
        assertEquals(1f, MashupEngine.keyScore("8A", "8A"), 0f)
        assertEquals(0.9f, MashupEngine.keyScore("8A", "8B"), 0f)
        assertEquals(0.7f, MashupEngine.keyScore("8A", "9A"), 0f)
        assertEquals(0.7f, MashupEngine.keyScore("12A", "1A"), 0f)
        assertEquals(0f, MashupEngine.keyScore("8A", "3A"), 0f)
        assertEquals(0f, MashupEngine.keyScore("--", "8A"), 0f)
    }

    @Test
    fun `partBars - multiple de 4, borne a 16, zero sans matiere`() {
        assertEquals(16, MashupEngine.partBars(128, 128))
        assertEquals(16, MashupEngine.partBars(40, 60))
        assertEquals(12, MashupEngine.partBars(34, 100))
        assertEquals(8, MashupEngine.partBars(26, 100))
        assertEquals(0, MashupEngine.partBars(20, 100))
    }

    @Test
    fun `candidates - tri par score, exclus et incompatibles ecartes`() {
        val base = track("base", 128f, "8A", lowMid = 0.2f)
        val lib = listOf(
            base,
            track("same-key", 128f, "8A"),
            track("relative", 126f, "8B", lowMid = 0.5f),
            track("far-key", 128f, "3A"),
            track("far-tempo", 100f, "8A"),
            track("excluded", 128f, "8A", excluded = true),
            track("not-analyzed", 128f, "8A", analyzed = false),
            track("too-short", 128f, "8A", durationMs = 80_000L)
        )
        val c = MashupEngine.candidates(base, lib)
        val names = c.map { it.track.uri }
        assertEquals(listOf("same-key", "relative"), names)
        assertTrue(c[0].score > c[1].score)
        assertEquals(16, c[0].partBars)
        // Le partenaire plus « voix » pose les aigus : la base tient les basses
        assertFalse(c[1].baseTopFirst)
    }

    @Test
    fun `plan et graphe ffmpeg - forme et retard de B`() {
        val base = track("base", 128f, "8A")
        val partner = track("partner", 128f, "8A")
        val c = MashupEngine.candidates(base, listOf(base, partner)).first()
        val p = MashupEngine.plan(base, c)
        assertEquals(8 + 16 + 16 + 8, p.totalBars)
        assertEquals(1.875, p.barSeconds, 1e-9)
        assertEquals(48 * 1.875, p.durationSeconds, 1e-6)
        assertEquals(60_400L, p.anchorAMs)
        val g = MashupEngine.filterGraph(p)
        assertTrue(g.contains("atempo=1.00000"))
        assertTrue(g.contains("adelay=delays=15000:all=1"))
        assertTrue(g.contains("lowpass=f=300"))
        assertTrue(g.contains("highpass=f=300"))
        assertTrue(g.contains("amix=inputs=6"))
        assertTrue(g.contains("atrim=duration=90.000"))
        assertTrue(g.endsWith("[out]"))
        assertEquals("Mashup - base x partner", MashupEngine.fileBaseName(p))
    }
}
