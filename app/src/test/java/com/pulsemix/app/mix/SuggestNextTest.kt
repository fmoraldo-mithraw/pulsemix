package com.pulsemix.app.mix

import com.pulsemix.app.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de MixEngine.suggestNext (« et ensuite ? »). La fonction est PURE :
 * pénalités et paires ratées sont injectées, PlayHistory et
 * TransitionFeedback ne sont jamais touchés.
 */
class SuggestNextTest {

    private fun track(
        uri: String,
        bpm: Float = 120f,
        camelot: String = "8A",
        energy: Float = 0.2f,
        excluded: Boolean = false
    ) = Track(
        uri = uri,
        title = uri,
        artist = "",
        durationMs = 200_000L,
        bpm = bpm,
        camelot = camelot,
        energyMean = energy,
        excluded = excluded,
        analyzed = true
    )

    private val current = track("cur", bpm = 126f, camelot = "8A", energy = 0.2f)

    @Test
    fun `l harmonique est prefere a cle egale par ailleurs`() {
        val all = listOf(
            current,
            track("harmonique", bpm = 126f, camelot = "8A"),
            track("dissonant", bpm = 126f, camelot = "3A")
        )
        val out = MixEngine.suggestNext(current, all)
        assertEquals("harmonique", out.first().uri)
    }

    @Test
    fun `le bpm proche est prefere - double tempo admis`() {
        val all = listOf(
            current,
            track("proche", bpm = 128f),
            track("loin", bpm = 90f),
            // 63 BPM = la moitié de 126 : aussi bon qu'un 126 direct
            track("moitie", bpm = 63f)
        )
        val out = MixEngine.suggestNext(current, all)
        assertEquals("loin", out.last().uri)
        assertTrue(out.indexOfFirst { it.uri == "proche" } <
            out.indexOfFirst { it.uri == "loin" })
        assertTrue(out.indexOfFirst { it.uri == "moitie" } <
            out.indexOfFirst { it.uri == "loin" })
    }

    @Test
    fun `un morceau sur-joue recule dans le classement`() {
        val all = listOf(
            current,
            track("use", bpm = 126f),
            track("frais", bpm = 126f)
        )
        val out = MixEngine.suggestNext(
            current, all,
            penalize = { uri -> if (uri == "use") 1f else 0f }
        )
        assertEquals("frais", out.first().uri)
        assertEquals("use", out.last().uri)
    }

    @Test
    fun `une paire marquee ratee n est jamais proposee`() {
        val all = listOf(current, track("rate", bpm = 126f), track("ok", bpm = 126f))
        val out = MixEngine.suggestNext(
            current, all,
            isBadPair = { from, to -> from == "cur" && to == "rate" }
        )
        assertTrue(out.none { it.uri == "rate" })
        assertTrue(out.any { it.uri == "ok" })
    }

    @Test
    fun `au plus 5 suggestions - jamais le courant ni les exclus`() {
        val all = listOf(current, track("exclu", excluded = true)) +
            (1..8).map { track("c$it", bpm = 120f + it) }
        val out = MixEngine.suggestNext(current, all)
        assertEquals(5, out.size)
        assertTrue(out.none { it.uri == "cur" })
        assertTrue(out.none { it.uri == "exclu" })
    }
}
