package com.pulsemix.app.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.net.Uri
import com.pulsemix.app.analysis.AudioDecoder
import com.pulsemix.app.data.Track
import com.pulsemix.app.mix.MixEngine
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Moteur du mode DJ.
 *
 * Chaque morceau n'est joué que sur sa « meilleure minute ». Deux decks
 * décodent en parallèle ; le morceau entrant est resamplé pour caler son BPM
 * sur le morceau en cours (façon pitch fader, ±8 %), son premier beat est
 * aligné à l'échantillon près sur la grille de beats du deck actif, puis un
 * crossfade equal-power fait la transition. Le bouton next saute à la phase
 * suivante du mix (transition immédiate, fondu court).
 */
class DjMixer(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onTrackChanged(track: Track, phaseIndex: Int)
        fun onProgress(progress: Float)
        fun onStopped()
        /** Session audio du moteur DJ (pour y attacher l'égaliseur). */
        fun onSessionReady(sessionId: Int) {}
    }

    private data class Segment(val track: Track, val phaseIndex: Int)

    companion object {
        const val OUT_SR = 44100
        const val BLOCK_FRAMES = 2048
        const val FADE_NORMAL_S = 8.0
        const val FADE_JUMP_S = 4.0
        // Jonctions adaptatives : long blend quand tempos calés et tonalités
        // compatibles, coupe franche quand le calage est impossible.
        const val FADE_LOCKED_HARMONIC_S = 14.0
        const val FADE_CUT_S = 3.5
        const val TAIL_MS = 16_000L
        const val HALF_PI = (Math.PI / 2).toFloat()
        // One-pole ~120 Hz à 44,1 kHz pour l'extraction des basses
        const val BASS_ALPHA = 0.017f
        // Bass swap : atténuation des basses du deck « en retrait » pendant
        // le crossfade (une seule ligne de basse à la fois)
        const val BASS_SWAP_CUT = 0.85f
    }

    /** Détecteur d'attaques (kicks) sur l'enveloppe de basses d'un deck. */
    private class OnsetTracker {
        private var mean = 0f
        var lastOnsetFrame = -1L
            private set
        private var refractoryUntil = 0L

        fun feed(energy: Float, frame: Long, refractoryFrames: Long) {
            if (mean <= 0f) mean = energy
            if (energy > 1.7f * mean && energy > 1e-4f && frame >= refractoryUntil) {
                lastOnsetFrame = frame
                refractoryUntil = frame + refractoryFrames
            }
            mean += 0.05f * (energy - mean)
        }
    }

    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var pendingJump = -1
    @Volatile private var currentPhaseIndex = 0
    @Volatile private var startSegIndex = 0

    private var segments: List<Segment> = emptyList()
    private var plan: MixEngine.MixPlan? = null
    private var mixThread: Thread? = null
    private var phaseLengthFactor = FloatArray(0)

    // ------------------------------------------------------------------ API

    /** @param startPhase phase de départ (reprise après fermeture/plantage). */
    fun start(plan: MixEngine.MixPlan, startPhase: Int = 0) {
        stop()
        this.plan = plan
        segments = plan.phases.flatMapIndexed { pi, phase ->
            phase.tracks.filter { it.analyzed && it.bpm > 0f }.map { Segment(it, pi) }
        }
        if (segments.isEmpty()) {
            ui.post { listener.onStopped() }
            return
        }
        // Modulation par phase : segments un peu plus longs dans les phases
        // énergiques (peak), un peu plus courts dans les phases calmes.
        val phaseEnergy = plan.phases.map { ph ->
            val analyzed = ph.tracks.filter { it.analyzed }
            if (analyzed.isEmpty()) 0f
            else analyzed.map { it.energyPeak }.sum() / analyzed.size
        }
        val eMin = phaseEnergy.minOrNull() ?: 0f
        val eMax = phaseEnergy.maxOrNull() ?: 0f
        phaseLengthFactor = FloatArray(plan.phases.size) { i ->
            val t = if (eMax > eMin) (phaseEnergy[i] - eMin) / (eMax - eMin) else 0.5f
            0.85f + 0.30f * t
        }
        running = true
        paused = false
        pendingJump = -1
        startSegIndex = segments.indexOfFirst { it.phaseIndex >= startPhase }
            .let { if (it < 0) 0 else it }
        currentPhaseIndex = segments[startSegIndex].phaseIndex
        mixThread = thread(name = "DjMixer", priority = Thread.MAX_PRIORITY) { runMix() }
    }

    fun stop() {
        running = false
        paused = false
        mixThread?.join(2000)
        mixThread = null
    }

    fun setPaused(p: Boolean) {
        paused = p
    }

    val isRunning: Boolean get() = running

    /** Next en mode DJ = saut à la phase suivante. */
    fun nextPhase() {
        val target = segments.indexOfFirst { it.phaseIndex > currentPhaseIndex }
        pendingJump = if (target >= 0) target else -2 // -2 = simple morceau suivant
    }

    /** Previous = début de la phase précédente (ou de la phase courante). */
    fun prevPhase() {
        val prev = max(0, currentPhaseIndex - 1)
        val target = segments.indexOfFirst { it.phaseIndex == prev }
        if (target >= 0) pendingJump = target
    }

    // ------------------------------------------------------------------ deck

    private inner class Deck(
        val segIndex: Int,
        val segment: Segment,
        val rate: Float,
        lengthFactor: Float = 1f
    ) {
        val track: Track = segment.track

        // Normalisation du volume : atténue/renforce vers un niveau commun
        val gain: Float =
            if (PlayerCore.normalizeVolume.value && track.energyMean > 0.01f)
                (0.18f / track.energyMean).coerceIn(0.6f, 1.6f)
            else 1f

        @Volatile var closed = false
        @Volatile var decoderDone = false
        @Volatile var srcSr = 0
        val queue = ArrayBlockingQueue<FloatArray>(48)
        private val openLatch = CountDownLatch(1)

        val startMs: Long
        val logicalEndMs: Long
        private val decodeEndMs: Long

        var startedAtFrame = 0L
        var framesOut = 0L
            private set
        var finished = false
            private set

        // Filtres de basses du deck (bass swap + détection de kick pour le
        // verrouillage pendant le crossfade)
        var lpL = 0f
        var lpR = 0f
        val onsets = OnsetTracker()

        /** Micro-correction de synchro pendant le fade (bornée à ±0,4 %). */
        fun syncNudge(delta: Float) {
            curRate = (curRate + delta).coerceIn(rate * 0.996f, rate * 1.004f)
        }

        // Retour progressif au tempo naturel après un calage (pitch ridé par
        // un DJ) : curRate glisse vers 1.0 une fois le crossfade terminé.
        @Volatile var curRate: Float = rate
            private set
        private var rampStarted = false
        private var beatPhase = 0.0 // en battements, tenue à jour dès la rampe

        /** Phase de battement à un instant donné (exacte avant toute rampe). */
        fun beatPhaseAt(frame: Long): Double =
            if (!rampStarted) (frame - startedAtFrame) / beatPeriodFrames
            else beatPhase

        /** Avance la phase d'un bloc (à appeler quand la rampe est active). */
        fun advancePhase(frames: Int) {
            if (rampStarted) beatPhase += frames / beatPeriodFrames
        }

        /** Rapproche le tempo du naturel d'un petit pas (hors crossfade). */
        fun nudgeTowardNatural(step: Float, frameNow: Long) {
            if (curRate == 1f) return
            if (!rampStarted) {
                beatPhase = (frameNow - startedAtFrame) / beatPeriodFrames
                rampStarted = true
            }
            curRate = if (curRate > 1f) max(1f, curRate - step)
            else min(1f, curRate + step)
        }

        private var ratio = 1.0
        private var srcPos = 1.0
        private var prevL = 0f; private var prevR = 0f
        private var nextL = 0f; private var nextR = 0f
        private var curChunk: FloatArray? = null
        private var curFrames = 0
        private var curPos = 0

        init {
            val best = track.bestStartMs.coerceIn(0L, max(0L, track.durationMs - 15_000L))
            val beat = track.firstBeatMs
            startMs = if (beat in best..(best + track.segmentMs)) beat else best
            // Modulation par phase, puis ré-arrondi aux phrases de 16 temps
            // pour que la fin reste sur une frontière musicale.
            var segMs = (track.segmentMs * lengthFactor).toLong()
            if (track.bpm > 0f) {
                val phraseMs = 16.0 * 60_000.0 / track.bpm
                val phrases = floor(segMs / phraseMs).toLong()
                if (phrases >= 2) segMs = (phrases * phraseMs).toLong()
            }
            logicalEndMs = min(best + segMs, track.durationMs)
            decodeEndMs = min(logicalEndMs + TAIL_MS, track.durationMs)

            thread(name = "DjDeck-${track.title.take(12)}") {
                AudioDecoder().decode(
                    context, Uri.parse(track.uri),
                    startUs = startMs * 1000,
                    maxDurationUs = max(1L, (decodeEndMs - startMs)) * 1000
                ) { pcm, frames, sr, ch ->
                    if (srcSr == 0) srcSr = sr
                    val stereo = FloatArray(frames * 2)
                    when (ch) {
                        1 -> for (f in 0 until frames) {
                            val v = pcm[f]; stereo[2 * f] = v; stereo[2 * f + 1] = v
                        }
                        2 -> System.arraycopy(pcm, 0, stereo, 0, frames * 2)
                        else -> for (f in 0 until frames) {
                            stereo[2 * f] = pcm[f * ch]
                            stereo[2 * f + 1] = pcm[f * ch + 1]
                        }
                    }
                    var offered = false
                    while (!closed && running && !offered) {
                        offered = queue.offer(stereo, 100, TimeUnit.MILLISECONDS)
                    }
                    if (offered) openLatch.countDown()
                    !closed && running
                }
                decoderDone = true
                openLatch.countDown()
            }
        }

        /** Attend le premier chunk décodé. @return true si le deck est exploitable. */
        fun open(): Boolean {
            openLatch.await(4, TimeUnit.SECONDS)
            if (srcSr == 0) return false
            ratio = srcSr.toDouble() * curRate / OUT_SR
            pullSrcFrame()
            pullSrcFrame()
            srcPos = 0.0
            return true
        }

        val totalOutFrames: Long
            get() = ((logicalEndMs - startMs) / 1000.0 * OUT_SR / rate).toLong()

        val tailOutFrames: Long
            get() = ((decodeEndMs - logicalEndMs) / 1000.0 * OUT_SR / rate).toLong()

        val remainingOut: Long get() = max(0L, totalOutFrames - framesOut)

        /** Période de beat en frames de sortie, au rate courant. */
        val beatPeriodFrames: Double
            get() = OUT_SR * 60.0 / (track.bpm.toDouble() * curRate)

        private fun pullSrcFrame(): Boolean {
            while (true) {
                val c = curChunk
                if (c != null && curPos < curFrames) {
                    prevL = nextL; prevR = nextR
                    nextL = c[curPos * 2]; nextR = c[curPos * 2 + 1]
                    curPos++
                    return true
                }
                curChunk = null
                val polled = queue.poll(250, TimeUnit.MILLISECONDS)
                if (polled == null) {
                    if (decoderDone && queue.isEmpty()) return false
                    // Famine du décodeur : tenir la dernière valeur un instant
                    prevL = nextL; prevR = nextR
                    return true
                }
                curChunk = polled
                curFrames = polled.size / 2
                curPos = 0
            }
        }

        /** Écrit `frames` frames stéréo dans dst à partir de dstFrameOffset. */
        fun read(dst: FloatArray, dstFrameOffset: Int, frames: Int): Int {
            if (finished || closed) return 0
            ratio = srcSr.toDouble() * curRate / OUT_SR
            var out = 0
            while (out < frames) {
                while (srcPos >= 1.0) {
                    if (!pullSrcFrame()) {
                        finished = true
                        return out
                    }
                    srcPos -= 1.0
                }
                val fr = srcPos.toFloat()
                val i = (dstFrameOffset + out) * 2
                dst[i] = (prevL + (nextL - prevL) * fr) * gain
                dst[i + 1] = (prevR + (nextR - prevR) * fr) * gain
                srcPos += ratio
                out++
                framesOut++
            }
            return out
        }

        fun close() {
            closed = true
            queue.clear()
        }
    }

    // ------------------------------------------------------------- mix loop

    private fun runMix() {
        val minBuf = AudioTrack.getMinBufferSize(
            OUT_SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(OUT_SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuf * 3, 256 * 1024))
            .build()

        var deckA: Deck? = null
        var deckB: Deck? = null
        val tmpA = FloatArray(BLOCK_FRAMES * 2)
        val tmpB = FloatArray(BLOCK_FRAMES * 2)
        val out = FloatArray(BLOCK_FRAMES * 2)
        var framesGlobal = 0L
        var fadeStartF = -1L
        var fadeLenF = 0L
        var endFadeFrames = -1L
        var blockCount = 0

        // Renfort dynamique des basses (sur le signal mixé)
        var mixLpL = 0f
        var mixLpR = 0f
        var bassGain = 0f
        var recentPeak = 0f

        fun openNextValid(fromIndex: Int, rate: Float): Deck? {
            var idx = fromIndex
            while (running && idx < segments.size) {
                val factor = phaseLengthFactor.getOrElse(segments[idx].phaseIndex) { 1f }
                val d = Deck(idx, segments[idx], rate, factor)
                if (d.open()) return d
                d.close()
                idx++
            }
            return null
        }

        try {
            audioTrack.play()
            ui.post { listener.onSessionReady(audioTrack.audioSessionId) }
            deckA = openNextValid(startSegIndex, 1f) ?: return
            deckA.startedAtFrame = 0L
            currentPhaseIndex = deckA.segment.phaseIndex
            announce(deckA)

            while (running) {
                // Pause (miroir du bouton play/pause et du Bluetooth)
                if (paused) {
                    audioTrack.pause()
                    while (paused && running) Thread.sleep(40)
                    if (!running) break
                    audioTrack.play()
                }

                val a = deckA ?: break

                // Hors crossfade : le deck actif revient doucement vers son
                // tempo naturel (~0,4 %/s) pour que les ralentissements de
                // calage ne s'accumulent pas de morceau en morceau.
                if (deckB == null) a.nudgeTowardNatural(0.0002f, framesGlobal)

                // Saut de phase demandé alors qu'une transition est déjà chargée
                val pj = pendingJump
                if (pj != -1 && deckB != null &&
                    (pj == -2 || deckB!!.segIndex != pj)
                ) {
                    deckB!!.close()
                    deckB = null
                    fadeStartF = -1L
                }

                // Programmer la prochaine transition
                if (deckB == null) {
                    val jumping = pendingJump != -1
                    val nextIdx = when {
                        pendingJump >= 0 -> pendingJump
                        else -> a.segIndex + 1
                    }
                    if (nextIdx < segments.size) {
                        val rate = computeRate(a, segments[nextIdx].track)
                        val fadeF =
                            (fadeSeconds(a, segments[nextIdx].track, rate, jumping) *
                                OUT_SR).toLong()
                        if (jumping || a.remainingOut <= fadeF + OUT_SR / 2) {
                            val b = openNextValid(nextIdx, rate)
                            if (b != null) {
                                // Départ aligné sur la grille de beats du deck A,
                                // de préférence sur une fin de mesure (4 temps).
                                // La phase de battement reste exacte même si le
                                // tempo du deck A est revenu vers son naturel.
                                val period = a.beatPeriodFrames
                                val phaseNow = a.beatPhaseAt(framesGlobal)
                                val leadBeats = (OUT_SR * 0.15) / period
                                val nextBeat = ceil(phaseNow + leadBeats)
                                val nextBar = ceil((phaseNow + leadBeats) / 4.0) * 4.0
                                val toBeat = ((nextBeat - phaseNow) * period).toLong()
                                val toBar = ((nextBar - phaseNow) * period).toLong()
                                var start = framesGlobal +
                                    if (toBar <= a.remainingOut) toBar else toBeat
                                if (start < framesGlobal) start = framesGlobal
                                val maxLen = a.remainingOut + a.tailOutFrames -
                                        (start - framesGlobal) - OUT_SR / 10
                                fadeStartF = start
                                fadeLenF = min(fadeF, max(OUT_SR / 4L, maxLen))
                                b.startedAtFrame = start
                                deckB = b
                            }
                            pendingJump = -1
                        }
                    } else if (a.remainingOut <= 0L && endFadeFrames < 0) {
                        endFadeFrames = OUT_SR / 2L // fondu de fin : 0,5 s
                    }
                }

                // Remplir un bloc
                java.util.Arrays.fill(tmpA, 0f)
                java.util.Arrays.fill(tmpB, 0f)
                val na = a.read(tmpA, 0, BLOCK_FRAMES)
                val b = deckB
                if (b != null) {
                    if (na == 0 && framesGlobal < fadeStartF) {
                        // Deck A épuisé plus tôt que prévu : démarrer B tout de suite
                        fadeStartF = framesGlobal
                        b.startedAtFrame = framesGlobal
                    }
                    if (framesGlobal + BLOCK_FRAMES > fadeStartF) {
                        val off = max(0L, fadeStartF - framesGlobal).toInt()
                        b.read(tmpB, off, BLOCK_FRAMES - off)
                    }
                }

                var blockSq = 0.0
                var bassSq = 0.0
                val bd = b
                val fadeActive = bd != null && fadeLenF > 0
                var subA = 0f
                var subB = 0f
                for (i in 0 until BLOCK_FRAMES) {
                    val gf = framesGlobal + i
                    var gA = 1f
                    var gB = 0f
                    var x = 0f
                    val inFade = fadeActive && gf >= fadeStartF
                    if (inFade) {
                        x = ((gf - fadeStartF).toFloat() / fadeLenF).coerceIn(0f, 1f)
                        gA = cos(x * HALF_PI)
                        // Entrée en S : le deck entrant reste discret sur le
                        // premier tiers du fade, puis monte franchement.
                        gB = sin(x.pow(1.6f) * HALF_PI)
                    }
                    var master = 1f
                    if (endFadeFrames >= 0) {
                        master = (endFadeFrames - i).coerceAtLeast(0L).toFloat() /
                                (OUT_SR / 2f)
                    }

                    val aL = tmpA[i * 2]
                    val aR = tmpA[i * 2 + 1]
                    val bL = tmpB[i * 2]
                    val bR = tmpB[i * 2 + 1]
                    // Basses de chaque deck (bass swap + détection de kick)
                    a.lpL += BASS_ALPHA * (aL - a.lpL)
                    a.lpR += BASS_ALPHA * (aR - a.lpR)
                    var vaL = aL
                    var vaR = aR
                    var vbL = bL
                    var vbR = bR
                    if (inFade && bd != null) {
                        bd.lpL += BASS_ALPHA * (bL - bd.lpL)
                        bd.lpR += BASS_ALPHA * (bR - bd.lpR)
                        // Bass swap : les basses de l'entrant sont coupées,
                        // puis échangées avec celles du sortant à ~65 % du fade
                        val swap = ((x - 0.62f) / 0.12f).coerceIn(0f, 1f)
                        val cutA = BASS_SWAP_CUT * swap
                        val cutB = BASS_SWAP_CUT * (1f - swap)
                        vaL -= cutA * a.lpL
                        vaR -= cutA * a.lpR
                        vbL -= cutB * bd.lpL
                        vbR -= cutB * bd.lpR
                        // Enveloppes de kick par sous-fenêtre de 256 frames
                        subA += abs(a.lpL) + abs(a.lpR)
                        subB += abs(bd.lpL) + abs(bd.lpR)
                        if ((i + 1) % 256 == 0) {
                            a.onsets.feed(
                                subA / 256, gf, (a.beatPeriodFrames * 0.6).toLong()
                            )
                            bd.onsets.feed(
                                subB / 256, gf, (bd.beatPeriodFrames * 0.6).toLong()
                            )
                            subA = 0f
                            subB = 0f
                        }
                    }

                    val l = (vaL * gA + vbL * gB) * master
                    val r = (vaR * gA + vbR * gB) * master
                    // Renfort dynamique des basses : extraction < ~120 Hz
                    // (one-pole), boost lissé appliqué sur les passages forts
                    // où les basses manquent.
                    mixLpL += BASS_ALPHA * (l - mixLpL)
                    mixLpR += BASS_ALPHA * (r - mixLpR)
                    blockSq += (l * l + r * r).toDouble()
                    bassSq += (mixLpL * mixLpL + mixLpR * mixLpR).toDouble()
                    val lb = l + bassGain * mixLpL
                    val rb = r + bassGain * mixLpR
                    out[i * 2] = lb.coerceIn(-1f, 1f)
                    out[i * 2 + 1] = rb.coerceIn(-1f, 1f)
                }
                // Verrouillage actif : si les kicks des deux decks dérivent
                // pendant le fade, micro-corriger le rate de l'entrant.
                if (fadeActive && bd != null && framesGlobal >= fadeStartF) {
                    val pa = a.beatPeriodFrames
                    val pb = bd.beatPeriodFrames
                    if (abs(pa - pb) < 0.02 * pa) { // uniquement si tempos verrouillés
                        val oa = a.onsets.lastOnsetFrame
                        val ob = bd.onsets.lastOnsetFrame
                        if (oa > 0 && ob > 0) {
                            var d = (ob - oa).toDouble()
                            d -= Math.round(d / pb) * pb // repli à ±période/2
                            val delta = (d / pb * 0.002).coerceIn(-3.0e-4, 3.0e-4)
                            bd.syncNudge(delta.toFloat())
                        }
                    }
                }
                // Mise à jour du boost pour le bloc suivant
                run {
                    val blockRms = kotlin.math.sqrt(blockSq / (2 * BLOCK_FRAMES)).toFloat()
                    recentPeak = max(blockRms, recentPeak * 0.9995f)
                    val loud = recentPeak > 1e-3f && blockRms > 0.75f * recentPeak
                    val ratio = if (blockRms > 1e-4f)
                        kotlin.math.sqrt(bassSq / (2 * BLOCK_FRAMES)).toFloat() / blockRms
                    else 1f
                    val target = if (loud && ratio < 0.30f)
                        min(0.5f, (0.30f - ratio) * 2.5f)
                    else 0f
                    bassGain += 0.02f * (target - bassGain)
                }
                audioTrack.write(out, 0, BLOCK_FRAMES * 2, AudioTrack.WRITE_BLOCKING)
                framesGlobal += BLOCK_FRAMES
                a.advancePhase(BLOCK_FRAMES)

                // Fin du crossfade : B devient le deck actif
                if (b != null && framesGlobal >= fadeStartF + fadeLenF) {
                    a.close()
                    deckA = b
                    deckB = null
                    fadeStartF = -1L
                    currentPhaseIndex = b.segment.phaseIndex
                    announce(b)
                }

                // Fin de set
                if (endFadeFrames >= 0) {
                    endFadeFrames -= BLOCK_FRAMES
                    if (endFadeFrames <= 0) break
                }
                if (na == 0 && deckB == null && a.segIndex + 1 >= segments.size) break

                // Progression (toutes les ~0,7 s)
                blockCount++
                if (blockCount % 16 == 0) {
                    val total = a.totalOutFrames
                    val p = if (total > 0) a.framesOut.toFloat() / total else 0f
                    ui.post { listener.onProgress(p.coerceIn(0f, 1f)) }
                }
            }
        } catch (_: Exception) {
        } finally {
            deckA?.close()
            deckB?.close()
            try {
                audioTrack.stop()
            } catch (_: Exception) {
            }
            audioTrack.release()
            running = false
            ui.post { listener.onStopped() }
        }
    }

    private fun announce(deck: Deck) {
        val t = deck.track
        val p = deck.segment.phaseIndex
        ui.post { listener.onTrackChanged(t, p) }
    }

    /**
     * Durée de la jonction selon la qualité du calage :
     *  - tempos calés ET tonalités compatibles : long blend (14 s), la
     *    transition peut s'étirer sans accroc ;
     *  - tempos calés : blend standard (8 s) ;
     *  - calage impossible (écart > ±8 %, hors double/moitié) : coupe courte
     *    (3,5 s) — étirer deux tempos non synchrones ferait battre les beats.
     */
    private fun fadeSeconds(current: Deck, next: Track, rate: Float, jumping: Boolean): Double {
        if (jumping) return FADE_JUMP_S
        val effA = current.track.bpm * current.curRate
        val effB = next.bpm * rate
        if (effA <= 0f || effB <= 0f) return FADE_NORMAL_S
        val ratio = effA / effB
        val lockErr = minOf(
            abs(ratio - 1f),
            abs(ratio - 2f) / 2f,
            abs(ratio - 0.5f) * 2f
        )
        if (lockErr > 0.005f) return FADE_CUT_S
        val harmonic = MixEngine.camelotScore(current.track.camelot, next.camelot)
        return if (harmonic >= 0.8f) FADE_LOCKED_HARMONIC_S else FADE_NORMAL_S
    }

    /** Cale le BPM du morceau entrant sur le BPM effectif du deck actif (±8 %). */
    private fun computeRate(current: Deck, next: Track): Float {
        val effBpm = current.track.bpm * current.curRate
        if (effBpm <= 0f || next.bpm <= 0f) return 1f
        val base = effBpm / next.bpm
        val candidates = floatArrayOf(base, base * 2f, base / 2f)
        var best = base.coerceIn(0.92f, 1.08f)
        var bestDist = Float.MAX_VALUE
        for (c in candidates) {
            if (c in 0.92f..1.08f) {
                val d = kotlin.math.abs(c - 1f)
                if (d < bestDist) {
                    bestDist = d
                    best = c
                }
            }
        }
        return best
    }
}
