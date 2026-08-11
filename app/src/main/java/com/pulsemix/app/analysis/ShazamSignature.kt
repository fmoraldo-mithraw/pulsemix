package com.pulsemix.app.analysis

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Empreinte sonore « à la Shazam » d'un enregistrement micro : pics
 * spectraux saillants d'un signal 16 kHz mono, encodés dans le format
 * binaire compris par le service de reconnaissance de Shazam.
 *
 * Portage Kotlin de l'algorithme du projet libre SongRec
 * (https://github.com/marin-m/SongRec, GPL-3.0) — fichiers
 * core/fingerprinting/{algorithm,signature_format}.rs. Ce fichier en
 * dérive et reste sous la même licence GPL-3.0.
 *
 * Contrairement à l'empreinte AcoustID (qui exige le fichier exact),
 * celle-ci est conçue pour reconnaître un morceau capté dans la pièce,
 * bruit ambiant compris.
 */
object ShazamSignature {

    private const val SAMPLE_RATE = 16_000

    class Peak(val pass: Int, val magnitude: Int, val bin: Int)

    class Signature(
        val numberSamples: Int,
        /** Pics par bande de fréquences : 250-520, 520-1450, 1450-3500, 3500-5500 Hz. */
        val bands: Array<MutableList<Peak>>
    ) {
        val sampleMs: Int get() = (numberSamples * 1000L / SAMPLE_RATE).toInt()
        val peakCount: Int get() = bands.sumOf { it.size }

        fun encodeToUri(): String =
            "data:audio/vnd.shazam.sig;base64," +
                java.util.Base64.getEncoder().encodeToString(encodeToBinary())

        /** Format binaire : en-tête 48 octets + listes de pics par bande. */
        fun encodeToBinary(): ByteArray {
            val out = ByteArrayOutputStream()
            out.u32(-0x3501da80) // magic1 0xcafe2580
            out.u32(0) // crc32, rempli à la fin
            out.u32(0) // taille sans l'en-tête, remplie à la fin
            out.u32(-0x6bee6400) // magic2 0x94119c00
            out.u32(0); out.u32(0); out.u32(0) // vide
            out.u32(3 shl 27) // identifiant 16 kHz
            out.u32(0); out.u32(0) // vide
            out.u32(numberSamples + (SAMPLE_RATE * 0.24).toInt())
            out.u32((15 shl 19) + 0x40000)
            out.u32(0x40000000)
            out.u32(0) // taille répétée, remplie à la fin

            for ((bandIndex, peaks) in bands.withIndex()) {
                if (peaks.isEmpty()) continue
                val body = ByteArrayOutputStream()
                var pass = 0
                for (p in peaks) {
                    if (p.pass - pass >= 255) {
                        body.write(0xff)
                        body.u32(p.pass)
                        pass = p.pass
                    }
                    body.write(p.pass - pass)
                    body.u16(p.magnitude)
                    body.u16(p.bin)
                    pass = p.pass
                }
                val bytes = body.toByteArray()
                out.u32(0x60030040 + bandIndex)
                out.u32(bytes.size)
                out.write(bytes)
                repeat((4 - bytes.size % 4) % 4) { out.write(0) }
            }

            val data = out.toByteArray()
            val size = data.size - 48
            patchU32(data, 8, size)
            patchU32(data, 52, size)
            val crc = CRC32()
            crc.update(data, 8, data.size - 8)
            patchU32(data, 4, crc.value.toInt())
            return data
        }
    }

    /** Fenêtre de Hanning sur 2050 points, zéros des extrémités omis. */
    private val hanning = FloatArray(2048) { i ->
        (0.5 - 0.5 * cos(2.0 * Math.PI * (i + 1) / 2049.0)).toFloat()
    }

    /**
     * Calcule l'empreinte d'un signal PCM 16 kHz mono. FFT de 2048
     * échantillons tous les 128, étalement des pics en fréquence et en
     * temps, puis rétention des maxima locaux saillants par bande.
     */
    fun fromPcm(samples: ShortArray): Signature {
        val ring = ShortArray(2048)
        var ringIndex = 0
        val windowed = FloatArray(2048)
        val im = FloatArray(2048)
        val fftOutputs = Array(256) { FloatArray(1025) }
        var fftIndex = 0
        val spreadOutputs = Array(256) { FloatArray(1025) }
        var spreadIndex = 0
        var spreadDone = 0
        val bands = Array(4) { mutableListOf<Peak>() }
        val usable = samples.size - samples.size % 128

        var offset = 0
        while (offset < usable) {
            // --- FFT du contenu de l'anneau, fenêtré, les 128 nouveaux à la fin
            System.arraycopy(samples, offset, ring, ringIndex, 128)
            ringIndex = (ringIndex + 128) and 2047
            for (i in 0 until 2048) {
                windowed[i] = ring[(i + ringIndex) and 2047] * hanning[i]
                im[i] = 0f
            }
            fft(windowed, im)
            val mags = fftOutputs[fftIndex]
            for (i in 0..1024) {
                mags[i] = max(
                    (windowed[i] * windowed[i] + im[i] * im[i]) / (1 shl 17),
                    1e-10f
                )
            }
            fftIndex = (fftIndex + 1) and 255

            // --- Étalement des pics : en fréquence sur le présent…
            val spread = spreadOutputs[spreadIndex]
            System.arraycopy(mags, 0, spread, 0, 1025)
            for (pos in 0..1022) {
                spread[pos] = max(spread[pos], max(spread[pos + 1], spread[pos + 2]))
            }
            // …et en remontant le temps sur trois trames passées
            for (pos in 0..1024) {
                val v = spread[pos]
                for (former in intArrayOf(1, 3, 6)) {
                    val past = spreadOutputs[(spreadIndex - former) and 255]
                    if (past[pos] < v) past[pos] = v
                }
            }
            spreadIndex = (spreadIndex + 1) and 255
            spreadDone++

            // --- Reconnaissance de pics, 46 trames en arrière
            if (spreadDone >= 46) {
                recognizePeaks(
                    fftOutputs[(fftIndex - 46) and 255],
                    spreadOutputs, spreadIndex, spreadDone, bands
                )
            }
            offset += 128
        }
        return Signature(samples.size, bands)
    }

    private fun recognizePeaks(
        fftMinus46: FloatArray,
        spreadOutputs: Array<FloatArray>,
        spreadIndex: Int,
        spreadDone: Int,
        bands: Array<MutableList<Peak>>
    ) {
        val fftMinus49 = spreadOutputs[(spreadIndex - 49) and 255]
        for (bin in 10..1014) {
            if (fftMinus46[bin] < 1f / 64f || fftMinus46[bin] < fftMinus49[bin - 1]) continue

            // Maximum local en fréquence…
            var maxNeighbor = 0f
            for (off in intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)) {
                maxNeighbor = max(maxNeighbor, fftMinus49[bin + off])
            }
            if (fftMinus46[bin] <= maxNeighbor) continue

            // …et en temps, sur les trames alentour
            var maxOther = maxNeighbor
            for (off in intArrayOf(
                -53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249
            )) {
                maxOther = max(maxOther, spreadOutputs[(spreadIndex + off) and 255][bin - 1])
            }
            if (fftMinus46[bin] <= maxOther) continue

            val pass = spreadDone - 46
            val mag = max(ln(fftMinus46[bin]), 1f / 64f) * 1477.3f + 6144f
            val magBefore = max(ln(fftMinus46[bin - 1]), 1f / 64f) * 1477.3f + 6144f
            val magAfter = max(ln(fftMinus46[bin + 1]), 1f / 64f) * 1477.3f + 6144f
            val variation1 = mag * 2f - magBefore - magAfter
            if (variation1 <= 0f) continue
            val variation2 = (magAfter - magBefore) * 32f / variation1
            val correctedBin = bin * 64 + variation2.toInt()

            val frequencyHz = correctedBin * (SAMPLE_RATE / 2f / 1024f / 64f)
            val band = when (frequencyHz.toInt()) {
                in 250..519 -> 0
                in 520..1449 -> 1
                in 1450..3499 -> 2
                in 3500..5500 -> 3
                else -> continue
            }
            bands[band].add(Peak(pass, mag.toInt(), correctedBin))
        }
    }

    // Table de rotation précalculée pour n = 2048 (précision constante,
    // pas d'erreur accumulée par multiplications successives)
    private val twiddleR = FloatArray(1024) { cos(-2.0 * Math.PI * it / 2048.0).toFloat() }
    private val twiddleI = FloatArray(1024) { sin(-2.0 * Math.PI * it / 2048.0).toFloat() }

    /** FFT complexe itérative (Cooley-Tukey, base 2), en place, n = 2048. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tw = k * step
                    val tr = re[b] * twiddleR[tw] - im[b] * twiddleI[tw]
                    val ti = re[b] * twiddleI[tw] + im[b] * twiddleR[tw]
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun ByteArrayOutputStream.u32(v: Int) {
        write(v and 0xff)
        write((v ushr 8) and 0xff)
        write((v ushr 16) and 0xff)
        write((v ushr 24) and 0xff)
    }

    private fun ByteArrayOutputStream.u16(v: Int) {
        write(v and 0xff)
        write((v ushr 8) and 0xff)
    }

    private fun patchU32(data: ByteArray, at: Int, v: Int) {
        data[at] = (v and 0xff).toByte()
        data[at + 1] = ((v ushr 8) and 0xff).toByte()
        data[at + 2] = ((v ushr 16) and 0xff).toByte()
        data[at + 3] = ((v ushr 24) and 0xff).toByte()
    }
}
