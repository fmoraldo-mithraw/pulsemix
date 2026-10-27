package com.pulsemix.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** En-tête WAV servi à ExoPlayer devant le PCM d'un WMA. */
class WavHeaderTest {
    @Test
    fun wavHeaderIsValid() {
        val h = WmaDataSource.wavHeader(44100, 2, 1_000L)
        assertEquals(WmaDataSource.HEADER, h.size)
        assertEquals("RIFF", String(h, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(h, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(h, 36, 4, Charsets.US_ASCII))
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1036, b.getInt(4))
        assertEquals(2.toShort(), b.getShort(22))
        assertEquals(44100, b.getInt(24))
        assertEquals(44100 * 4, b.getInt(28))
        assertEquals(1_000, b.getInt(40))
    }
}
