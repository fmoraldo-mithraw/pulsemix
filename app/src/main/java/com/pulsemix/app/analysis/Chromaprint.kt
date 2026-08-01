package com.pulsemix.app.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Portage Kotlin de l'algorithme Chromaprint (AcoustID), configuration
 * TEST2 — celle produite par `fpcalc` et attendue par api.acoustid.org.
 * Référence : https://github.com/acoustid/chromaprint (MIT).
 *
 * Pipeline : PCM mono 11025 Hz → STFT (fenêtre de Hamming 4096,
 * pas 1365) → énergie par bande chroma (12 notes, 28-3520 Hz) →
 * filtrage temporel → normalisation → image intégrale → 16
 * classifieurs (filtres de Haar + quantification + code de Gray)
 * → suite de sous-empreintes 32 bits, compressée puis encodée en
 * base64-URL pour l'API.
 *
 * Kotlin pur (aucune dépendance Android) : testable sur JVM.
 */
class Chromaprint(maxSeconds: Int = 120) {

    companion object {
        const val SAMPLE_RATE = 11025
        const val ALGORITHM = 1 // CHROMAPRINT_ALGORITHM_TEST2

        private const val FRAME_SIZE = 4096
        private const val HOP = FRAME_SIZE / 3 // 1365 (overlap 2/3)
        private const val MIN_FREQ = 28.0
        private const val MAX_FREQ = 3520.0
        private const val NUM_BANDS = 12
        private const val MAX_FILTER_WIDTH = 16
        private const val IMAGE_ROWS = 257 // fenêtre glissante 256 + 1

        private val CHROMA_COEFS = doubleArrayOf(0.25, 0.75, 1.0, 0.75, 0.25)

        // Classifieurs TEST2 : (type de filtre, y, hauteur, largeur,
        // seuils t0/t1/t2) — constantes officielles, ne pas modifier.
        private val CLASSIFIERS = arrayOf(
            C(0, 4, 3, 15, 1.98215, 2.35817, 2.63523),
            C(4, 4, 6, 15, -1.03809, -0.651211, -0.282167),
            C(1, 0, 4, 16, -0.298702, 0.119262, 0.558497),
            C(3, 8, 2, 12, -0.105439, 0.0153946, 0.135898),
            C(3, 4, 4, 8, -0.142891, 0.0258736, 0.200632),
            C(4, 0, 3, 5, -0.826319, -0.590612, -0.368214),
            C(1, 2, 2, 9, -0.557409, -0.233035, 0.0534525),
            C(2, 7, 3, 4, -0.0646826, 0.00620476, 0.0784847),
            C(2, 6, 2, 16, -0.192387, -0.029699, 0.215855),
            C(2, 1, 3, 2, -0.0397818, -0.00568076, 0.0292026),
            C(5, 10, 1, 15, -0.53823, -0.369934, -0.190235),
            C(3, 6, 2, 10, -0.124877, 0.0296483, 0.139239),
            C(2, 1, 1, 14, -0.101475, 0.0225617, 0.231971),
            C(3, 5, 6, 4, -0.0799915, -0.00729616, 0.063262),
            C(1, 9, 2, 12, -0.272556, 0.019424, 0.302559),
            C(3, 4, 2, 14, -0.164292, -0.0321188, 0.0846339)
        )

        private val GRAY = intArrayOf(0, 1, 3, 2)

        // Fenêtre de Hamming, échelle 1/32767 (entrée int16)
        private val WINDOW = DoubleArray(FRAME_SIZE) {
            (0.54 - 0.46 * cos(it * 2.0 * PI / (FRAME_SIZE - 1))) / 32767.0
        }

        // Note chroma de chaque bin FFT (base la = 27,5 Hz)
        private const val MIN_INDEX = 10 // max(1, round(4096*28/11025))
        private const val MAX_INDEX = 1308 // min(2048, round(4096*3520/11025))
        private val NOTES = IntArray(MAX_INDEX) { i ->
            if (i < MIN_INDEX) 0 else {
                val freq = i.toDouble() * SAMPLE_RATE / FRAME_SIZE
                val octave = log2(freq / 27.5)
                (NUM_BANDS * (octave - floor(octave))).toInt()
            }
        }

        private const val B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        // Rééchantillonnage : demi-largeur du noyau sinc fenêtré
        private const val RESAMPLE_HALF = 16

        private val COS_TABLE =
            DoubleArray(FRAME_SIZE / 2) { cos(2.0 * PI * it / FRAME_SIZE) }
        private val SIN_TABLE =
            DoubleArray(FRAME_SIZE / 2) { sin(2.0 * PI * it / FRAME_SIZE) }
    }

    private class C(
        val type: Int, val y: Int, val h: Int, val w: Int,
        val t0: Double, val t1: Double, val t2: Double
    )

    // ------------------------------------------------------------ entrée PCM

    private val maxSamples = maxSeconds * SAMPLE_RATE
    private val samples = ShortArray(maxSamples)
    private var sampleCount = 0

    // État du rééchantillonneur streaming (source → 11025 Hz)
    private var srcRate = 0
    private var srcBuf = FloatArray(0)
    private var srcBufLen = 0
    private var srcBufStart = 0L // index absolu de srcBuf[0] dans la source
    private var nextOut = 0L // prochain échantillon de sortie à produire

    /**
     * Alimente avec du PCM float interleavé (n'importe quel taux et nombre
     * de canaux, constants sur toute la durée).
     * @return false quand la fenêtre d'analyse est pleine (on peut arrêter
     * de décoder).
     */
    fun feed(pcm: FloatArray, frames: Int, sampleRate: Int, channels: Int): Boolean {
        if (sampleCount >= maxSamples) return false
        if (srcRate == 0) srcRate = sampleRate
        val ch = if (channels < 1) 1 else channels

        if (srcRate == SAMPLE_RATE) {
            // Pas de rééchantillonnage : conversion directe
            var i = 0
            while (i < frames && sampleCount < maxSamples) {
                samples[sampleCount++] = toShort(mono(pcm, i, ch))
                i++
            }
            return sampleCount < maxSamples
        }

        // Ajoute le mono au tampon source
        ensureSrcCapacity(srcBufLen + frames)
        for (i in 0 until frames) {
            srcBuf[srcBufLen++] = mono(pcm, i, ch)
        }
        resamplePending(flush = false)
        return sampleCount < maxSamples
    }

    private fun mono(pcm: FloatArray, frame: Int, channels: Int): Float {
        if (channels == 1) return pcm[frame]
        var s = 0f
        val base = frame * channels
        for (c in 0 until channels) s += pcm[base + c]
        return s / channels
    }

    private fun toShort(x: Float): Short {
        val v = (x * 32768f).roundToInt()
        return v.coerceIn(-32768, 32767).toShort()
    }

    private fun ensureSrcCapacity(n: Int) {
        if (srcBuf.size < n) {
            srcBuf = srcBuf.copyOf(maxOf(n, srcBuf.size * 2, 1 shl 16))
        }
    }

    /** Sinc fenêtré (Hann), passe-bas à 0,8 × Nyquist de sortie. */
    private fun resamplePending(flush: Boolean) {
        val ratio = srcRate.toDouble() / SAMPLE_RATE
        val cutoff = 0.8 * min(1.0, 1.0 / ratio) // cycles/échantillon source ×π
        while (sampleCount < maxSamples) {
            val center = nextOut * ratio
            val first = floor(center).toLong() - RESAMPLE_HALF + 1
            val last = floor(center).toLong() + RESAMPLE_HALF
            if (!flush && last >= srcBufStart + srcBufLen) break
            var acc = 0.0
            var norm = 0.0
            var k = first
            while (k <= last) {
                val idx = (k - srcBufStart).toInt()
                val s = if (idx in 0 until srcBufLen) srcBuf[idx].toDouble() else 0.0
                val d = k - center
                val w = 0.5 + 0.5 * cos(PI * d / RESAMPLE_HALF) // Hann
                val sinc = if (d == 0.0) cutoff
                else sin(PI * cutoff * d) / (PI * d)
                acc += s * sinc * w
                norm += sinc * w
                k++
            }
            if (flush && first >= srcBufStart + srcBufLen) break
            // Gain unité au continu : normalisation par la somme du noyau
            samples[sampleCount++] = toShort(
                (if (norm > 1e-9) acc / norm else 0.0).toFloat()
            )
            nextOut++
        }
        // Jette ce qui ne servira plus
        val needed = floor((nextOut) * ratio).toLong() - RESAMPLE_HALF
        if (needed > srcBufStart) {
            val drop = (needed - srcBufStart).toInt().coerceIn(0, srcBufLen)
            if (drop > 0) {
                System.arraycopy(srcBuf, drop, srcBuf, 0, srcBufLen - drop)
                srcBufLen -= drop
                srcBufStart += drop
            }
        }
    }

    // ------------------------------------------------------------ calcul

    /** Sous-empreintes brutes (une par pas temporel). */
    fun rawFingerprint(): IntArray {
        if (srcRate != 0 && srcRate != SAMPLE_RATE) resamplePending(flush = true)
        val n = sampleCount
        if (n < FRAME_SIZE) return IntArray(0)

        val re = DoubleArray(FRAME_SIZE)
        val im = DoubleArray(FRAME_SIZE)
        val energy = DoubleArray(FRAME_SIZE / 2 + 1)
        val features = DoubleArray(NUM_BANDS)

        // Filtre chroma : anneau de 8 trames
        val ring = Array(8) { DoubleArray(NUM_BANDS) }
        var ringOffset = 0
        var ringSize = 1
        val filtered = DoubleArray(NUM_BANDS)

        // Image intégrale glissante
        val image = Array(IMAGE_ROWS) { DoubleArray(NUM_BANDS) }
        var numRows = 0

        val out = ArrayList<Int>(n / HOP + 1)

        var start = 0
        while (start + FRAME_SIZE <= n) {
            // FFT réelle de la trame fenêtrée
            for (i in 0 until FRAME_SIZE) {
                re[i] = samples[start + i] * WINDOW[i]
                im[i] = 0.0
            }
            fft(re, im)
            for (i in 0..FRAME_SIZE / 2) {
                energy[i] = re[i] * re[i] + im[i] * im[i]
            }

            // Chroma (12 bandes)
            java.util.Arrays.fill(features, 0.0)
            for (i in MIN_INDEX until MAX_INDEX) {
                features[NOTES[i]] += energy[i]
            }

            // Filtrage temporel [0.25, 0.75, 1, 0.75, 0.25]
            System.arraycopy(features, 0, ring[ringOffset], 0, NUM_BANDS)
            ringOffset = (ringOffset + 1) % 8
            val readyRow: DoubleArray? = if (ringSize >= CHROMA_COEFS.size) {
                val off = (ringOffset + 8 - CHROMA_COEFS.size) % 8
                java.util.Arrays.fill(filtered, 0.0)
                for (b in 0 until NUM_BANDS) {
                    for (j in CHROMA_COEFS.indices) {
                        filtered[b] += ring[(off + j) % 8][b] * CHROMA_COEFS[j]
                    }
                }
                // Normalisation euclidienne (seuil 0,01)
                var sq = 0.0
                for (b in 0 until NUM_BANDS) sq += filtered[b] * filtered[b]
                val norm = if (sq > 0) sqrt(sq) else 0.0
                if (norm < 0.01) {
                    java.util.Arrays.fill(filtered, 0.0)
                } else {
                    for (b in 0 until NUM_BANDS) filtered[b] /= norm
                }
                filtered
            } else {
                ringSize++
                null
            }

            if (readyRow != null) {
                // Ajout à l'image intégrale (sommes cumulées 2D)
                val row = image[numRows % IMAGE_ROWS]
                var acc = 0.0
                for (b in 0 until NUM_BANDS) {
                    acc += readyRow[b]
                    row[b] = acc
                }
                if (numRows > 0) {
                    val prev = image[(numRows - 1) % IMAGE_ROWS]
                    for (b in 0 until NUM_BANDS) row[b] += prev[b]
                }
                numRows++
                if (numRows >= MAX_FILTER_WIDTH) {
                    out.add(subfingerprint(image, numRows, numRows - MAX_FILTER_WIDTH))
                }
            }
            start += HOP
        }
        return out.toIntArray()
    }

    /** Empreinte compressée en base64-URL (format de l'API AcoustID). */
    fun fingerprint(): String? {
        val raw = rawFingerprint()
        if (raw.isEmpty()) return null
        return base64Url(compress(raw))
    }

    /** Durée d'audio réellement consommée, en secondes. */
    fun durationSeconds(): Int = sampleCount / SAMPLE_RATE

    // ------------------------------------------------- classifieurs / image

    private fun area(
        image: Array<DoubleArray>, numRows: Int,
        r1: Int, c1: Int, r2: Int, c2: Int
    ): Double {
        if (r1 == r2 || c1 == c2) return 0.0
        fun row(i: Int) = image[i % IMAGE_ROWS]
        return if (r1 == 0) {
            val r = row(r2 - 1)
            if (c1 == 0) r[c2 - 1] else r[c2 - 1] - r[c1 - 1]
        } else {
            val ra = row(r1 - 1)
            val rb = row(r2 - 1)
            if (c1 == 0) rb[c2 - 1] - ra[c2 - 1]
            else rb[c2 - 1] - ra[c2 - 1] - rb[c1 - 1] + ra[c1 - 1]
        }
    }

    private fun subfingerprint(
        image: Array<DoubleArray>, numRows: Int, offset: Int
    ): Int {
        var bits = 0
        for (c in CLASSIFIERS) {
            val v = applyFilter(image, numRows, c, offset)
            val q = if (v < c.t1) {
                if (v < c.t0) 0 else 1
            } else {
                if (v < c.t2) 2 else 3
            }
            bits = (bits shl 2) or GRAY[q]
        }
        return bits
    }

    private fun applyFilter(
        img: Array<DoubleArray>, numRows: Int, c: C, x: Int
    ): Double {
        val y = c.y
        val w = c.w
        val h = c.h
        fun ar(r1: Int, c1: Int, r2: Int, c2: Int) = area(img, numRows, r1, c1, r2, c2)
        val a: Double
        val b: Double
        when (c.type) {
            0 -> {
                a = ar(x, y, x + w, y + h); b = 0.0
            }
            1 -> {
                val h2 = h / 2
                a = ar(x, y + h2, x + w, y + h)
                b = ar(x, y, x + w, y + h2)
            }
            2 -> {
                val w2 = w / 2
                a = ar(x + w2, y, x + w, y + h)
                b = ar(x, y, x + w2, y + h)
            }
            3 -> {
                val w2 = w / 2
                val h2 = h / 2
                a = ar(x, y + h2, x + w2, y + h) + ar(x + w2, y, x + w, y + h2)
                b = ar(x, y, x + w2, y + h2) + ar(x + w2, y + h2, x + w, y + h)
            }
            4 -> {
                val h3 = h / 3
                a = ar(x, y + h3, x + w, y + 2 * h3)
                b = ar(x, y, x + w, y + h3) + ar(x, y + 2 * h3, x + w, y + h)
            }
            else -> {
                val w3 = w / 3
                a = ar(x + w3, y, x + 2 * w3, y + h)
                b = ar(x, y, x + w3, y + h) + ar(x + 2 * w3, y, x + w, y + h)
            }
        }
        return ln((1.0 + a) / (1.0 + b))
    }

    // ------------------------------------------------------------------ FFT

    /** FFT complexe radix-2 en place (taille 4096). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        // Permutation bit-reverse
        var j = 0
        for (i in 0 until n - 1) {
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
            val half = len shr 1
            val step = n / len
            for (i in 0 until n step len) {
                var k = 0
                for (jj in i until i + half) {
                    val wr = COS_TABLE[k]
                    val wi = -SIN_TABLE[k]
                    val xr = re[jj + half] * wr - im[jj + half] * wi
                    val xi = re[jj + half] * wi + im[jj + half] * wr
                    re[jj + half] = re[jj] - xr
                    im[jj + half] = im[jj] - xi
                    re[jj] += xr
                    im[jj] += xi
                    k += step
                }
            }
            len = len shl 1
        }
    }

    // ---------------------------------------------------------- compression

    private fun compress(data: IntArray): ByteArray {
        val normal = ArrayList<Int>(data.size * 8)
        val exceptional = ArrayList<Int>(data.size)

        fun process(x0: Int) {
            var x = x0
            var bit = 1
            var lastBit = 0
            while (x != 0) {
                if (x and 1 != 0) {
                    val value = bit - lastBit
                    if (value >= 7) {
                        normal.add(7)
                        exceptional.add(value - 7)
                    } else {
                        normal.add(value)
                    }
                    lastBit = bit
                }
                x = x ushr 1
                bit++
            }
            normal.add(0)
        }

        process(data[0])
        for (i in 1 until data.size) process(data[i] xor data[i - 1])

        val out = ByteArray(
            4 + (normal.size * 3 + 7) / 8 + (exceptional.size * 5 + 7) / 8
        )
        out[0] = (ALGORITHM and 255).toByte()
        out[1] = ((data.size shr 16) and 255).toByte()
        out[2] = ((data.size shr 8) and 255).toByte()
        out[3] = (data.size and 255).toByte()
        var p = 4
        p = packBits(normal, 3, out, p)
        packBits(exceptional, 5, out, p)
        return out
    }

    /** Packe des valeurs de `width` bits, LSB d'abord (format chromaprint). */
    private fun packBits(values: List<Int>, width: Int, out: ByteArray, start: Int): Int {
        var bitPos = 0
        var p = start
        var cur = 0
        for (v in values) {
            cur = cur or ((v and ((1 shl width) - 1)) shl bitPos)
            bitPos += width
            while (bitPos >= 8) {
                out[p++] = (cur and 255).toByte()
                cur = cur ushr 8
                bitPos -= 8
            }
        }
        if (bitPos > 0) out[p++] = (cur and 255).toByte()
        return p
    }

    private fun base64Url(data: ByteArray): String {
        val sb = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        while (i + 3 <= data.size) {
            val b0 = data[i].toInt() and 255
            val b1 = data[i + 1].toInt() and 255
            val b2 = data[i + 2].toInt() and 255
            sb.append(B64[b0 shr 2])
            sb.append(B64[((b0 shl 4) or (b1 shr 4)) and 63])
            sb.append(B64[((b1 shl 2) or (b2 shr 6)) and 63])
            sb.append(B64[b2 and 63])
            i += 3
        }
        val rem = data.size - i
        if (rem == 1) {
            val b0 = data[i].toInt() and 255
            sb.append(B64[b0 shr 2])
            sb.append(B64[(b0 shl 4) and 63])
        } else if (rem == 2) {
            val b0 = data[i].toInt() and 255
            val b1 = data[i + 1].toInt() and 255
            sb.append(B64[b0 shr 2])
            sb.append(B64[((b0 shl 4) or (b1 shr 4)) and 63])
            sb.append(B64[(b1 shl 2) and 63])
        }
        return sb.toString()
    }
}
