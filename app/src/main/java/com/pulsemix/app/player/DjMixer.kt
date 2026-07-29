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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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
    }

    private data class Segment(val track: Track, val phaseIndex: Int)

    companion object {
        const val OUT_SR = 44100
        const val BLOCK_FRAMES = 2048
        const val FADE_NORMAL_S = 8.0
        const val FADE_JUMP_S = 4.0
        const val TAIL_MS = 9_000L
        const val HALF_PI = (Math.PI / 2).toFloat()
    }

    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var pendingJump = -1
    @Volatile private var currentPhaseIndex = 0

    private var segments: List<Segment> = emptyList()
    private var plan: MixEngine.MixPlan? = null
    private var mixThread: Thread? = null

    // ------------------------------------------------------------------ API

    fun start(plan: MixEngine.MixPlan) {
        stop()
        this.plan = plan
        segments = plan.phases.flatMapIndexed { pi, phase ->
            phase.tracks.filter { it.analyzed && it.bpm > 0f }.map { Segment(it, pi) }
        }
        if (segments.isEmpty()) {
            ui.post { listener.onStopped() }
            return
        }
        running = true
        paused = false
        pendingJump = -1
        currentPhaseIndex = 0
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

    private inner class Deck(val segIndex: Int, val segment: Segment, val rate: Float) {
        val track: Track = segment.track
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
            logicalEndMs = min(best + track.segmentMs, track.durationMs)
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
            ratio = srcSr.toDouble() * rate / OUT_SR
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

        /** Période de beat en frames de sortie, à ce rate. */
        val beatPeriodFrames: Double
            get() = OUT_SR * 60.0 / (track.bpm.toDouble() * rate)

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
                dst[i] = prevL + (nextL - prevL) * fr
                dst[i + 1] = prevR + (nextR - prevR) * fr
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

        fun openNextValid(fromIndex: Int, rate: Float): Deck? {
            var idx = fromIndex
            while (running && idx < segments.size) {
                val d = Deck(idx, segments[idx], rate)
                if (d.open()) return d
                d.close()
                idx++
            }
            return null
        }

        try {
            audioTrack.play()
            deckA = openNextValid(0, 1f) ?: return
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
                        val fadeF =
                            ((if (jumping) FADE_JUMP_S else FADE_NORMAL_S) * OUT_SR).toLong()
                        if (jumping || a.remainingOut <= fadeF + OUT_SR / 2) {
                            val rate = computeRate(a, segments[nextIdx].track)
                            val b = openNextValid(nextIdx, rate)
                            if (b != null) {
                                // Départ aligné sur la grille de beats du deck A
                                val period = a.beatPeriodFrames
                                val lead = (OUT_SR * 0.15).toLong()
                                val k = ceil(
                                    (framesGlobal + lead - a.startedAtFrame) / period
                                ).toLong()
                                var start = a.startedAtFrame + (k * period).toLong()
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

                for (i in 0 until BLOCK_FRAMES) {
                    val gf = framesGlobal + i
                    var gA = 1f
                    var gB = 0f
                    if (b != null && gf >= fadeStartF && fadeLenF > 0) {
                        val x = ((gf - fadeStartF).toFloat() / fadeLenF).coerceIn(0f, 1f)
                        gA = cos(x * HALF_PI)
                        gB = sin(x * HALF_PI)
                    }
                    var master = 1f
                    if (endFadeFrames >= 0) {
                        master = (endFadeFrames - i).coerceAtLeast(0L).toFloat() /
                                (OUT_SR / 2f)
                    }
                    val l = (tmpA[i * 2] * gA + tmpB[i * 2] * gB) * master
                    val r = (tmpA[i * 2 + 1] * gA + tmpB[i * 2 + 1] * gB) * master
                    out[i * 2] = l.coerceIn(-1f, 1f)
                    out[i * 2 + 1] = r.coerceIn(-1f, 1f)
                }
                audioTrack.write(out, 0, BLOCK_FRAMES * 2, AudioTrack.WRITE_BLOCKING)
                framesGlobal += BLOCK_FRAMES

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

    /** Cale le BPM du morceau entrant sur le BPM effectif du deck actif (±8 %). */
    private fun computeRate(current: Deck, next: Track): Float {
        val effBpm = current.track.bpm * current.rate
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
