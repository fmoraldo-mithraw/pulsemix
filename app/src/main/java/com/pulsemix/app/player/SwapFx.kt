package com.pulsemix.app.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Traitement des gestes « sur le 1 » du mode pro (drop swap, loop roll,
 * reverb wash, brake, spinback) — cf. [DjMixer.usesSwapFx]. Une instance
 * par transition, allouée au moment de programmer le fondu (jamais dans la
 * boucle par bloc) ; [process] n'alloue rien.
 *
 * Code PUR (aucune dépendance Android) : la boucle audio du moteur et le
 * banc d'écoute des tests JVM (SwapFxListenTest) passent par lui — ce qui
 * est mesuré là-bas est exactement ce qui sort du téléphone.
 *
 * DROP EXPLOSIF (tous les gestes sauf le wash) — un drop frappe par
 * CONTRASTE, pas par son volume :
 *  1. la montée : le sortant garde ses basses jusqu'à sa dernière mesure
 *     (passe-haut sur la seule dernière mesure) et un RISER — bruit filtré
 *     dont la bande et le niveau montent — porte la tension jusqu'au
 *     point d'orgue (pas de riser sous un brake : un frein dégonfle) ;
 *  2. le point d'orgue et la COUPURE : le sortant frappe son dernier
 *     coup (lancé dans une réverbe pour le drop swap et le roll), puis
 *     silence pendant [gapF] (½ à 1 temps), seule la queue de réverbe
 *     s'éteint dedans — l'oreille se recalibre sur le silence et le
 *     cerveau, qui attend le drop, le reçoit plus fort ;
 *  3. le « 1 » : l'entrant repart plein sur son drop, avec un IMPACT —
 *     infra-basse qui chute de 80 à 35 Hz et crash discret — et rien
 *     par-dessus (plus d'écho qui brouillait l'attaque).
 * L'intensité ([intensity] : 0 douce, 1 normale, 2 explosive) dose le
 * silence, le riser, la réverbe et l'impact, tous proportionnels au
 * niveau mesuré du sortant (les morceaux sont normalisés).
 *
 * À chaque frame [gf] (horloge de sortie du moteur), [process] reçoit le
 * sortant brut et rend :
 *  - [outL]/[outR] : le sortant traité (boucle, frein, rembobinage…),
 *    AVANT son gain ;
 *  - [gA]/[gB] : gains du sortant et de l'entrant ;
 *  - [wetL]/[wetR] : effets ajoutés (riser, réverbe, impact), déjà dosés,
 *    à ajouter au mélange.
 *
 * Tous les effets s'éteignent avant [endF] : à la fin du fondu, le
 * moteur referme le sortant et jette cet objet — un effet encore audible
 * serait coupé net.
 */
internal class SwapFx(
    val kind: Int,
    val fadeStartF: Long,
    swapAt: Long,
    val endF: Long,
    beatFrames: Double,
    intensity: Int = 1
) {
    /** Le « 1 » : départ de l'entrant. Avancé par le moteur si le
     *  sortant se tarit avant l'heure ([advanceSwap]). */
    var swapF: Long = swapAt
        private set

    var outL = 0f
    var outR = 0f
    var gA = 1f
    var gB = 0f
    var wetL = 0f
    var wetR = 0f

    private val level = intensity.coerceIn(0, 2)
    private val beat = if (beatFrames.isNaN() || beatFrames <= 0.0) 22_050.0
    else beatFrames.coerceIn(11_025.0, 44_100.0)
    private val beatI = beat.toInt()
    private val barF = (4.0 * beat).toLong()

    /** Geste explosif (silence, impact) : tous sauf le wash. */
    private val punch = kind != DjMixer.KIND_WASH
    private val riserOn = punch && kind != DjMixer.KIND_BRAKE
    private val throwOn = kind == DjMixer.KIND_DROP || kind == DjMixer.KIND_ROLL

    /** Durée du silence avant le « 1 ». */
    val gapF: Long = if (punch) DjMixer.dropGapFrames(beat, level, swapAt - fadeStartF) else 0L

    /** La coupure du sortant : le « 1 » moins le silence. */
    var cutF: Long = swapAt - gapF
        private set

    // Passe-bas one-pole des basses (< ~120 Hz) : retirées des réverbes
    // (un grave réverbéré bave sous les basses de l'entrant).
    private var lowL = 0f
    private var lowR = 0f
    // Passe-haut de la dernière mesure (état one-pole du passe-bas soustrait)
    private var hpL = 0f
    private var hpR = 0f
    // Passe-bas de lissage du spinback (repliement de la relecture rapide)
    private var smL = 0f
    private var smR = 0f
    // Niveau mesuré du sortant (moyenne quadratique depuis le début du
    // fondu jusqu'à la coupure) : référence de dosage des effets.
    private var sumSq = 0.0
    private var nSq = 0L
    private var refRms = 0f

    // ---- Loop roll : le premier temps capturé, son départ
    private val rollStart = max(fadeStartF, cutF - (8.0 * beat).toLong())
    private val rollBuf = if (kind == DjMixer.KIND_ROLL) FloatArray(beatI * 2) else null
    // Temps entiers de roll avant la coupure : les boucles changent de
    // longueur sur ces temps-là, là où elles repartent de zéro.
    private val rollBeats = ((cutF - rollStart) / beatI).toInt().coerceAtLeast(1)

    // ---- Brake / spinback : capture du sortant depuis le début du fondu,
    // relue à vitesse variable ; le geste finit sur la coupure.
    private val capLen = if (kind == DjMixer.KIND_BRAKE || kind == DjMixer.KIND_SPINBACK)
        (cutF - fadeStartF).coerceIn(1L, MAX_CAPTURE) else 0L
    private val cap = if (capLen > 0L) FloatArray(capLen.toInt() * 2) else null
    private val gestureLen: Long = when (kind) {
        DjMixer.KIND_BRAKE -> min(DjMixer.brakeFrames(beat), capLen).coerceAtLeast(1L)
        DjMixer.KIND_SPINBACK -> min(DjMixer.spinbackFrames(beat), capLen).coerceAtLeast(1L)
        else -> 1L
    }
    private val gestureStart = cutF - gestureLen
    // Spinback : vitesse arrière maximale, bornée par ce qui a été capturé
    // AVANT le geste (la relecture remonte dans le passé du sortant).
    private val spinMax: Double = if (kind == DjMixer.KIND_SPINBACK) {
        val history = (gestureStart - fadeStartF).toDouble().coerceAtLeast(0.0)
        DjMixer.spinbackMaxSpeed(history, gestureLen.toDouble())
    } else 0.0

    // ---- Réverbe : le wash, ou le dernier coup du drop swap / roll
    private val reverb = if (kind == DjMixer.KIND_WASH || throwOn) WashReverb() else null
    private val tmp = FloatArray(2)

    // ---- Riser : bruit (xorshift, un par canal) dans un passe-bande
    // d'état variable qui monte ; niveau auto-normalisé.
    private var seedL = 0x1234567
    private var seedR = 0x7654321
    private var svLowL = 0f
    private var svBandL = 0f
    private var svLowR = 0f
    private var svBandR = 0f
    private var riserMs = 1e-4f

    // ---- Impact : phase de l'infra-basse, passe-bas du crash
    private var subPhase = 0.0
    private var crashLpL = 0f
    private var crashLpR = 0f

    /** Le sortant s'est tari avant l'heure : le « 1 » est maintenant (la
     *  coupure aussi, si elle n'est pas déjà passée). */
    fun advanceSwap(now: Long) {
        if (now < swapF) swapF = now
        if (now < cutF) cutF = now
    }

    fun process(gf: Long, aL: Float, aR: Float) {
        outL = aL
        outR = aR
        wetL = 0f
        wetR = 0f
        gA = DjMixer.dropCutGainA(gf - cutF)
        gB = DjMixer.dropCutGainB(gf - swapF)
        lowL += LOW_ALPHA * (aL - lowL)
        lowR += LOW_ALPHA * (aR - lowR)
        if (gf < cutF) {
            sumSq += (aL * aL + aR * aR) * 0.5
            nSq++
            refRms = kotlin.math.sqrt(sumSq / nSq).toFloat()
        }
        when (kind) {
            DjMixer.KIND_DROP -> lastBarHp(gf)
            DjMixer.KIND_ROLL -> {
                roll(gf)
                lastBarHp(gf)
            }
            DjMixer.KIND_BRAKE -> brake(gf, aL, aR)
            DjMixer.KIND_SPINBACK -> spinback(gf, aL, aR)
            DjMixer.KIND_WASH -> wash(gf)
        }
        if (throwOn) throwReverb(gf)
        if (riserOn && gf < cutF + DjMixer.DROP_CUT_A_FRAMES) riser(gf)
        if (punch && gf >= swapF) impact(gf)
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

    /** Passe-haut montant (30 → 250 Hz) sur la SEULE dernière mesure avant
     *  la coupure : la montée garde son corps jusque-là, puis le grave se
     *  libère pour les basses de l'entrant. */
    private fun lastBarHp(gf: Long) {
        val from = cutF - barF
        if (gf < from) return
        val fc = DjMixer.dropHpFc((gf - from).toFloat() / barF.coerceAtLeast(1L))
        val alpha = 1f - exp(-2f * PI.toFloat() * fc / DjMixer.OUT_SR)
        hpL += alpha * (outL - hpL)
        hpR += alpha * (outR - hpR)
        outL -= hpL
        outR -= hpR
    }

    private fun roll(gf: Long) {
        val rb = rollBuf ?: return
        if (gf < rollStart) return
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
            val left = rollBeats - (t / beatI).toInt()
            val len = DjMixer.rollLoopFrames(left.toDouble(), beatI)
            val pos = ((t % beatI) % len).toInt()
            val w = min(1f, min(pos, len - 1 - pos).toFloat() / DjMixer.ROLL_EDGE_FRAMES)
            outL = rb[pos * 2] * w
            outR = rb[pos * 2 + 1] * w
        }
    }

    /** Le dernier coup du sortant lancé dans la réverbe : envoi qui monte
     *  sur le dernier temps, fermé avec lui à la coupure ; la queue
     *  s'éteint vite dans le silence (le silence doit S'ENTENDRE) et plus
     *  rien sur le « 1 ». */
    private fun throwReverb(gf: Long) {
        val r = reverb ?: return
        val t = (gf - (cutF - beatI)).toFloat() / beatI
        val send = if (t <= 0f) 0f else t.coerceAtMost(1f).pow(2f) * gA
        r.process((outL - lowL) * send, (outR - lowR) * send, tmp)
        val wet = THROW_WET[level] * when {
            gf < cutF -> 1f
            gf >= swapF -> 0f
            else -> (1f - (gf - cutF).toFloat() / (swapF - cutF).coerceAtLeast(1L)).pow(3f)
        }
        wetL += tmp[0] * wet
        wetR += tmp[1] * wet
    }

    private fun noise(l: Boolean): Float {
        var x = if (l) seedL else seedR
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        if (l) seedL = x else seedR = x
        return x.toFloat() / Int.MAX_VALUE
    }

    /** Riser : bruit dans un passe-bande qui monte de 300 Hz à 6 kHz sur
     *  la montée, niveau en t² jusqu'à [RISER_LEVEL] × le niveau du
     *  sortant au point d'orgue, coupé avec lui. */
    private fun riser(gf: Long) {
        val pre = (cutF - fadeStartF).coerceAtLeast(1L)
        val p = ((gf - fadeStartF).toFloat() / pre).coerceIn(0f, 1f)
        val fc = 300f * 20f.pow(p.pow(1.5f))
        val f = 2f * sin(PI.toFloat() * fc / DjMixer.OUT_SR)
        val nl = noise(true)
        val nr = noise(false)
        val hiL = nl - svLowL - RISER_DAMP * svBandL
        svBandL += f * hiL
        svLowL += f * svBandL
        val hiR = nr - svLowR - RISER_DAMP * svBandR
        svBandR += f * hiR
        svLowR += f * svBandR
        riserMs += 0.002f * ((svBandL * svBandL + svBandR * svBandR) * 0.5f - riserMs)
        val norm = (1f / kotlin.math.sqrt(riserMs)).coerceAtMost(8f)
        val g = RISER_LEVEL[level] * refRms * p * p * gA * norm
        wetL += svBandL * g
        wetR += svBandR * g
    }

    /** Impact sur le « 1 » : infra-basse qui chute de 80 à 35 Hz (attaque
     *  2 ms) et crash (bruit au-dessus de ~4 kHz), proportionnels au niveau
     *  du sortant, éteints avant la fin du fondu. */
    private fun impact(gf: Long) {
        val ti = (gf - swapF).toDouble() / DjMixer.OUT_SR
        val fade = tailFade(gf)
        val freq = 35.0 + 45.0 * exp(-ti / 0.06)
        subPhase += 2.0 * PI * freq / DjMixer.OUT_SR
        val atk = min(1.0, ti / 0.002)
        val sub = (SUB_LEVEL[level] * refRms * 1.41f) *
            (atk * exp(-ti / SUB_TAU) * sin(subPhase)).toFloat() * fade
        val nl = noise(true)
        val nr = noise(false)
        crashLpL += CRASH_ALPHA * (nl - crashLpL)
        crashLpR += CRASH_ALPHA * (nr - crashLpR)
        val cenv = (min(1.0, ti / 0.001) * exp(-ti / 0.45)).toFloat() *
            CRASH_LEVEL[level] * refRms * 1.7f * fade
        wetL += sub + (nl - crashLpL) * cenv
        wetR += sub + (nr - crashLpR) * cenv
    }

    /** Capture du sortant depuis le début du fondu, jusqu'à la coupure. */
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

    private companion object {
        const val HALF_PI = 1.5707964f
        const val LOW_ALPHA = 0.017f
        /** ~6 kHz en one-pole à 44,1 kHz. */
        val SPIN_SMOOTH = 1f - exp(-2f * PI.toFloat() * 6_000f / 44_100f)
        /** ~4 kHz : ce qui reste du bruit au-dessus fait le crash. */
        val CRASH_ALPHA = 1f - exp(-2f * PI.toFloat() * 4_000f / 44_100f)
        /** Amortissement du passe-bande du riser (1/Q). */
        const val RISER_DAMP = 0.7f
        /** Capture du brake / spinback : 9 s au plus (deux mesures à
         *  60 BPM, le tempo le plus lent admis). */
        const val MAX_CAPTURE = 44_100L * 9L
        // Dosages par intensité (douce, normale, explosive), relatifs au
        // niveau efficace du sortant
        val RISER_LEVEL = floatArrayOf(0.25f, 0.4f, 0.6f)
        val THROW_WET = floatArrayOf(0.35f, 0.5f, 0.6f)
        val SUB_LEVEL = floatArrayOf(1.0f, 1.8f, 2.6f)
        val CRASH_LEVEL = floatArrayOf(0.2f, 0.3f, 0.45f)
        /** Décroissance de l'infra-basse (s). */
        const val SUB_TAU = 0.3
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
