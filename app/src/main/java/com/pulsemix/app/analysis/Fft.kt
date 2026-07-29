package com.pulsemix.app.analysis

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FFT radix-2 itérative (Cooley-Tukey), en place.
 * n doit être une puissance de 2.
 */
class Fft(private val n: Int) {

    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)

    init {
        require(n > 1 && (n and (n - 1)) == 0) { "n doit être une puissance de 2" }
        for (i in 0 until n / 2) {
            val a = -2.0 * Math.PI * i / n
            cosT[i] = cos(a).toFloat()
            sinT[i] = sin(a).toFloat()
        }
    }

    fun forward(re: FloatArray, im: FloatArray) {
        // Réordonnancement bit-reverse
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        // Papillons
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (idx in i until i + half) {
                    val c = cosT[k]
                    val s = sinT[k]
                    val tr = re[idx + half] * c - im[idx + half] * s
                    val ti = re[idx + half] * s + im[idx + half] * c
                    re[idx + half] = re[idx] - tr
                    im[idx + half] = im[idx] - ti
                    re[idx] += tr
                    im[idx] += ti
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Magnitudes des n/2 premiers bins (spectre réel utile). */
    fun magnitudes(re: FloatArray, im: FloatArray, out: FloatArray) {
        for (i in 0 until n / 2) {
            out[i] = sqrt(re[i] * re[i] + im[i] * im[i])
        }
    }
}
