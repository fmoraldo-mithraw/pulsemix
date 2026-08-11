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

        /**
         * Version du jeu de caractéristiques extraites. Un morceau analysé
         * avec une version antérieure est réanalysé au prochain scan : les
         * mesures qui manquent ne peuvent pas être devinées après coup.
         *
         * 2 : montée, amplitude de respiration, part de son tenu et part de
         * bas-médium — de quoi reconnaître l'orchestral et l'épique.
         */
        const val FEATURES_VERSION = 2

        val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        // Camelot : index = pitch class du fondamental
        val CAMELOT_MAJOR = arrayOf("8B", "3B", "10B", "5B", "12B", "7B", "2B", "9B", "4B", "11B", "6B", "1B")
        val CAMELOT_MINOR = arrayOf("5A", "12A", "7A", "2A", "9A", "4A", "11A", "6A", "1A", "8A", "3A", "10A")

        val KRUMHANSL_MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val KRUMHANSL_MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

        /** Niveau cible de la normalisation de volume (RMS linéaire). Le
         *  même 0,18 que les formules historiques de PlayerCore.normGain et
         *  DjMixer.Deck.gain : le gain mesuré vise le même niveau perçu. */
        const val LOUDNESS_TARGET = 0.18f

        /**
         * Gain de normalisation MESURÉ (ReplayGain maison), en dB : ce qu'il
         * faut appliquer pour ramener [loudness] (RMS global linéaire 0..1)
         * au niveau cible. Borné à ±8 dB — au-delà, on remonte du bruit de
         * fond ou on écrase un master, plus de la correction. 0 = « pas de
         * mesure » (morceau trop silencieux pour être fiable, ou analysé
         * avant l'arrivée du champ) : les lecteurs retombent alors sur
         * l'ancienne formule à base d'energyMean.
         */
        fun gainDbFor(loudness: Float, target: Float = LOUDNESS_TARGET): Float {
            if (loudness <= 0.01f) return 0f
            return (20f * kotlin.math.log10(target / loudness)).coerceIn(-8f, 8f)
        }

        /** dB → facteur linéaire (10^(dB/20)). Les bornes restent aux appelants. */
        fun gainFactor(gainDb: Float): Float =
            Math.pow(10.0, gainDb / 20.0).toFloat()
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
        val musicStartMs: Long,
        val durationMs: Long,
        /** Énergie du dernier tiers rapportée au premier : la montée. */
        val energySlope: Float,
        /** Écart entre crête et fond sonore : l'amplitude de respiration. */
        val dynamicSpread: Float,
        /** Part de son tenu (0..1) : nappes, chœurs et cuivres contre percussions. */
        val sustainRatio: Float,
        /** Part de l'énergie entre 180 et 1200 Hz : voix massées et cuivres. */
        val lowMidRatio: Float,
        /**
         * RMS global du morceau, linéaire 0..1 : sqrt(moyenne des carrés)
         * sur TOUT le fichier. Proche d'[energyMean] (moyenne des RMS de
         * bloc) mais pas identique — c'est la vraie base d'un gain de
         * normalisation mesuré (voir [gainDbFor]).
         */
        val loudness: Float,
        /**
         * Structure du morceau (intro/montée/temps fort/calme/outro),
         * encodée compacte par [StructureDetector.encode]. Vide si rien de
         * fiable n'a pu être segmenté. Calculée ici — sur les tableaux
         * déjà en mémoire, pas de seconde passe — et non versionnée : les
         * morceaux analysés avant l'arrivée du champ ne sont PAS réanalysés
         * (l'UI et le DJ gardent alors leur comportement historique).
         */
        val structure: String
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

            val (bpm, conf) = detectBpm(state.flux, state.fluxLow, hopSec)
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
            val musicStartMs = detectMusicStart(rms, blockMs)

            // Structure du morceau : le flux spectral est recalé sur la
            // grille des blocs RMS ; hors de la fenêtre FFT (l'analyse n'en
            // couvre qu'une partie centrale), -1 = pas de mesure.
            val rmsArr = FloatArray(rms.size) { rms[it] }
            val fluxOnRms = FloatArray(rms.size) { i ->
                val midSample = i.toLong() * RMS_BLOCK + RMS_BLOCK / 2
                val j = ((midSample - state.fftStart) / HOP).toInt()
                if (midSample >= state.fftStart && j < state.flux.size)
                    state.flux[j]
                else -1f
            }
            val structure = StructureDetector.encode(
                StructureDetector.detect(
                    rmsArr, fluxOnRms, blockMs.toFloat(), bpm, durationMs, firstBeatMs
                )
            )

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
                musicStartMs = musicStartMs,
                durationMs = durationMs,
                energySlope = energySlope(rms),
                dynamicSpread = dynamicSpread(rms),
                sustainRatio = if (state.magTotal > 0.0)
                    (1.0 - state.fluxTotal / state.magTotal)
                        .coerceIn(0.0, 1.0).toFloat()
                else 0f,
                lowMidRatio = if (state.centroidDen > 0.0)
                    (state.lowMidNum / state.centroidDen)
                        .coerceIn(0.0, 1.0).toFloat()
                else 0f,
                // RMS global : racine de la moyenne des carrés des RMS de
                // bloc (blocs de taille égale, le résidu final est ignoré)
                loudness = if (rms.isEmpty()) 0f
                else sqrt(rms.sumOf { (it * it).toDouble() } / rms.size).toFloat(),
                structure = structure
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
        // Flux « basses » (<= 200 Hz) : l'enveloppe des kicks, qui tranche
        // les erreurs d'octave (le kick marque le temps, pas les hi-hats)
        val fluxLow = ArrayList<Float>()
        val chroma = DoubleArray(12)
        var centroidNum = 0.0
        var centroidDen = 0.0

        // Part de l'énergie dans le bas-médium (180-1200 Hz) : c'est là que
        // vivent les voix massées et les cuivres — chœurs et cors, la
        // signature de l'orchestral. Le rapport à l'énergie totale distingue
        // ces timbres d'une production électronique, qui se répartit plutôt
        // entre le grave profond et l'aigu percussif.
        var lowMidNum = 0.0

        // Attaques rapportées à la matière sonore : une nappe de cordes ou
        // un chœur tenu montent peu et longtemps (flux faible pour beaucoup
        // d'énergie), une batterie fait l'inverse.
        var fluxTotal = 0.0
        var magTotal = 0.0

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
            var fluxLowSum = 0f
            var cNum = 0.0
            var cDen = 0.0
            var magSum = 0f
            var lowMidSum = 0.0
            val binHz = sampleRate.toFloat() / FFT_SIZE
            for (i in 1 until FFT_SIZE / 2) {
                val m = ln(1f + 10f * mags[i])
                val p = if (firstFrame) m else prevMags[i]
                val d = m - p
                if (d > 0f) {
                    fluxSum += d
                    if (i * binHz <= 200f) fluxLowSum += d
                }
                prevMags[i] = m
                magSum += m

                val freq = i * binHz
                if (freq in 180f..1200f) lowMidSum += mags[i].toDouble()
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
            lowMidNum += lowMidSum
            // La toute première trame n'a rien à quoi se comparer : son flux
            // vaut la trame entière et gonflerait la part d'attaques.
            if (!firstFrame) {
                fluxTotal += fluxSum.toDouble()
                magTotal += magSum.toDouble()
            }
            flux.add(fluxSum)
            fluxLow.add(fluxLowSum)
            firstFrame = false
        }
    }

    // ------------------------------------------------------------------- BPM

    /** Dé-tendance (moyenne locale) + lissage [0.25, 0.5, 0.25]. */
    private fun preprocessFlux(fluxList: List<Float>): FloatArray {
        val n = fluxList.size
        val f = FloatArray(n) { fluxList[it] }
        val g0 = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f
            var c = 0
            for (j in max(0, i - 8)..min(n - 1, i + 8)) {
                s += f[j]; c++
            }
            g0[i] = max(0f, f[i] - s / c)
        }
        val g = FloatArray(n)
        for (i in 0 until n) {
            val a = if (i > 0) g0[i - 1] else g0[i]
            val b = if (i < n - 1) g0[i + 1] else g0[i]
            g[i] = 0.25f * a + 0.5f * g0[i] + 0.25f * b
        }
        return g
    }

    /**
     * Soutien d'une grille de beats de période donnée (en hops) dans une
     * enveloppe d'attaques : [support, hits].
     *  - support : moyenne de g aux positions de la grille (meilleure phase),
     *    normalisée par la moyenne globale — « à quel point les temps de
     *    cette grille tombent sur des événements » ;
     *  - hits : fraction des temps dont le pic local vaut au moins 45 % du
     *    plus gros événement à ± une période — robuste aux passages doux.
     */
    private fun gridMetrics(arr: FloatArray, period: Double): FloatArray {
        val n = arr.size
        if (period < 4 || n < period * 4) return floatArrayOf(0f, 0f)
        var gm = 0f
        for (v in arr) gm += v
        gm = gm / n + 1e-9f
        val tol = max(2, Math.round(period * 0.14).toInt())
        val half = period.toInt() + 1
        // Plancher de référence : moyenne des K plus hauts pics (K = nombre
        // de temps attendus) — les silences ne comptent pas comme des hits
        val peaks = ArrayList<Float>()
        for (i in 1 until n - 1) {
            if (arr[i] > arr[i - 1] && arr[i] >= arr[i + 1] && arr[i] > 0f) {
                peaks.add(arr[i])
            }
        }
        var floorRef = 0.2f * gm
        if (peaks.isNotEmpty()) {
            peaks.sortDescending()
            val k = max(4, (n / period).toInt()).coerceAtMost(peaks.size)
            var s = 0f
            for (i in 0 until k) s += peaks[i]
            floorRef = 0.2f * (s / k)
        }
        var bestSup = 0f
        var bestHit = 0f
        val phaseStep = max(1.0, period / 24)
        var phase = 0.0
        while (phase < period) {
            var sup = 0f
            var cnt = 0
            var hits = 0
            var pos = phase
            while (pos < n - 1) {
                val ip = pos.toInt()
                val fr = (pos - ip).toFloat()
                sup += arr[ip] * (1 - fr) + arr[min(ip + 1, n - 1)] * fr
                var wmax = 0f
                for (q in max(0, ip - tol)..min(n - 1, ip + tol)) {
                    if (arr[q] > wmax) wmax = arr[q]
                }
                var ref = floorRef
                for (q in max(0, ip - half)..min(n - 1, ip + half)) {
                    if (arr[q] > ref) ref = arr[q]
                }
                if (wmax >= 0.45f * ref) hits++
                cnt++
                pos += period
            }
            if (cnt > 0) {
                val supN = (sup / cnt) / gm
                if (supN > bestSup) {
                    bestSup = supN
                    bestHit = hits.toFloat() / cnt
                }
            }
            phase += phaseStep
        }
        return floatArrayOf(bestSup, bestHit)
    }

    /**
     * Détection de BPM v2 : peigne d'autocorrélation symétrique (comme
     * avant) pour le classement de base, puis corrections d'octave et de
     * 3:2 fondées sur le soutien MESURÉ des grilles de beats candidates,
     * en bande complète ET en bande basses (l'enveloppe des kicks tranche
     * les erreurs d'octave). Validé sur banc synthétique + fichiers réels :
     * 20/21 contre 18/21 pour l'ancienne heuristique.
     */
    private fun detectBpm(
        fluxList: List<Float>,
        fluxLowList: List<Float>,
        hopSec: Float
    ): Pair<Float, Float> {
        val n = fluxList.size
        if (n < 200) return 0f to 0f
        val g = preprocessFlux(fluxList)
        val gl = preprocessFlux(fluxLowList)

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
            // Peigne harmonique symétrique + prior doux autour de 120
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
        fun combRaw(b: Double): Float =
            acInterp(60.0 / (b * hopSec)) + 0.45f * acInterp(120.0 / (b * hopSec))

        // Affinage local du peigne (le candidat x2 d'un tempo approximatif
        // doit être recalé sur son vrai pic avant le test de grille)
        fun refine(b: Double): Double {
            var bb = b
            var bs = -1f
            var x = max(60.0, b - 2.5)
            while (x <= min(190.0, b + 2.5) + 1e-9) {
                val s = combRaw(x)
                if (s > bs) {
                    bs = s; bb = x
                }
                x += 0.2
            }
            return bb
        }

        val mCache = HashMap<Int, FloatArray>() // [supF, hitF, supL, hitL]
        fun metricsOf(b: Double): FloatArray = mCache.getOrPut((b * 2).roundToInt()) {
            val p = 60.0 / (b * hopSec)
            val full = gridMetrics(g, p)
            val low = gridMetrics(gl, p)
            floatArrayOf(full[0], full[1], low[0], low[1])
        }

        // Densité d'attaques : garde-fou anti-doublage des morceaux lents
        val ops = countOnsets(fluxList) / max(1f, n * hopSec)
        fun densityOk(b: Double) = ops >= 1.6f * (b / 60.0).toFloat()

        fun preferFaster(slow: Double, fast: Double, x15: Boolean): Boolean {
            if (fast > 190.0) return false
            val s = metricsOf(slow)
            val f = metricsOf(fast)
            val lowInf = max(s[3], f[3]) >= 0.45f
            val lowOk = f[3] >= s[3] - 0.15f || f[2] >= 0.60f * (s[2] + 1e-9f)
            val populated = f[1] >= 0.85f * max(s[1], 1e-9f) || f[1] >= 0.9f ||
                (lowInf && f[3] >= 0.85f * max(s[3], 1e-9f))
            if (!populated) return false
            val ratio = f[0] / (s[0] + 1e-9f)
            // Pas de grille de kicks lisible : prudence, ratio fort exigé
            if (!lowInf) return ratio >= 0.86f
            if (ratio >= 0.86f && lowOk) return true
            if (x15) {
                return ratio >= 0.66f && lowOk &&
                    f[1] >= 0.92f * max(s[1], 1e-9f) &&
                    scoreOf(Math.round(fast * 2) / 2.0) >= 0.55f * bestScore
            }
            return ratio >= 0.62f && lowOk
        }

        var best = refine(bestBpm)
        // Octave up d'abord (grilles mesurées + densité)
        var promoted = false
        if (best < 100 && best * 2 <= 190) {
            val up = refine(best * 2)
            if (kotlin.math.abs(up - best * 2) < 0.08 * best * 2 &&
                densityOk(up) && preferFaster(best, up, false)
            ) {
                best = up
                promoted = true
            }
        }
        // Sinon 3:2 (grilles mesurées + densité + vrai pic de peigne)
        if (!promoted && best < 115 && best * 1.5 <= 190) {
            val up = refine(best * 1.5)
            if (kotlin.math.abs(up - best * 1.5) < 0.08 * best * 1.5 &&
                densityOk(up) && combRaw(up) >= 0.68f * combRaw(best) &&
                preferFaster(best, up, true)
            ) {
                best = up
            }
        }
        // Garde-fou haut : redescendre seulement si la demi-grille est
        // nettement mieux soutenue
        if (best > 165 && best / 2 >= 60) {
            val s = metricsOf(best)
            val h = metricsOf(best / 2)
            if (h[0] >= 1.25f * s[0] &&
                (max(s[3], h[3]) < 0.45f || h[3] > s[3] + 0.2f)
            ) {
                best /= 2
            }
        }

        // Affinage fin (0,1 BPM) autour du niveau retenu
        var fine = best
        var fs = -1f
        var x = max(60.0, best - 1.2)
        while (x <= min(190.0, best + 1.2) + 1e-9) {
            val s = combRaw(x)
            if (s > fs) {
                fs = s; fine = x
            }
            x += 0.1
        }

        val mean = scoreSum / max(1, scoreCount)
        val conf = if (mean > 0) min(1f, (bestScore / mean - 1f) / 3f) else 0f
        return (Math.round(fine * 10.0) / 10.0).toFloat() to max(0f, conf)
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

    // --------------------------------------------------- début de la musique

    /**
     * Détecte le début réel de la musique quand le morceau est préfacé d'un
     * sketch ou d'une intro parlée. Volontairement CONSERVATEUR : une intro
     * musicale douce (piano, nappe...) ne doit jamais être coupée.
     *  - la musique « commence » à la première fenêtre de 8 s dont l'énergie
     *    lissée atteint 32 % du niveau musique (75e percentile) — un vrai
     *    passage musical calme dépasse ce seuil, un sketch parlé non ;
     *  - on ne coupe que si le saut dépasse 10 s (un sketch est long) ;
     *  - ET si l'avant-début est nettement plus calme que la musique
     *    (< 55 % du niveau) — sinon c'est une intro musicale, on garde tout ;
     *  - saut plafonné à 90 s.
     */
    private fun detectMusicStart(rms: List<Float>, blockMs: Double): Long {
        val n = rms.size
        if (n < 40) return 0L

        // RMS lissé (~1 s)
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

        val sorted = smooth.sorted()
        val musicLevel = sorted[(0.75 * (n - 1)).toInt()]
        if (musicLevel <= 0f) return 0L

        val win = max(1, (8_000.0 / blockMs).roundToInt())
        val cap = min(n - win, (90_000.0 / blockMs).toInt())
        var i = 0
        while (i < cap) {
            var s = 0f
            for (j in i until i + win) s += smooth[j]
            if (s / win >= 0.32f * musicLevel) break
            i++
        }
        // Saut court : pas un sketch, ne rien couper
        if (i * blockMs < 10_000.0) return 0L
        // L'avant-début doit être clairement plus calme que la musique :
        // sinon c'est une intro musicale progressive, on la garde
        var pre = 0f
        for (j in 0 until i) pre += smooth[j]
        if (pre / i >= 0.55f * musicLevel) return 0L
        // Petit pré-roll d'une demi-seconde avant le départ détecté
        return (i * blockMs - 500).toLong().coerceAtLeast(0L)
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

        // Affinage à l'échantillon près : la résolution du flux est d'un hop
        // (~23 ms) ; on cherche la montée d'énergie la plus franche autour du
        // hop retenu par fenêtres de 64 échantillons (~1,5 ms). Un calage plus
        // précis = des transitions DJ plus nettes.
        val center = (best + 1) * HOP
        val lo = max(0, center - HOP)
        val hi = min(size, center + 2 * HOP)
        var bestPos = center
        var bestRise = -Float.MAX_VALUE
        var prevE = 0f
        var p = lo
        while (p + 64 <= hi) {
            var e = 0f
            for (k in p until p + 64) {
                val v = mono[k]
                e += v * v
            }
            val rise = e - prevE
            if (rise > bestRise) {
                bestRise = rise
                bestPos = p
            }
            prevE = e
            p += 64
        }
        return bestStartMs + bestPos.toLong() * 1000L / sr
    }

    private fun percentile(values: List<Float>, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    /**
     * La montée : énergie moyenne du dernier tiers rapportée à celle du
     * premier. Un morceau qui finit deux fois plus fort qu'il n'a commencé
     * rend 2. C'est la trace d'un crescendo, ce qui fait qu'un morceau
     * « raconte » quelque chose plutôt que de tourner en boucle.
     *
     * Le tiers de tête est pris après le silence d'introduction : sinon
     * n'importe quel morceau précédé d'un blanc paraîtrait monter à
     * l'infini.
     */
    internal fun energySlope(rms: List<Float>): Float {
        if (rms.size < 9) return 1f
        val floor = percentile(rms, 0.05f) + 1e-6f
        val start = rms.indexOfFirst { it > floor * 3f }.coerceAtLeast(0)
        val body = rms.subList(start, rms.size)
        if (body.size < 9) return 1f
        val third = body.size / 3
        val head = body.take(third).average().toFloat()
        val tail = body.takeLast(third).average().toFloat()
        if (head <= 1e-6f) return 1f
        return (tail / head).coerceIn(0f, 8f)
    }

    /**
     * L'amplitude de respiration : crête rapportée au fond sonore. Une
     * production compressée reste autour de 1,5 ; un orchestre qui passe du
     * murmure au tutti dépasse largement 5.
     */
    internal fun dynamicSpread(rms: List<Float>): Float {
        if (rms.size < 4) return 1f
        val low = percentile(rms, 0.10f)
        val high = percentile(rms, 0.95f)
        if (low <= 1e-6f) return if (high > 1e-6f) 8f else 1f
        return (high / low).coerceIn(1f, 8f)
    }
}
