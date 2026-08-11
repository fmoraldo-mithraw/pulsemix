package com.pulsemix.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

class ShazamSignatureTest {

    /**
     * Signal déterministe riche en attaques (sinusoïdes modulées en phase
     * + bruit pseudo-aléatoire LCG) : quatre composantes, une par bande de
     * fréquences. Un signal STATIONNAIRE ne produit presque aucun pic —
     * l'algorithme ne retient que les maxima stricts en temps, c'est
     * voulu : il écoute des attaques, pas des drones.
     *
     * Attendu (rejoué hors JVM sur le portage de référence) :
     * bandes ≈ [18, 107, 287, 285], total ≈ 697. Les bornes des
     * assertions sont larges : seuls d'infimes écarts d'arrondi flottant
     * peuvent déplacer quelques pics, pas les déciles.
     */
    private fun richSignal(seconds: Int): ShortArray {
        var seed = 123456789L
        return ShortArray(16_000 * seconds) { i ->
            val t = i / 16_000.0
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val noise = ((seed ushr 33) % 4001L) - 2000L
            val v = 2800 * sin(2 * PI * 440 * t) +
                2400 * sin(2 * PI * 1200 * t + 4 * sin(2 * PI * 1.3 * t)) +
                2000 * sin(2 * PI * 2500 * t + 3 * sin(2 * PI * 0.7 * t)) +
                1200 * sin(2 * PI * 4000 * t + 2 * sin(2 * PI * 3.1 * t)) +
                0.5 * noise
            v.toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun u32(data: ByteArray, at: Int): Long =
        (data[at].toLong() and 0xff) or
            ((data[at + 1].toLong() and 0xff) shl 8) or
            ((data[at + 2].toLong() and 0xff) shl 16) or
            ((data[at + 3].toLong() and 0xff) shl 24)

    @Test
    fun `un signal riche produit des pics dans les quatre bandes`() {
        val sig = ShazamSignature.fromPcm(richSignal(5))
        assertTrue("total ${sig.peakCount}", sig.peakCount in 300..1200)
        for (b in 0..3) {
            assertTrue("bande $b vide", sig.bands[b].isNotEmpty())
        }
        // Les passes croissent dans chaque bande (exigé par l'encodage delta)
        for (b in 0..3) {
            for (i in 1 until sig.bands[b].size) {
                assertTrue(sig.bands[b][i].pass >= sig.bands[b][i - 1].pass)
            }
        }
    }

    @Test
    fun `le silence ne produit aucun pic`() {
        val sig = ShazamSignature.fromPcm(ShortArray(16_000 * 4))
        assertEquals(0, sig.peakCount)
    }

    @Test
    fun `format binaire valide et rejouable`() {
        val sig = ShazamSignature.fromPcm(richSignal(4))
        assertTrue(sig.peakCount > 0)
        val bin = sig.encodeToBinary()

        // En-tête : magies, CRC sur tout sauf les 8 premiers octets,
        // tailles cohérentes, identifiant 16 kHz, nombre d'échantillons
        assertEquals(0xcafe2580L, u32(bin, 0))
        val crc = CRC32().apply { update(bin, 8, bin.size - 8) }
        assertEquals(crc.value, u32(bin, 4))
        assertEquals((bin.size - 48).toLong(), u32(bin, 8))
        assertEquals(0x94119c00L, u32(bin, 12))
        assertEquals((3L shl 27), u32(bin, 28))
        assertEquals((16_000 * 4 + 3840).toLong(), u32(bin, 40))
        assertEquals(0x40000000L, u32(bin, 48))
        assertEquals((bin.size - 48).toLong(), u32(bin, 52))

        // Rejouer les listes de pics et retrouver exactement l'original
        var pos = 56
        val decoded = Array(4) { mutableListOf<Triple<Int, Int, Int>>() }
        while (pos < bin.size) {
            val band = (u32(bin, pos) - 0x60030040L).toInt()
            val size = u32(bin, pos + 4).toInt()
            assertTrue(band in 0..3)
            var p = pos + 8
            val end = p + size
            var pass = 0
            while (p < end) {
                val head = bin[p].toInt() and 0xff
                if (head == 0xff) {
                    pass = u32(bin, p + 1).toInt()
                    p += 5
                } else {
                    pass += head
                    val mag = (bin[p + 1].toInt() and 0xff) or
                        ((bin[p + 2].toInt() and 0xff) shl 8)
                    val fbin = (bin[p + 3].toInt() and 0xff) or
                        ((bin[p + 4].toInt() and 0xff) shl 8)
                    decoded[band].add(Triple(pass, mag, fbin))
                    p += 5
                }
            }
            pos = end + (4 - size % 4) % 4
        }
        for (b in 0..3) {
            assertEquals(sig.bands[b].size, decoded[b].size)
            for ((i, peak) in sig.bands[b].withIndex()) {
                assertEquals(peak.pass, decoded[b][i].first)
                assertEquals(peak.magnitude, decoded[b][i].second)
                assertEquals(peak.bin, decoded[b][i].third)
            }
        }
    }

    @Test
    fun `l'uri est prefixee et en base64`() {
        val sig = ShazamSignature.fromPcm(richSignal(4))
        val uri = sig.encodeToUri()
        assertTrue(uri.startsWith("data:audio/vnd.shazam.sig;base64,"))
        val decoded = java.util.Base64.getDecoder()
            .decode(uri.substringAfter("base64,"))
        assertEquals(0xcafe2580L, u32(decoded, 0))
    }
}
