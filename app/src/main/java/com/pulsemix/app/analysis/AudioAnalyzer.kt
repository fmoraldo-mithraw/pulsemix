package com.pulsemix.app.analysis

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Analyse un fichier audio et en extrait :
 *  - le BPM (enveloppe d'attaques par flux spectral + autocorrélation)
 *  - la tonalité (chromagramme + profils de Krumhansl-Kessler) + code Camelot
 *  - des statistiques d'énergie (RMS moyen, pic, centroïde spectral, densité d'attaques)
 *  - la « meilleure minute » (fenêtre de 60 s la plus énergique, recalée sur une vallée)
 *  - une ancre de beat au début de la meilleure minute (pour le calage DJ)
 */
class AudioAnalyzer {

    companion object {
        const val FFT_SIZE = 2048
        const val HOP = 1024
        const val RMS_BLOCK = 4096

        val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        // Camelot : index = pitch class du fondamental
        val CAMELOT_MAJOR = arrayOf("8B", "3B", "10B", "5B", "12B", "7B", "2B", "9B", "4B", "11B", "6B", "1B")
        val CAMELOT_MINOR = arrayOf("5A", "12A", "7A", "2A", "9A", "4A", "11A", "6A", "1A", "8A", "3A", "10A")

        val KRUMHANSL_MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val KRUMHANSL_MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    }

    data class Features(
        val bpm: Float,
        val bpmConfidence: Float,
        val keyName: String,
        val camelot: String,
        val energyMean: Float,
        val energyPeak: Float,
        val centroid: Float,
        val onsetRate: Float,
        val bestStartMs: Long,
        val segmentMs: Long,
        val firstBeatMs: Long,
        val durationMs: Long
    )

    suspend fun analyze(
        context: Context,
        uri: Uri,
        durationHintMs: Long,
        shouldContinue: () -> Boolean = { true }
    ): Features? =
        withContext(Dispatchers.Default) {
            val state = StreamState(durationHintMs)
            val ok = AudioDecoder().decode(context, uri) { pcm, frames, sr, ch ->
                state.feed(pcm, frames, sr, ch)
                shouldContinue()
            }
            // Interrompu (stop demandé) : données partielles, ne rien conclure
            if (!shouldContinue()) return@withContext null
            if (!ok || state.sampleRate == 0 || state.totalMono < state.sampleRate) return@withContext null

            val sr = state.sampleRate
            val durationMs = state.totalMono * 1000L / sr
            val hopSec = HOP.toFloat() / sr

            val (bpm, conf) = detectBpm(state.flux, hopSec)
            val (keyName, camelot) = detectKey(state.chroma)

            val rms = state.rms
            val blockMs = RMS_BLOCK * 1000.0 / sr
            val energyMean = if (rms.isEmpty()) 0f else (rms.sum() / rms.size)
            val energyPeak = percentile(rms, 0.95f)
            val centroid = if (state.centroidDen > 0) (state.centroidNum / state.centroidDen).toFloat() else 0f
            val fftWinSec = max(1f, (state.fftEnd - state.fftStart).toFloat() / sr)
            val onsetRate = countOnsets(state.flux) / fftWinSec

            val (bestStartMs, segmentMs) = bestSegment(rms, blockMs, durationMs, bpm)
            val firstBeatMs = probeFirstBeat(context, uri, bestStartMs, bpm)

            Features(
                bpm = bpm,
                bpmConfidence = conf,
                keyName = keyName,
                camelot = camelot,
                energyMean = energyMean,
                energyPeak = energyPeak,
                centroid = centroid,
                onsetRate = onsetRate,
                bestStartMs = bestStartMs,
                segmentMs = segmentMs,
                firstBeatMs = firstBeatMs,
                durationMs = durationMs
            )
        }

    // ------------------------------------------------------------------ flux

    /** État d'analyse en streaming : RMS sur tout le morceau, FFT sur une fenêtre centrale. */
    private class StreamState(durationHintMs: Long) {
        var sampleRate = 0
        var totalMono = 0L

        // RMS
        val rms = ArrayList<Float>()
        private var sumSq = 0.0
        private var blockFill = 0

        // FFT
        private val frame = FloatArray(FFT_SIZE)
        private var frameFill = FFT_SIZE - HOP
        private var fft: Fft? = null
        private val window = FloatArray(FFT_SIZE)
        private val re = FloatArray(FFT_SIZE)
        private val im = FloatArray(FFT_SIZE)
        private val mags = FloatArray(FFT_SIZE / 2)
        private val prevMags = FloatArray(FFT_SIZE / 2)
        private var firstFrame = true

        val flux = ArrayList<Float>()
        val chroma = DoubleArray(12)
        var centroidNum = 0.0
        var centroidDen = 0.0

        var fftStart = 0L
        var fftEnd = Long.MAX_VALUE
        private val durHint = if (durationHintMs > 0) durationHintMs else 240_000L

        fun feed(pcm: FloatArray, frames: Int, sr: Int, ch: Int) {
            if (sampleRate == 0) {
                sampleRate = sr
                // Fenêtre FFT : 75 s à partir de 30 % du morceau (bornée)
                val durSamples = durHint * sr / 1000
                val winLen = min(75L * sr, durSamples)
                fftStart = max(0L, min((durSamples * 3) / 10, durSamples - winLen))
                fftEnd = fftStart + winLen
                fft = Fft(FFT_SIZE)
                for (i in 0 until FFT_SIZE) {
                    window[i] = (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1))).toFloat()
                }
            }
            for (f in 0 until frames) {
                var m = 0f
                val base = f * ch
                for (c in 0 until min(ch, 2)) m += pcm[base + c]
                m /= min(ch, 2)
                processSample(m)
            }
        }

        private fun processSample(s: Float) {
            // RMS global
            sumSq += (s * s).toDouble()
            blockFill++
            if (blockFill == RMS_BLOCK) {
                rms.add(sqrt(sumSq / RMS_BLOCK).toFloat())
                sumSq = 0.0
                blockFill = 0
            }
            // Framing FFT
            frame[frameFill++] = s
            totalMono++
            if (frameFill == FFT_SIZE) {
                if (totalMono in fftStart..fftEnd) doFrame()
                System.arraycopy(frame, HOP, frame, 0, FFT_SIZE - HOP)
                frameFill = FFT_SIZE - HOP
            }
        }

        private fun doFrame() {
            val f = fft ?: return
            for (i in 0 until FFT_SIZE) {
                re[i] = frame[i] * window[i]
                im[i] = 0f
            }
            f.forward(re, im)
            f.magnitudes(re, im, mags)

            var fluxSum = 0f
            var cNum = 0.0
            var cDen = 0.0
            val binHz = sampleRate.toFloat() / FFT_SIZE
            for (i in 1 until FFT_SIZE / 2) {
                val m = ln(1f + 10f * mags[i])
                val p = if (firstFrame) m else prevMags[i]
                val d = m - p
                if (d > 0f) fluxSum += d
                prevMags[i] = m

                val freq = i * binHz
                if (freq in 55f..5000f) {
                    val pitch = (12.0 * log2(freq / 440.0) + 69.0).roundToInt()
                    val pc = ((pitch % 12) + 12) % 12
                    chroma[pc] += m.toDouble()
                }
                cNum += (freq * mags[i]).toDouble()
                cDen += mags[i].toDouble()
            }
            centroidNum += cNum
            centroidDen += cDen
            flux.add(fluxSum)
            firstFrame = false
        }
    }

    // ------------------------------------------------------------------- BPM

    private fun detectBpm(fluxList: List<Float>, hopSec: Float): Pair<Float, Float> {
        val n = fluxList.size
        if (n < 200) return 0f to 0f
        val f = FloatArray(n) { fluxList[it] }

        // Dé-tendance : soustraire la moyenne locale, garder la partie positive
        val g0 = FloatArray(n)
        val radius = 8
        for (i in 0 until n) {
            var s = 0f
            var c = 0
            for (j in max(0, i - radius)..min(n - 1, i + radius)) {
                s += f[j]; c++
            }
            g0[i] = max(0f, f[i] - s / c)
        }
        // Lissage léger [0.25, 0.5, 0.25] : élargit les pics d'attaque pour que
        // l'autocorrélation aux lags entiers ne sous-estime plus les périodes
        // non entières (BPM élevés = lags courts).
        val g = FloatArray(n)
        for (i in 0 until n) {
            val a = if (i > 0) g0[i - 1] else g0[i]
            val b = if (i < n - 1) g0[i + 1] else g0[i]
            g[i] = 0.25f * a + 0.5f * g0[i] + 0.25f * b
        }

        val acCache = HashMap<Int, Float>()
        fun acAt(lag: Int): Float {
            if (lag <= 0 || lag >= n - 8) return 0f
            return acCache.getOrPut(lag) {
                var s = 0f
                val m = n - lag
                for (i in 0 until m) s += g[i] * g[i + lag]
                s / m
            }
        }

        fun acInterp(lagF: Double): Float {
            val l0 = floor(lagF).toInt()
            val fr = (lagF - l0).toFloat()
            return acAt(l0) * (1f - fr) + acAt(l0 + 1) * fr
        }

        var bestBpm = 0.0
        var bestScore = 0f
        var scoreSum = 0f
        var scoreCount = 0
        val scores = HashMap<Int, Float>() // clé = bpm*2 arrondi

        var bpm = 60.0
        while (bpm <= 190.0) {
            val lag = 60.0 / (bpm * hopSec)
            // Peigne harmonique symétrique : uniquement des multiples du lag,
            // même nombre de termes pour tous les candidats. L'ancien terme
            // ac(lag/2) favorisait mécaniquement le demi-tempo (ses trois
            // termes tombaient tous sur des pics, ex. 120 BPM détecté à 60).
            var s = acInterp(lag)
            s += 0.45f * acInterp(lag * 2)
            val w = exp(-((bpm - 120.0) * (bpm - 120.0)) / (2.0 * 55.0 * 55.0)).toFloat()
            s *= (0.75f + 0.25f * w)
            scores[(bpm * 2).roundToInt()] = s
            scoreSum += s
            scoreCount++
            if (s > bestScore) {
                bestScore = s
                bestBpm = bpm
            }
            bpm += 0.5
        }
        if (bestScore <= 0f) return 0f to 0f

        fun scoreOf(b: Double): Float = scores[(b * 2).roundToInt()] ?: 0f

        var raised = false
        // Rattrapage 3:2 : un morceau à T détecté à 2T/3 (piège classique).
        // Le candidat 1,5x est retenu si son score est proche ET s'il possède
        // une vraie subdivision au demi-lag (tatum), contrairement au retenu.
        val cand = bestBpm * 1.5
        if (cand <= 190) {
            val lagBest = 60.0 / (bestBpm * hopSec)
            val lagCand = 60.0 / (cand * hopSec)
            if (scoreOf(cand) > 0.70f * bestScore &&
                acInterp(lagCand / 2) > 1.3f * acInterp(lagBest / 2)
            ) {
                bestBpm = (cand * 2).roundToInt() / 2.0
                bestScore = scoreOf(bestBpm)
                raised = true
            }
        }
        // Correction octave : préférer la plage 75-165. Seuil desserré à 0,75 :
        // deux fichiers réels (vrais tempos 117,5 et 129) tombaient à moins de
        // 1 % du seuil de 0,85 côté appli et restaient au demi-tempo. Les
        // morceaux vraiment lents ne risquent rien : leur candidat double n'a
        // aucun support dans l'autocorrélation.
        if (bestBpm < 75 && bestBpm * 2 <= 190 && scoreOf(bestBpm * 2) > 0.75f * bestScore) {
            bestBpm *= 2
            raised = true
        }
        // Ne pas redescendre un tempo qu'une règle vient de remonter
        if (!raised && bestBpm > 165 && bestBpm / 2 >= 60 &&
            scoreOf(bestBpm / 2) > 0.9f * bestScore
        ) {
            bestBpm /= 2
        }

        val mean = scoreSum / max(1, scoreCount)
        val conf = if (mean > 0) min(1f, (bestScore / mean - 1f) / 3f) else 0f
        return (Math.round(bestBpm * 10.0) / 10.0).toFloat() to max(0f, conf)
    }

    private fun countOnsets(fluxList: List<Float>): Int {
        val n = fluxList.size
        if (n < 3) return 0
        val mean = fluxList.sum() / n
        val th = mean * 1.5f
        var count = 0
        for (i in 1 until n - 1) {
            val v = fluxList[i]
            if (v > th && v > fluxList[i - 1] && v >= fluxList[i + 1]) count++
        }
        return count
    }

    // ------------------------------------------------------------------- clé

    private fun detectKey(chroma: DoubleArray): Pair<String, String> {
        val total = chroma.sum()
        if (total <= 0.0) return "--" to "--"
        var bestCorr = -2.0
        var bestPc = 0
        var bestMinor = false
        for (minor in booleanArrayOf(false, true)) {
            val prof = if (minor) KRUMHANSL_MINOR else KRUMHANSL_MAJOR
            for (tonic in 0 until 12) {
                val rotated = DoubleArray(12) { prof[((it - tonic) % 12 + 12) % 12] }
                val r = pearson(chroma, rotated)
                if (r > bestCorr) {
                    bestCorr = r
                    bestPc = tonic
                    bestMinor = minor
                }
            }
        }
        val name = NOTE_NAMES[bestPc] + if (bestMinor) "m" else ""
        val camelot = if (bestMinor) CAMELOT_MINOR[bestPc] else CAMELOT_MAJOR[bestPc]
        return name to camelot
    }

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val n = a.size
        val ma = a.sum() / n
        val mb = b.sum() / n
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            val xa = a[i] - ma
            val xb = b[i] - mb
            num += xa * xb
            da += xa * xa
            db += xb * xb
        }
        val den = sqrt(da * db)
        return if (den > 0) num / den else 0.0
    }

    // -------------------------------------------------------- meilleure minute

    private fun bestSegment(
        rms: List<Float>,
        blockMs: Double,
        durationMs: Long,
        bpm: Float
    ): Pair<Long, Long> {
        if (durationMs <= 75_000 || rms.size < 20) {
            // Morceau court : au plus 60 % de la durée, pour ne pas tout jouer
            val seg = min(60_000L, durationMs * 6 / 10)
                .coerceAtLeast(min(durationMs, 20_000L))
            return 0L to seg
        }
        val n = rms.size
        val w = max(1, (60_000.0 / blockMs).roundToInt())
        if (n <= w) return 0L to min(60_000L, durationMs)

        // Somme glissante : fenêtre de 60 s la plus énergique
        var sum = 0.0
        for (i in 0 until w) sum += rms[i]
        var bestSum = sum
        var bestIdx = 0
        for (i in 1..n - w) {
            sum += rms[i + w - 1] - rms[i - 1]
            if (sum > bestSum) {
                bestSum = sum
                bestIdx = i
            }
        }

        // RMS lissé (~1 s) pour juger les transitions sans le grain des blocs
        val k = max(1, (1000.0 / blockMs).roundToInt())
        val smooth = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f
            var c = 0
            for (j in max(0, i - k / 2)..min(n - 1, i + k / 2)) {
                s += rms[j]; c++
            }
            smooth[i] = s / c
        }

        // Départ = la montée d'énergie soutenue la plus franche (le « drop »)
        // autour de la fenêtre : 8 s après moins 8 s avant. Mieux qu'une
        // vallée : le mode DJ entre là où le morceau décolle, pas sur un creux.
        val h = max(1, (8_000.0 / blockMs).roundToInt())
        val lo = max(0, bestIdx - (20_000.0 / blockMs).roundToInt())
        val hi = min(n - 1, bestIdx + (10_000.0 / blockMs).roundToInt())
        var riseIdx = bestIdx
        var riseVal = -Float.MAX_VALUE
        for (i in lo..hi) {
            var before = 0f
            var cb = 0
            for (j in max(0, i - h) until i) {
                before += smooth[j]; cb++
            }
            var after = 0f
            var ca = 0
            for (j in i until min(n, i + h)) {
                after += smooth[j]; ca++
            }
            val rise = (if (ca > 0) after / ca else 0f) - (if (cb > 0) before / cb else 0f)
            if (rise > riseVal) {
                riseVal = rise
                riseIdx = i
            }
        }

        // Profil plat (pas de vrai drop) : garder la fenêtre énergique telle quelle
        val meanRms = rms.sum() / n
        val startIdx = if (riseVal > 0.05f * meanRms) riseIdx else bestIdx

        // ---- Durée adaptative : jouer la section, pas une minute arbitraire.
        // On suit l'énergie lissée après le départ et on coupe à la première
        // retombée durable (fin de section), bornée entre 40 et 90 s.
        val plateauEnd = min(n - 1, startIdx + (15_000.0 / blockMs).roundToInt())
        var plateau = 0f
        var pc = 0
        for (j in startIdx..plateauEnd) {
            plateau += smooth[j]; pc++
        }
        plateau /= max(1, pc)

        val sustain = max(1, (4_000.0 / blockMs).roundToInt())
        val i0 = startIdx + (40_000.0 / blockMs).roundToInt()
        val i1 = min(n - 1, startIdx + (90_000.0 / blockMs).roundToInt())
        var cutIdx = i1
        var i = i0
        while (i < i1) {
            var s = 0f
            var c = 0
            for (j in i until min(n, i + sustain)) {
                s += smooth[j]; c++
            }
            if (c > 0 && s / c < 0.75f * plateau) {
                cutIdx = i
                break
            }
            i++
        }

        val startMs = (startIdx * blockMs).toLong().coerceIn(0L, max(0L, durationMs - 30_000L))
        var segMs = ((cutIdx - startIdx) * blockMs).toLong().coerceIn(40_000L, 90_000L)
        // Morceau court : plafonner à 60 % de la durée totale
        if (durationMs < 120_000L) segMs = min(segMs, durationMs * 6 / 10)
        segMs = min(segMs, durationMs - startMs)

        // Arrondir aux phrases musicales (16 temps) : la transition DJ tombe
        // sur une fin de phrase, pas au milieu d'une mesure.
        if (bpm > 0f) {
            val phraseMs = 16.0 * 60_000.0 / bpm
            val phrases = floor(segMs / phraseMs).toLong()
            if (phrases >= 2) segMs = (phrases * phraseMs).toLong()
        }
        return startMs to segMs
    }

    // ------------------------------------------------------- ancre de premier beat

    /**
     * Redécode ~8 s à partir de la meilleure minute pour trouver le premier beat fort.
     * Sert au calage sample-accurate du moteur DJ.
     */
    private fun probeFirstBeat(context: Context, uri: Uri, bestStartMs: Long, bpm: Float): Long {
        if (bpm <= 0f) return bestStartMs
        // Tableau primitif (pas d'ArrayList<Float> : ~350 000 Float boxés par
        // morceau mettaient une vraie pression sur le GC pendant la lecture)
        var mono = FloatArray(0)
        var size = 0
        var sr = 0
        AudioDecoder().decode(context, uri, bestStartMs * 1000, 8_000_000) { pcm, frames, s, ch ->
            if (sr == 0) {
                sr = s
                mono = FloatArray(9 * s)
            }
            for (f in 0 until frames) {
                var m = 0f
                val base = f * ch
                for (c in 0 until min(ch, 2)) m += pcm[base + c]
                if (size < mono.size) mono[size++] = m / min(ch, 2)
            }
            size < 8 * s
        }
        if (sr == 0 || size < FFT_SIZE * 4) return bestStartMs

        val fft = Fft(FFT_SIZE)
        val window = FloatArray(FFT_SIZE) {
            (0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / (FFT_SIZE - 1))).toFloat()
        }
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val mags = FloatArray(FFT_SIZE / 2)
        val prev = FloatArray(FFT_SIZE / 2)
        val flux = ArrayList<Float>()
        var pos = 0
        var first = true
        while (pos + FFT_SIZE <= size) {
            for (i in 0 until FFT_SIZE) {
                re[i] = mono[pos + i] * window[i]
                im[i] = 0f
            }
            fft.forward(re, im)
            fft.magnitudes(re, im, mags)
            var s = 0f
            for (i in 1 until FFT_SIZE / 2) {
                val m = ln(1f + 10f * mags[i])
                if (!first) {
                    val d = m - prev[i]
                    if (d > 0) s += d
                }
                prev[i] = m
            }
            if (!first) flux.add(s)
            first = false
            pos += HOP
        }
        if (flux.isEmpty()) return bestStartMs

        val hopMs = HOP * 1000.0 / sr
        val periodMs = 60_000.0 / bpm
        val searchMs = min(2.5 * periodMs, 4000.0)
        val maxIdx = min(flux.size - 1, (searchMs / hopMs).toInt())
        var best = 0
        var bestV = -1f
        for (i in 0..maxIdx) {
            if (flux[i] > bestV) {
                bestV = flux[i]
                best = i
            }
        }
        return bestStartMs + (best * hopMs).toLong()
    }

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
