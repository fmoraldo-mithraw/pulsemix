package com.pulsemix.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Décodeur WMA natif sur la JVM (ffmpeg bytedeco linux-x86_64), sur un
 * vrai WMA v2 de 4 s (bruit rose, tags) : tags, durée, et seek EXACT à
 * l'échantillon — le moteur DJ et le raccord du mode Mix en dépendent.
 */
class WmaDecoderTest {

    private val file: File =
        File(javaClass.classLoader!!.getResource("sample.wma")!!.toURI())

    private fun pcm(d: WmaDecoder, maxFrames: Int = Int.MAX_VALUE): ShortArray {
        val out = ByteArrayOutputStream()
        var frames = 0
        while (frames < maxFrames) {
            val n = d.decodeNext { p, len ->
                val b = ByteArray(len)
                p.get(b, 0, len)
                out.write(b)
            }
            if (n < 0) break
            frames += n / (2 * d.channels)
        }
        val sb = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(sb.remaining()).also { sb.get(it) }
    }

    @Test
    fun readsTagsAndWholeFile() {
        WmaDecoder(file.absolutePath).use { d ->
            assertEquals(44100, d.sampleRate)
            assertEquals(2, d.channels)
            assertEquals("Essai WMA", d.tags["title"])
            assertEquals("PulseMix", d.tags["artist"])
            assertEquals("Rock", d.tags["genre"])
            val all = pcm(d)
            val secs = all.size / 2 / 44100.0
            assertTrue("durée décodée $secs s", secs in 3.9..4.05)
            assertTrue("silence ?", all.any { kotlin.math.abs(it.toInt()) > 1000 })
        }
    }

    @Test
    fun seekIsSampleExact() {
        val full = WmaDecoder(file.absolutePath).use { pcm(it) }
        for (sec in listOf(0.0, 0.25, 1.0, 1.7, 2.5, 3.3)) {
            val idx = (sec * 44100).toLong()
            val seg = WmaDecoder(file.absolutePath).use { d ->
                d.seekFrames(idx)
                pcm(d, 4096)
            }
            val n = minOf(seg.size, full.size - (idx * 2).toInt(), 4096 * 2)
            assertTrue(n > 0)
            for (i in 0 until n) {
                assertEquals("seek $sec s, échantillon $i", full[(idx * 2).toInt() + i], seg[i])
            }
        }
    }
}
