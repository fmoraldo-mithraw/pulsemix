package com.pulsemix.app.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Traitement des gestes « sur le 1 » du mode pro (loop roll, reverb wash,
 * brake, spinback) — cf. [DjMixer.isSwapKind]. Une instance par
 * transition, allouée au moment de programmer le fondu (jamais dans la
 * boucle par bloc) ; [process] n'alloue rien.
 *
 * Code PUR (aucune dépendance Android) : la boucle audio du moteur et le
 * banc d'écoute des tests JVM (SwapFxListenTest) passent par lui — ce qui
 * est mesuré là-bas est exactement ce qui sort du téléphone.
 *
 * À chaque frame [gf] (horloge de sortie du moteur), [process] reçoit le
 * sortant brut et rend :
 *  - [outL]/[outR] : le sortant traité (boucle, frein, rembobinage…),
 *    AVANT son gain ;
 *  - [gA]/[gB] : gains du sortant et de l'entrant ;
 *  - [wetL]/[wetR] : la queue d'effet (écho, réverbe), déjà dosée, à
 *    ajouter au mélange.
 *
 * Toutes les queues s'éteignent avant [endF] : à la fin du fondu, le
 * moteur referme le sortant et jette cet objet — une queue encore
 * audible serait coupée net.
 */
internal class SwapFx(
    val kind: Int,
    val fadeStartF: Long,
    swapAt: Long,
    val endF: Long,
    beatFrames: Double
) {
    /** Le « 1 » : coupe du sortant, départ de l'entrant. Avancé par le
     *  moteur si le sortant se tarit avant l'heure ([advanceSwap]). */
    var swapF: Long = swapAt
        private set

    var outL = 0f
    var outR = 0f
    var gA = 1f
    var gB = 0f
    var wetL = 0f
    var wetR = 0f

    private val beat = if (beatFrames.isNaN() || beatFrames <= 0.0) 22_050.0
    else beatFrames.coerceIn(11_025.0, 44_100.0)
    private val beatI = beat.toInt()

    // Passe-bas one-pole des basses (< ~120 Hz) : retirées de la réverbe
    // du wash (un grave réverbéré bave sous les basses de l'entrant).
    private var lowL = 0f
    private var lowR = 0f
    // Passe-haut montant du roll (état one-pole du passe-bas soustrait)
    private var hpL = 0f
    private var hpR = 0f
    // Passe-bas de lissage du spinback (repliement de la relecture rapide)
    private var smL = 0f
    private var smR = 0f

    // ---- Loop roll : le premier temps capturé, son départ
    private val rollStart = max(fadeStartF, swapAt - (8.0 * beat).toLong())
    private val rollBuf = if (kind == DjMixer.KIND_ROLL) FloatArray(beatI * 2) else null
    // Queue d'écho d'un temps, nourrie par le dernier temps du roll
    private val echo = if (kind == DjMixer.KIND_ROLL) FloatArray(beatI * 2) else null
    private var echoPos = 0

    // ---- Brake / spinback : capture du sortant depuis le début du fondu,
    // relue à vitesse variable
    private val capLen = if (kind == DjMixer.KIND_BRAKE || kind == DjMixer.KIND_SPINBACK)
        (swapAt - fadeStartF).coerceIn(1L, MAX_CAPTURE) else 0L
    private val cap = if (capLen > 0L) FloatArray(capLen.toInt() * 2) else null
    private val gestureLen: Long = when (kind) {
        DjMixer.KIND_BRAKE -> min(DjMixer.brakeFrames(beat), capLen).coerceAtLeast(1L)
        DjMixer.KIND_SPINBACK -> min(DjMixer.spinbackFrames(beat), capLen).coerceAtLeast(1L)
        else -> 1L
    }
    private val gestureStart = swapAt - gestureLen
    // Spinback : vitesse arrière maximale, bornée par ce qui a été capturé
    // AVANT le geste (la relecture remonte dans le passé du sortant).
    private val spinMax: Double = if (kind == DjMixer.KIND_SPINBACK) {
        val history = (gestureStart - fadeStartF).toDouble().coerceAtLeast(0.0)
        DjMixer.spinbackMaxSpeed(history, gestureLen.toDouble())
    } else 0.0

    // ---- Reverb wash
    private val reverb = if (kind == DjMixer.KIND_WASH) WashReverb() else null

    /** Le sortant s'est tari avant l'heure : le « 1 » est maintenant. */
    fun advanceSwap(now: Long) {
        if (now < swapF) swapF = now
    }

    fun process(gf: Long, aL: Float, aR: Float) {
        outL = aL
        outR = aR
        wetL = 0f
        wetR = 0f
        val toSwap = gf - swapF
        gA = DjMixer.dropCutGainA(toSwap)
        gB = DjMixer.dropCutGainB(toSwap)
        lowL += LOW_ALPHA * (aL - lowL)
        lowR += LOW_ALPHA * (aR - lowR)
        when (kind) {
            DjMixer.KIND_ROLL -> roll(gf)
            DjMixer.KIND_BRAKE -> brake(gf, aL, aR)
            DjMixer.KIND_SPINBACK -> spinback(gf, aL, aR)
            DjMixer.KIND_WASH -> wash(gf)
        }
    }

    /** Extinction des queues : pleine jusqu'à mi-queue, puis cosinus
     *  jusqu'à zéro à [endF]. */
    private fun tailFade(gf: Long): Float {
        if (gf < swapF) return 1f
        val half = swapF + (endF - swapF) / 2
        if (gf < half) return 1f
        val t = (gf - half).toFloat() / (endF - half).coerceAtLeast(1L)
        return cos(t.coerceIn(0f, 1f) * HALF_PI)
    }

    private fun roll(gf: Long) {
        val rb = rollBuf ?: return
        if (gf >= rollStart) {
            val t = gf - rollStart
            if (t < beatI) {
                rb[t.toInt() * 2] = outL
                rb[t.toInt() * 2 + 1] = outR
                // Le direct s'efface sur ses dernières frames : la boucle
                // repart de zéro (fenêtre), sans saut.
                val w = min(1f, (beatI - 1 - t).toFloat() / DjMixer.ROLL_EDGE_FRAMES)
                outL *= w
                outR *= w
            } else {
                val len = DjMixer.rollLoopFrames((swapF - gf).toDouble() / beat, beatI)
                val pos = ((t % beatI) % len).toInt()
                val w = min(1f, min(pos, len - 1 - pos).toFloat() / DjMixer.ROLL_EDGE_FRAMES)
                outL = rb[pos * 2] * w
                outR = rb[pos * 2 + 1] * w
            }
        }
        // Passe-haut qui monte de 30 à 350 Hz jusqu'au « 1 » : la tension
        // monte, le grave se libère pour les basses de l'entrant.
        val pre = (swapF - fadeStartF).coerceAtLeast(1L)
        val fc = DjMixer.dropHpFc((gf - fadeStartF).toFloat() / pre)
        val alpha = 1f - exp(-2f * PI.toFloat() * fc / DjMixer.OUT_SR)
        hpL += alpha * (outL - hpL)
        hpR += alpha * (outR - hpR)
        outL -= hpL
        outR -= hpR
        // Écho d'un temps : nourri par le DERNIER temps du roll (les
        // huitièmes de temps), il tombe en rythme sur l'entrant.
        val eb = echo ?: return
        val eL = eb[echoPos * 2]
        val eR = eb[echoPos * 2 + 1]
        // Nourri en fenêtre (bords de ROLL_EDGE_FRAMES) : la boucle
        // d'écho se referme sur elle-même sans saut.
        val feed = if (gf in (swapF - beatI) until swapF) min(
            1f,
            min(gf - (swapF - beatI), swapF - 1 - gf).toFloat() / DjMixer.ROLL_EDGE_FRAMES
        ) * gA else 0f
        eb[echoPos * 2] = eL * DjMixer.ECHO_FEEDBACK + outL * feed
        eb[echoPos * 2 + 1] = eR * DjMixer.ECHO_FEEDBACK + outR * feed
        echoPos = if (echoPos + 1 >= beatI) 0 else echoPos + 1
        val wet = DjMixer.DROP_ECHO_WET * tailFade(gf)
        wetL = eL * wet
        wetR = eR * wet
    }

    /** Capture du sortant depuis le début du fondu, jusqu'au « 1 ». */
    private fun capture(gf: Long, aL: Float, aR: Float) {
        val c = cap ?: return
        val i = gf - fadeStartF
        if (i in 0 until capLen) {
            c[i.toInt() * 2] = aL
            c[i.toInt() * 2 + 1] = aR
        }
    }

    /** Lecture interpolée de la capture à la position [p] (frames depuis
     *  le début du fondu), bornée à ce qui a déjà été écrit ([written]). */
    private fun readCap(p: Double, written: Long) {
        val c = cap ?: return
        val last = (min(written, capLen) - 1L).coerceAtLeast(0L).toDouble()
        val q = p.coerceIn(0.0, last)
        val i0 = q.toInt()
        val i1 = min(i0 + 1, last.toInt())
        val fr = (q - i0).toFloat()
        outL = c[i0 * 2] + (c[i1 * 2] - c[i0 * 2]) * fr
        outR = c[i0 * 2 + 1] + (c[i1 * 2 + 1] - c[i0 * 2 + 1]) * fr
    }

    private fun brake(gf: Long, aL: Float, aR: Float) {
        capture(gf, aL, aR)
        if (gf < gestureStart) return
        // Vinyle qu'on stoppe : vitesse de 1 à 0, niveau en √vitesse.
        val t = gf - gestureStart
        val base = (gestureStart - fadeStartF).toDouble()
        readCap(base + DjMixer.brakeReadPos(t, gestureLen), gf - fadeStartF + 1)
        val env = DjMixer.brakeEnvelope(t.toFloat() / gestureLen)
        outL *= env
        outR *= env
    }

    private fun spinback(gf: Long, aL: Float, aR: Float) {
        capture(gf, aL, aR)
        // Le lissage suit le sortant dès le début du fondu : il n'a pas à
        // partir de zéro au moment du geste.
        if (gf < gestureStart) {
            smL += SPIN_SMOOTH * (aL - smL)
            smR += SPIN_SMOOTH * (aR - smR)
            return
        }
        // Le disque rattrapé et lancé en arrière : la relecture remonte
        // dans ce que le sortant vient de jouer, vite puis de moins en
        // moins (spinbackReadPos), en s'éteignant.
        val t = gf - gestureStart
        val base = (gestureStart - fadeStartF).toDouble()
        readCap(base + DjMixer.spinbackReadPos(t, gestureLen, spinMax), gf - fadeStartF + 1)
        // Relecture accélérée : passe-bas ~6 kHz contre le repliement,
        // amené en fondu pendant que le disque est rattrapé.
        smL += SPIN_SMOOTH * (outL - smL)
        smR += SPIN_SMOOTH * (outR - smR)
        val u = t.toFloat() / gestureLen
        val k = (u / DjMixer.SPIN_GRAB.toFloat()).coerceAtMost(1f)
        val env = DjMixer.spinbackEnvelope(u)
        outL = (outL + (smL - outL) * k) * env
        outR = (outR + (smR - outR) * k) * env
    }

    private fun wash(gf: Long) {
        val r = reverb ?: return
        val pre = (swapF - fadeStartF).coerceAtLeast(1L)
        // Envoi refermé avec le sortant sur le « 1 » (même rampe de
        // 10 ms) : un envoi coupé net claquerait dans la réverbe.
        val send = DjMixer.washSend(((gf - fadeStartF).toFloat() / pre).coerceAtMost(1f)) * gA
        r.process((outL - lowL) * send, (outR - lowR) * send, tmp)
        val wet = DjMixer.WASH_WET * tailFade(gf)
        wetL = tmp[0] * wet
        wetR = tmp[1] * wet
        // L'entrant arrive en un temps : la réverbe lui laisse la place
        val tb = (gf - swapF).toFloat() / beatI
        gB = if (tb <= 0f) 0f else sin(tb.coerceAtMost(1f) * HALF_PI)
    }

    private val tmp = FloatArray(2)

    private companion object {
        const val HALF_PI = 1.5707964f
        const val LOW_ALPHA = 0.017f
        /** ~6 kHz en one-pole à 44,1 kHz. */
        val SPIN_SMOOTH = 1f - exp(-2f * PI.toFloat() * 6_000f / 44_100f)
        /** Capture du brake / spinback : 9 s au plus (deux mesures à
         *  60 BPM, le tempo le plus lent admis). */
        const val MAX_CAPTURE = 44_100L * 9L
    }
}

/**
 * Réverbe du reverb wash ([DjMixer.KIND_WASH]) : un Freeverb réduit —
 * quatre filtres en peigne amortis en parallèle puis deux passe-tout en
 * série, par canal (le droit décalé de 23 échantillons pour l'ampleur
 * stéréo). Queue d'environ 1,5 s. [process] n'alloue rien.
 */
internal class WashReverb {
    private val combL = COMBS.map { FloatArray(it) }.toTypedArray()
    private val combR = COMBS.map { FloatArray(it + SPREAD) }.toTypedArray()
    private val combPos = IntArray(COMBS.size * 2)
    private val combLp = FloatArray(COMBS.size * 2)
    private val apL = ALLPASSES.map { FloatArray(it) }.toTypedArray()
    private val apR = ALLPASSES.map { FloatArray(it + SPREAD) }.toTypedArray()
    private val apPos = IntArray(ALLPASSES.size * 2)

    /** Une frame : entrée stéréo, sortie (humide seule) dans [out]. */
    fun process(inL: Float, inR: Float, out: FloatArray) {
        val input = (inL + inR) * INPUT_GAIN
        var l = 0f
        var r = 0f
        for (c in COMBS.indices) {
            l += comb(combL[c], c, input)
            r += comb(combR[c], c + COMBS.size, input)
        }
        for (k in ALLPASSES.indices) {
            l = allpass(apL[k], k, l)
            r = allpass(apR[k], k + ALLPASSES.size, r)
        }
        out[0] = l * OUT_GAIN
        out[1] = r * OUT_GAIN
    }

    private fun comb(buf: FloatArray, idx: Int, input: Float): Float {
        val p = combPos[idx]
        val y = buf[p]
        combLp[idx] = y * (1f - DAMP) + combLp[idx] * DAMP
        buf[p] = input + combLp[idx] * FEEDBACK
        combPos[idx] = if (p + 1 >= buf.size) 0 else p + 1
        return y
    }

    private fun allpass(buf: FloatArray, idx: Int, input: Float): Float {
        val p = apPos[idx]
        val b = buf[p]
        buf[p] = input + b * 0.5f
        apPos[idx] = if (p + 1 >= buf.size) 0 else p + 1
        return b - input
    }

    private companion object {
        val COMBS = intArrayOf(1116, 1277, 1422, 1557)
        val ALLPASSES = intArrayOf(556, 341)
        const val SPREAD = 23
        const val FEEDBACK = 0.88f
        const val DAMP = 0.25f
        const val INPUT_GAIN = 0.03f
        const val OUT_GAIN = 3f
    }
}
