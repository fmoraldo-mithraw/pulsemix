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
import kotlin.math.exp
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
        // Fondus assez longs pour « sentir arriver » le morceau entrant :
        // il est audible tôt (basses coupées, donc propre), et ne prend le
        // dessus qu'en seconde moitié.
        const val FADE_NORMAL_S = 10.0
        const val FADE_JUMP_S = 4.0
        // Jonctions adaptatives : long blend quand tempos calés et tonalités
        // compatibles, coupe franche quand le calage est impossible.
        const val FADE_LOCKED_HARMONIC_S = 14.0
        const val FADE_CUT_S = 4.5
        const val TAIL_MS = 16_000L
        const val HALF_PI = (Math.PI / 2).toFloat()
        // One-pole ~120 Hz à 44,1 kHz pour l'extraction des basses
        const val BASS_ALPHA = 0.017f
        // Bass swap : atténuation des basses du deck « en retrait » pendant
        // le crossfade (une seule ligne de basse à la fois)
        const val BASS_SWAP_CUT = 0.95f
        // Boucle de sortie : durée maximale rejouée en boucle (garde-fou)
        const val LOOP_MAX_OUT: Long = 30L * 44_100L
        // One-pole ~2,5 kHz : extraction des médiums (mid swap)
        const val MID_ALPHA = 0.30f
        // Types de transition : une technique de DJ par situation
        const val KIND_NORMAL = 0   // filter sweep passe-haut (le sortant s'amincit)
        const val KIND_CUT = 1      // coupe courte + echo-out
        const val KIND_HARMONIC = 2 // long blend + mid swap
        const val KIND_DARK = 3     // filter sweep passe-bas (le sortant s'étouffe)
        const val KIND_EQ = 4       // échange de basses classique, sans filtre
        const val ECHO_FEEDBACK = 0.55f
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
    @Volatile private var rehearsal = false
    @Volatile private var recorder: MixRecorder? = null

    /** Active/coupe l'enregistrement du set (fichier M4A). */
    fun setRecorder(r: MixRecorder?) {
        val old = recorder
        recorder = r
        old?.stop()
    }

    private var segments: List<Segment> = emptyList()
    private var plan: MixEngine.MixPlan? = null
    private var mixThread: Thread? = null
    private var phaseLengthFactor = FloatArray(0)
    // Dernière technique utilisée : évite deux fois de suite la même quand
    // plusieurs conviennent (variété façon DJ)
    private var lastFadeKind = -1

    // ------------------------------------------------------------------ API

    /**
     * @param startPhase phase de départ (reprise après fermeture/plantage).
     * @param rehearsalMode true = répétition des transitions : chaque morceau
     * est avancé jusqu'à ses ~15 dernières secondes, on n'entend que les
     * jonctions.
     */
    fun start(plan: MixEngine.MixPlan, startPhase: Int = 0, rehearsalMode: Boolean = false) {
        stop()
        rehearsal = rehearsalMode
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
        // Passe-bas ~2,5 kHz : bande médiums = midLp - lp (mid swap)
        var midLpL = 0f
        var midLpR = 0f
        // Passe-bas balayé (filter sweep du sortant)
        var sweepLpL = 0f
        var sweepLpR = 0f
        val onsets = OnsetTracker()

        /** Micro-correction de synchro pendant le fade (cumul borné à ±0,4 %,
         *  relatif au rate courant : compatible avec le speed boost). */
        private var syncAccum = 0f
        fun syncNudge(delta: Float) {
            val d = delta.coerceIn(-0.004f - syncAccum, 0.004f - syncAccum)
            syncAccum += d
            curRate += d
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

        /** Rapproche le tempo de la cible d'un petit pas (hors crossfade).
         *  Cible 1.0 = tempo naturel ; > 1 = speed boost. */
        fun nudgeTowardNatural(step: Float, frameNow: Long, target: Float = 1f) {
            if (curRate == target) return
            if (!rampStarted) {
                beatPhase = (frameNow - startedAtFrame) / beatPeriodFrames
                rampStarted = true
            }
            curRate = if (curRate > target) max(target, curRate - step)
            else min(target, curRate + step)
        }

        private var ratio = 1.0
        private var srcPos = 1.0
        private var prevL = 0f; private var prevR = 0f
        private var nextL = 0f; private var nextR = 0f
        private var curChunk: FloatArray? = null
        private var curFrames = 0
        private var curPos = 0

        // Boucle de sortie (« loop out ») : capture circulaire des derniers
        // battements produits ; à la fin du passage fort, les 8 derniers
        // battements sont rejoués en boucle sous le fondu de sortie.
        // Dimensionnée pour le rate le plus lent possible (crans de vitesse
        // négatifs : jusqu'à -12 %) + l'amorce de couture (~50 ms)
        private val loopCapacity: Int =
            if (track.bpm > 0f) (8.0 * 60.0 / track.bpm * OUT_SR / 0.80).toInt() + 2
            else OUT_SR * 4
        private val loopCapture = FloatArray(loopCapacity * 2)
        private var loopWritePos = 0
        private var loopFilled = 0
        private var loopData: FloatArray? = null
        private var loopPos = 0
        private var loopLen = 0
        private var loopXfade = 0
        private var loopedOut = 0L

        /** Active la boucle de sortie. @return false si trop peu de matière. */
        private fun startLoop(): Boolean {
            if (loopData != null) return true
            val period = beatPeriodFrames
            if (period <= 0.0 || period.isNaN()) return false
            var len = (8.0 * period).toInt()
            if (len > loopFilled) len = loopFilled
            if (len < OUT_SR / 2) return false
            // Amorce de couture : ~50 ms de signal d'avant-boucle, fondues
            // sur la fin de chaque passage pour un raccord sans claquement.
            var xf = min(2_205, len / 8)
            if (len + xf > loopFilled) xf = (loopFilled - len).coerceAtLeast(0)
            val total = len + xf
            val data = FloatArray(total * 2)
            var src = (loopWritePos - total + loopCapacity) % loopCapacity
            for (k in 0 until total) {
                data[k * 2] = loopCapture[src * 2]
                data[k * 2 + 1] = loopCapture[src * 2 + 1]
                src = (src + 1) % loopCapacity
            }
            loopData = data
            loopLen = len
            loopXfade = xf
            loopPos = xf // reprise 8 temps en arrière, juste après l'amorce
            loopedOut = 0
            return true
        }

        // Boucle live (bouton maintenu) : les 4 derniers temps tournent en
        // boucle pendant que la lecture continue « en dessous » (slip) — au
        // relâchement, le morceau reprend là où il serait arrivé.
        private var liveData: FloatArray? = null
        private var liveLen = 0
        private var liveXf = 0
        private var livePos = 0

        fun startLiveLoop() {
            if (liveData != null) return
            val period = beatPeriodFrames
            if (period <= 0.0 || period.isNaN()) return
            val len = (4.0 * period).toInt()
            if (len < OUT_SR / 4 || len + 512 > loopFilled) return
            var xf = min(2_205, len / 8)
            if (len + xf > loopFilled) xf = loopFilled - len
            val total = len + xf
            val data = FloatArray(total * 2)
            var src = (loopWritePos - total + loopCapacity) % loopCapacity
            for (k in 0 until total) {
                data[k * 2] = loopCapture[src * 2]
                data[k * 2 + 1] = loopCapture[src * 2 + 1]
                src = (src + 1) % loopCapacity
            }
            liveLen = len
            liveXf = xf
            livePos = xf
            liveData = data
        }

        fun stopLiveLoop() {
            liveData = null
        }

        init {
            val best = track.bestStartMs.coerceIn(0L, max(0L, track.durationMs - 15_000L))
            val beat = track.firstBeatMs
            // Ancre sur le premier beat du passage fort
            val anchor = if (beat in best..(best + track.segmentMs)) beat else best
            // Modulation par phase, puis ré-arrondi aux phrases de 16 temps
            // pour que la fin reste sur une frontière musicale.
            var segMs = (track.segmentMs * lengthFactor).toLong()
            if (track.bpm > 0f) {
                val phraseMs = 16.0 * 60_000.0 / track.bpm
                val phrases = floor(segMs / phraseMs).toLong()
                if (phrases >= 2) segMs = (phrases * phraseMs).toLong()
            }
            // Le deck démarre pile sur le passage fort et déroule le morceau
            // naturellement sous le fondu d'entrée (pas de boucle de début :
            // essayée, jugée décevante). À l'autre bout, la boucle de sortie
            // (8 derniers battements) prend le relais sous le fondu de sortie.
            startMs = anchor
            logicalEndMs = min(anchor + segMs, track.durationMs)
            decodeEndMs = min(logicalEndMs + 2_000, track.durationMs)

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
                    // Famine du décodeur : décroître doucement vers le silence
                    // (tenir une valeur fixe produisait un bourdonnement type
                    // bégaiement sous forte charge CPU)
                    prevL = nextL; prevR = nextR
                    nextL *= 0.98f; nextR *= 0.98f
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
                // Boucle de sortie active : rejouer les derniers battements
                val ld = loopData
                if (ld != null) {
                    if (loopedOut >= LOOP_MAX_OUT) {
                        finished = true
                        return out
                    }
                    val i = (dstFrameOffset + out) * 2
                    var sL = ld[loopPos * 2]
                    var sR = ld[loopPos * 2 + 1]
                    if (loopXfade > 0 && loopPos >= loopLen) {
                        // Couture fondue vers l'amorce d'avant-boucle
                        val q = loopPos - loopLen
                        val t = (q + 1).toFloat() / loopXfade
                        sL = sL * (1f - t) + ld[q * 2] * t
                        sR = sR * (1f - t) + ld[q * 2 + 1] * t
                    }
                    dst[i] = sL
                    dst[i + 1] = sR
                    loopPos++
                    if (loopPos >= loopLen + loopXfade) loopPos = loopXfade
                    loopedOut++
                    out++
                    framesOut++
                    continue
                }
                // Fin du passage fort : basculer sur la boucle
                if (framesOut >= totalOutFrames && startLoop()) continue
                while (srcPos >= 1.0) {
                    if (!pullSrcFrame()) {
                        if (!startLoop()) {
                            finished = true
                            return out
                        }
                        break
                    }
                    srcPos -= 1.0
                }
                if (loopData != null) continue
                val fr = srcPos.toFloat()
                val i = (dstFrameOffset + out) * 2
                val l = (prevL + (nextL - prevL) * fr) * gain
                val r = (prevR + (nextR - prevR) * fr) * gain
                dst[i] = l
                dst[i + 1] = r
                // Capture circulaire pour la boucle de sortie
                loopCapture[loopWritePos * 2] = l
                loopCapture[loopWritePos * 2 + 1] = r
                loopWritePos = (loopWritePos + 1) % loopCapacity
                if (loopFilled < loopCapacity) loopFilled++
                // Boucle live active : la sortie vient de la boucle, le flux
                // vient d'avancer d'une frame en dessous (slip)
                val lv = liveData
                if (lv != null && liveLen > 0) {
                    var sL = lv[livePos * 2]
                    var sR = lv[livePos * 2 + 1]
                    if (liveXf > 0 && livePos >= liveLen) {
                        val q = livePos - liveLen
                        val t = (q + 1).toFloat() / liveXf
                        sL = sL * (1f - t) + lv[q * 2] * t
                        sR = sR * (1f - t) + lv[q * 2 + 1] * t
                    }
                    dst[i] = sL
                    dst[i + 1] = sR
                    livePos++
                    if (livePos >= liveLen + liveXf) livePos = liveXf
                }
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
        var fadeKindF = KIND_NORMAL
        var echoBuf: FloatArray? = null
        var echoPos = 0
        var endFadeFrames = -1L
        var blockCount = 0

        // Renfort dynamique des basses (sur le signal mixé)
        var mixLpL = 0f
        var mixLpR = 0f
        var bassGain = 0f
        var recentPeak = 0f
        // Bass boost manuel (bouton) : rampe progressive
        var manualBass = 0f
        // Limiteur doux de sortie (attaque rapide, relâche lente)
        var limGain = 1f
        // Effets live du panneau « Effets » : filtre maître balayé, écho calé
        // sur le tempo, auto-pan, gate rythmique
        var filtSm = 0f
        var filtLpL = 0f
        var filtLpR = 0f
        val echoDly = FloatArray(OUT_SR * 2) // 1 s stéréo
        var echoDlyPos = 0
        var echoSm = 0f
        var panPhase = 0.0
        var gatePhase = 0.0
        var gateSm = 1f

        // Répétition : avance rapide jusqu'aux ~15 dernières secondes du deck
        fun fastForward(d: Deck) {
            if (!rehearsal) return
            val tailFrames = 15L * OUT_SR
            var w = 0
            while (running && d.remainingOut > tailFrames) {
                if (d.read(tmpA, 0, BLOCK_FRAMES) == 0) break
                if (++w % 8 == 0) {
                    java.util.Arrays.fill(out, 0f)
                    audioTrack.write(out, 0, BLOCK_FRAMES * 2, AudioTrack.WRITE_BLOCKING)
                }
            }
            // recaler la grille de beats sur la position réellement atteinte
            d.startedAtFrame = framesGlobal - d.framesOut
        }

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
            fastForward(deckA)

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
                // tempo naturel (~0,4 %/s) — ou vers +8 % si le speed boost
                // est actif — sans jamais casser la grille de beats.
                if (deckB == null) {
                    val target = 1f + 0.04f * PlayerCore.speedLevel.value
                    a.nudgeTowardNatural(0.0002f, framesGlobal, target)
                }

                // Boucle live (bouton maintenu dans le panneau Effets)
                if (PlayerCore.liveLoop.value) a.startLiveLoop()
                else a.stopLiveLoop()

                // Saut de phase demandé alors qu'une transition est déjà chargée
                val pj = pendingJump
                if (pj != -1 && deckB != null &&
                    (pj == -2 || deckB!!.segIndex != pj)
                ) {
                    deckB!!.close()
                    deckB = null
                    fadeStartF = -1L
                    echoBuf = null
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
                        val (fadeS, fadeKind) =
                            fadeSpec(a, segments[nextIdx].track, rate, jumping)
                        val fadeF = (fadeS * OUT_SR).toLong()
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
                                // La boucle de sortie prolonge le deck A sous
                                // le fondu : la capacité inclut LOOP_MAX_OUT
                                val maxLen = a.remainingOut + LOOP_MAX_OUT -
                                        (start - framesGlobal) - OUT_SR / 10
                                fadeStartF = start
                                fadeLenF = min(fadeF, max(OUT_SR / 4L, maxLen))
                                fadeKindF = fadeKind
                                lastFadeKind = fadeKind
                                // Echo-out : ligne à retard d'un battement
                                echoBuf = if (fadeKind == KIND_CUT) {
                                    echoPos = 0
                                    FloatArray(
                                        a.beatPeriodFrames.toInt()
                                            .coerceIn(4_410, 88_200) * 2
                                    )
                                } else null
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
                // Bass boost manuel : monte/descend progressivement (~2 s),
                // par crans (négatif = coupe des basses)
                val manualTarget = 0.275f * PlayerCore.bassLevel.value
                manualBass = if (manualTarget > manualBass)
                    min(manualTarget, manualBass + 0.01f)
                else max(manualTarget, manualBass - 0.01f)
                val appliedBass =
                    if (manualBass < 0f) manualBass else max(bassGain, manualBass)
                val bd = b
                val fadeActive = bd != null && fadeLenF > 0
                // Filter sweep : coupure balayée sur la durée du fondu.
                // KIND_NORMAL : passe-haut ~40 Hz -> ~6 kHz (le sortant
                // s'amincit). KIND_DARK : passe-bas ~6 kHz -> ~150 Hz (le
                // sortant s'assombrit et s'étouffe). Coefficient recalculé
                // par bloc (suffisant à 2048 frames).
                var sweepAlpha = 0f
                if (fadeActive && framesGlobal >= fadeStartF &&
                    (fadeKindF == KIND_NORMAL || fadeKindF == KIND_DARK)
                ) {
                    val xb = ((framesGlobal - fadeStartF).toFloat() / fadeLenF)
                        .coerceIn(0f, 1f)
                    val fc = if (fadeKindF == KIND_NORMAL)
                        40f * 10f.pow(2.2f * xb)
                    else 6_000f * 10f.pow(-1.6f * xb)
                    sweepAlpha = 1f - exp(-2f * Math.PI.toFloat() * fc / OUT_SR)
                }
                var subA = 0f
                var subB = 0f
                // Effets live : cibles lissées et paramètres du bloc
                filtSm += (PlayerCore.filterLevel.value - filtSm)
                    .coerceIn(-0.08f, 0.08f)
                echoSm += (PlayerCore.echoLevel.value - echoSm)
                    .coerceIn(-0.05f, 0.05f)
                val filtOn = abs(filtSm) > 0.05f
                val fAlpha = if (filtOn) {
                    val t = abs(filtSm) / 3f
                    // >0 : passe-haut 60 Hz -> 2,5 kHz ; <0 : passe-bas
                    // 12 kHz -> 400 Hz (par crans, glissé en douceur)
                    val fc = if (filtSm > 0f) 60f * 42f.pow(t)
                    else 12_000f * 0.033f.pow(t)
                    1f - exp(-2f * Math.PI.toFloat() * fc / OUT_SR)
                } else 0f
                val echoOn = echoSm > 0.05f
                val echoDelay = (a.beatPeriodFrames / 2.0).toInt()
                    .coerceIn(2_205, OUT_SR - 1) // ½ temps
                val echoWet = 0.13f * echoSm
                val echoFb = 0.17f * echoSm
                val panLvl = PlayerCore.panLevel.value
                val panStep = if (panLvl != 0)
                    (if (panLvl > 0) 1 else -1) * 2.0 * Math.PI /
                        (OUT_SR * 12.0 / (1 shl (abs(panLvl) - 1)))
                else 0.0
                val gateDepth = when (PlayerCore.gateLevel.value) {
                    1 -> 0.5f; 2 -> 0.75f; 3 -> 0.95f; else -> 0f
                }
                val gateStep = 1.0 / a.beatPeriodFrames
                for (i in 0 until BLOCK_FRAMES) {
                    val gf = framesGlobal + i
                    var gA = 1f
                    var gB = 0f
                    var x = 0f
                    val inFade = fadeActive && gf >= fadeStartF
                    if (inFade) {
                        x = ((gf - fadeStartF).toFloat() / fadeLenF).coerceIn(0f, 1f)
                        if (fadeKindF == KIND_HARMONIC) {
                            // Long blend : les deux morceaux sont faits pour
                            // se superposer, courbes equal-power symétriques
                            gA = cos(x * HALF_PI)
                            gB = sin(x * HALF_PI)
                        } else {
                            // Sortie raide : le sortant descend vite dès le
                            // début du fondu (moins de bouillie à mi-parcours)
                            gA = cos(x.pow(0.7f) * HALF_PI)
                            // Entrée progressive : l'entrant s'annonce dès le
                            // premier quart (sans ses basses : propre), puis
                            // monte franchement.
                            gB = sin(x.pow(1.3f) * HALF_PI)
                        }
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
                        when (fadeKindF) {
                            KIND_HARMONIC -> {
                                // Long blend : bass swap à ~45 % du fondu,
                                // puis mid swap à ~55 % — les deux morceaux
                                // s'échangent bande par bande, façon EQ 3
                                // bandes d'une table de mixage.
                                val swap = ((x - 0.45f) / 0.10f).coerceIn(0f, 1f)
                                val cutA = BASS_SWAP_CUT * swap
                                val cutB = BASS_SWAP_CUT * (1f - swap)
                                vaL -= cutA * a.lpL
                                vaR -= cutA * a.lpR
                                vbL -= cutB * bd.lpL
                                vbR -= cutB * bd.lpR
                                a.midLpL += MID_ALPHA * (aL - a.midLpL)
                                a.midLpR += MID_ALPHA * (aR - a.midLpR)
                                bd.midLpL += MID_ALPHA * (bL - bd.midLpL)
                                bd.midLpR += MID_ALPHA * (bR - bd.midLpR)
                                val ms = ((x - 0.55f) / 0.10f).coerceIn(0f, 1f)
                                val mCutA = 0.8f * ms
                                val mCutB = 0.8f * (1f - ms)
                                vaL -= mCutA * (a.midLpL - a.lpL)
                                vaR -= mCutA * (a.midLpR - a.lpR)
                                vbL -= mCutB * (bd.midLpL - bd.lpL)
                                vbR -= mCutB * (bd.midLpR - bd.lpR)
                            }
                            KIND_NORMAL -> {
                                // Filter sweep passe-haut : le sortant
                                // s'amincit (basses puis médiums) pendant
                                // que l'entrant récupère ses basses tôt.
                                a.sweepLpL += sweepAlpha * (aL - a.sweepLpL)
                                a.sweepLpR += sweepAlpha * (aR - a.sweepLpR)
                                vaL = aL - a.sweepLpL
                                vaR = aR - a.sweepLpR
                                // Le balayage retire les basses du sortant dès
                                // ~20 % du fondu : l'entrant récupère les
                                // siennes à 25 % (45 % laissait un trou de
                                // graves entre les deux).
                                val rel = ((x - 0.25f) / 0.10f).coerceIn(0f, 1f)
                                val cutB = BASS_SWAP_CUT * (1f - rel)
                                vbL -= cutB * bd.lpL
                                vbR -= cutB * bd.lpR
                            }
                            KIND_DARK -> {
                                // Filter sweep passe-bas : le sortant
                                // s'assombrit et s'étouffe — il garde ses
                                // basses plus longtemps, l'entrant récupère
                                // les siennes à ~50 % seulement.
                                a.sweepLpL += sweepAlpha * (aL - a.sweepLpL)
                                a.sweepLpR += sweepAlpha * (aR - a.sweepLpR)
                                vaL = a.sweepLpL
                                vaR = a.sweepLpR
                                val rel = ((x - 0.50f) / 0.10f).coerceIn(0f, 1f)
                                val cutB = BASS_SWAP_CUT * (1f - rel)
                                vbL -= cutB * bd.lpL
                                vbR -= cutB * bd.lpR
                                // Et le sortant cède ses basses au même moment
                                vaL -= BASS_SWAP_CUT * rel * a.lpL
                                vaR -= BASS_SWAP_CUT * rel * a.lpR
                            }
                            KIND_EQ -> {
                                // Échange de basses classique, sans filtre :
                                // la transition « table de mixage » sobre.
                                val swap = ((x - 0.45f) / 0.10f).coerceIn(0f, 1f)
                                val cutA = BASS_SWAP_CUT * swap
                                val cutB = BASS_SWAP_CUT * (1f - swap)
                                vaL -= cutA * a.lpL
                                vaR -= cutA * a.lpR
                                vbL -= cutB * bd.lpL
                                vbR -= cutB * bd.lpR
                            }
                            // KIND_CUT : pas de traitement spectral ici,
                            // l'echo-out agit après le mixage.
                        }
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

                    var l = (vaL * gA + vbL * gB) * master
                    var r = (vaR * gA + vbR * gB) * master
                    // Echo-out (KIND_CUT) : le début du fondu alimente une
                    // ligne à retard d'un battement ; ses répétitions
                    // s'éteignent en feedback pendant que l'entrant démarre.
                    val eb = echoBuf
                    if (eb != null && inFade) {
                        val n = eb.size / 2
                        val eL = eb[echoPos * 2]
                        val eR = eb[echoPos * 2 + 1]
                        l += eL * master
                        r += eR * master
                        val feedL = if (x < 0.3f) vaL * gA else 0f
                        val feedR = if (x < 0.3f) vaR * gA else 0f
                        eb[echoPos * 2] = eL * ECHO_FEEDBACK + feedL
                        eb[echoPos * 2 + 1] = eR * ECHO_FEEDBACK + feedR
                        echoPos = (echoPos + 1) % n
                    }
                    // ---- Effets live (panneau Effets) ----
                    // Filtre maître : passe-haut (crans +) ou passe-bas (crans -)
                    if (filtOn) {
                        filtLpL += fAlpha * (l - filtLpL)
                        filtLpR += fAlpha * (r - filtLpR)
                        if (filtSm > 0f) {
                            l -= filtLpL
                            r -= filtLpR
                        } else {
                            l = filtLpL
                            r = filtLpR
                        }
                    } else {
                        filtLpL = l
                        filtLpR = r
                    }
                    // Écho calé sur le tempo (½ temps, dose par crans)
                    if (echoOn) {
                        var rp = echoDlyPos - echoDelay
                        if (rp < 0) rp += OUT_SR
                        val dL = echoDly[rp * 2]
                        val dR = echoDly[rp * 2 + 1]
                        echoDly[echoDlyPos * 2] = l + dL * echoFb
                        echoDly[echoDlyPos * 2 + 1] = r + dR * echoFb
                        echoDlyPos++
                        if (echoDlyPos >= OUT_SR) echoDlyPos = 0
                        l += dL * echoWet
                        r += dR * echoWet
                    }
                    // Gate rythmique : hachage en croches calé sur le beat
                    if (gateDepth > 0f) {
                        gatePhase += gateStep
                        val frac = (gatePhase * 2.0) % 1.0
                        val target = if (frac < 0.55) 1f else 1f - gateDepth
                        gateSm += 0.004f * (target - gateSm)
                        l *= gateSm
                        r *= gateSm
                    } else gateSm = 1f
                    // Auto-pan : le son tourne lentement (sens et vitesse par crans)
                    if (panStep != 0.0) {
                        panPhase += panStep
                        val c = kotlin.math.cos(panPhase).toFloat()
                        l *= 0.7f + 0.3f * c
                        r *= 0.7f - 0.3f * c
                    }
                    // Renfort dynamique des basses : extraction < ~120 Hz
                    // (one-pole), boost lissé appliqué sur les passages forts
                    // où les basses manquent.
                    mixLpL += BASS_ALPHA * (l - mixLpL)
                    mixLpR += BASS_ALPHA * (r - mixLpR)
                    blockSq += (l * l + r * r).toDouble()
                    bassSq += (mixLpL * mixLpL + mixLpR * mixLpR).toDouble()
                    val lb = l + appliedBass * mixLpL
                    val rb = r + appliedBass * mixLpR
                    // Limiteur doux : évite l'écrêtage brut quand normalisation,
                    // renfort de basses et EQ s'empilent
                    val peak = max(abs(lb), abs(rb))
                    if (peak * limGain > 0.98f) limGain = 0.98f / peak
                    else limGain += (1f - limGain) * 0.0008f
                    out[i * 2] = (lb * limGain).coerceIn(-1f, 1f)
                    out[i * 2 + 1] = (rb * limGain).coerceIn(-1f, 1f)
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
                // Mise à jour du boost pour le bloc suivant. Gelé pendant les
                // fondus : le bass swap et le filter sweep creusent les basses
                // exprès, le renfort dynamique ne doit pas les recombler.
                run {
                    val blockRms = kotlin.math.sqrt(blockSq / (2 * BLOCK_FRAMES)).toFloat()
                    recentPeak = max(blockRms, recentPeak * 0.9995f)
                    val loud = recentPeak > 1e-3f && blockRms > 0.75f * recentPeak
                    val ratio = if (blockRms > 1e-4f)
                        kotlin.math.sqrt(bassSq / (2 * BLOCK_FRAMES)).toFloat() / blockRms
                    else 1f
                    val inFadeBlock = fadeActive && framesGlobal >= fadeStartF
                    val target = if (!inFadeBlock && loud && ratio < 0.30f)
                        min(0.5f, (0.30f - ratio) * 2.5f)
                    else 0f
                    bassGain += 0.02f * (target - bassGain)
                }
                audioTrack.write(out, 0, BLOCK_FRAMES * 2, AudioTrack.WRITE_BLOCKING)
                recorder?.write(out, BLOCK_FRAMES * 2)
                framesGlobal += BLOCK_FRAMES
                a.advancePhase(BLOCK_FRAMES)

                // Fin du crossfade : B devient le deck actif
                if (b != null && framesGlobal >= fadeStartF + fadeLenF) {
                    a.close()
                    deckA = b
                    deckB = null
                    fadeStartF = -1L
                    echoBuf = null
                    currentPhaseIndex = b.segment.phaseIndex
                    announce(b)
                    fastForward(b)
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
            recorder?.stop()
            recorder = null
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
     * Durée ET technique de la jonction, choisies par transition comme un
     * DJ choisit son geste :
     *  - tempos calés ET tonalités compatibles : long blend (14 s) avec
     *    bass swap puis mid swap (KIND_HARMONIC) ;
     *  - calage impossible (écart > ±8 %, hors double/moitié) : coupe
     *    courte (4,5 s) avec echo-out (KIND_CUT) — étirer deux tempos non
     *    synchrones ferait battre les beats ;
     *  - tempos calés, tonalités moyennes (le cas le plus fréquent) : la
     *    technique est choisie selon le caractère des deux morceaux —
     *    entrant calme → sweep sombre et long ; deux morceaux énergiques →
     *    palette punchy (sweep, coupe écho, bass swap) ; sortant brillant →
     *    plutôt sweep sombre ; sinon sweep clair/sombre/bass swap. Un tirage
     *    déterministe par paire varie le choix, sans jamais répéter la
     *    technique précédente quand plusieurs conviennent ;
     *  - saut de phase manuel : fondu court (4 s), bass swap neutre.
     */
    private fun fadeSpec(
        current: Deck,
        next: Track,
        rate: Float,
        jumping: Boolean
    ): Pair<Double, Int> {
        if (jumping) return FADE_JUMP_S to KIND_EQ
        val effA = current.track.bpm * current.curRate
        val effB = next.bpm * rate
        if (effA <= 0f || effB <= 0f) return FADE_NORMAL_S to KIND_EQ
        val ratio = effA / effB
        val lockErr = minOf(
            abs(ratio - 1f),
            abs(ratio - 2f) / 2f,
            abs(ratio - 0.5f) * 2f
        )
        if (lockErr > 0.005f) return FADE_CUT_S to KIND_CUT
        val harmonic = MixEngine.camelotScore(current.track.camelot, next.camelot)
        if (harmonic >= 0.8f) return FADE_LOCKED_HARMONIC_S to KIND_HARMONIC

        val eOut = current.track.energyMean
        val eIn = next.energyMean
        val bright = current.track.centroid
        val pool: List<Pair<Double, Int>> = when {
            // Entrant calme : arrivée en douceur, sortant qui s'étouffe
            eIn > 0f && eIn < 0.10f -> listOf(
                12.0 to KIND_DARK,
                FADE_NORMAL_S to KIND_NORMAL,
                11.0 to KIND_EQ
            )
            // Deux morceaux énergiques : gestes francs, coupe écho permise
            eOut > 0.17f && eIn > 0.17f -> listOf(
                FADE_NORMAL_S to KIND_NORMAL,
                5.0 to KIND_CUT,
                8.0 to KIND_EQ
            )
            // Sortant brillant : l'étouffer (passe-bas) sonne naturel
            bright > 2_600f -> listOf(
                FADE_NORMAL_S to KIND_DARK,
                FADE_NORMAL_S to KIND_NORMAL,
                9.0 to KIND_EQ
            )
            else -> listOf(
                FADE_NORMAL_S to KIND_NORMAL,
                FADE_NORMAL_S to KIND_DARK,
                9.0 to KIND_EQ
            )
        }
        // Tirage stable par paire de morceaux, sans répéter la technique
        // de la transition précédente
        var idx = ((current.track.uri.hashCode() * 31 + next.uri.hashCode())
            ushr 1) % pool.size
        if (pool[idx].second == lastFadeKind) idx = (idx + 1) % pool.size
        return pool[idx]
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
