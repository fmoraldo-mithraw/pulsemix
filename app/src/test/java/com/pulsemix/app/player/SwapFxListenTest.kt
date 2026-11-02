package com.pulsemix.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Banc d'écoute des gestes « sur le 1 » ([SwapFx]) : chaque transition est
 * rendue entre deux morceaux synthétiques (kick, basse, accord tenu,
 * charley tonal — aucun bruit, pour que tout clic ressorte) et passée au
 * crible de critères d'écoute objectifs :
 *
 *  1. AUCUN CLIC : pas de pic isolé dans la dérivée seconde du mélange
 *     (> [CLICK_FACTOR] × son niveau efficace local sur 10 ms) — un
 *     saut d'échantillon s'entend comme un claquement ;
 *  2. PAS DE TROU : jamais plus de [MAX_GAP_MS] sous −20 dB du plus
 *     calme des deux morceaux — un blanc casse le groove ;
 *  3. PAS DE BOSSE : jamais plus de +[MAX_BUMP_DB] dB au-dessus du plus
 *     fort des deux (fenêtres de 50 ms) — deux morceaux pleins qui
 *     s'additionnent, ou une queue d'effet trop forte ;
 *  4. LE « 1 » EST TENU : l'entrant muet avant le « 1 », plein juste
 *     après ; le sortant muet 10 ms après ;
 *  5. QUEUE PROPRE : l'effet éteint avant la fin du fondu (le moteur le
 *     jette à ce moment-là : une queue encore audible serait coupée net).
 *
 * Chaque geste est rendu à 90, 128 et 174 BPM (et le brake / spinback /
 * wash aussi entre tempos non calables).
 */
class SwapFxListenTest {

    private val sr = DjMixer.OUT_SR

    /** Morceau synthétique au tempo [bpm], fondamentale [root] Hz :
     *  kick sur les temps, basse sur les contretemps, accord tenu,
     *  charley tonal (3 kHz) sur les doubles-croches paires. */
    private fun synth(f: Long, bpm: Double, root: Double): Float {
        if (f < 0L) return 0f
        val t = f.toDouble() / sr
        val beat = 60.0 / bpm
        val tb = t % beat
        // Kick : sinus en glissando 150 → 50 Hz, attaque 2 ms
        val kAtk = min(1.0, tb / 0.002)
        val kPhase = 2 * PI * (50.0 * tb + 100.0 * 0.03 * (1 - exp(-tb / 0.03)))
        // (éteint sur les 3 dernières ms du temps : le banc ne doit pas
        // fabriquer ses propres clics)
        val kEnd = min(1.0, (beat - tb) / 0.003)
        val kick = 0.45 * kAtk * kEnd * exp(-tb / 0.15) * sin(kPhase)
        // Basse : contretemps, attaque 5 ms
        val half = beat / 2
        val tbs = (t + half) % beat
        val bass = 0.2 * min(1.0, tbs / 0.005) * min(1.0, (beat - tbs) / 0.003) *
            exp(-tbs / 0.2) *
            sin(2 * PI * root / 2 * t)
        // Accord tenu (fondamentale, tierce, quinte)
        val pad = 0.05 * (sin(2 * PI * root * 2 * t) + sin(2 * PI * root * 2.52 * t) +
            sin(2 * PI * root * 3 * t))
        // Charley tonal sur les croches
        val th = t % half
        val hat = 0.04 * min(1.0, th / 0.001) * min(1.0, (half - th) / 0.002) *
            exp(-th / 0.03) * sin(2 * PI * 3_000.0 * t)
        return (kick + bass + pad + hat).toFloat()
    }

    private class Render(
        val mix: FloatArray, val a: FloatArray, val b: FloatArray, val wet: FloatArray,
        val gA: FloatArray, val gB: FloatArray,
        val fadeStart: Int, val swap: Int, val end: Int
    )

    private fun render(kind: Int, bpmA: Double, bpmB: Double): Render {
        val beat = sr * 60.0 / bpmA
        val bar = 4.0 * beat
        val swap = (8 * bar).toLong()
        val fadeStart = swap - (DjMixer.swapPreBars(kind) * bar).toLong()
        val end = swap + (DjMixer.swapTailBars(kind) * bar).toLong()
        val total = (end + sr).toInt()
        val fx = SwapFx(kind, fadeStart, swap, end, beat)
        val mix = FloatArray(total)
        val a = FloatArray(total)
        val b = FloatArray(total)
        val wet = FloatArray(total)
        val gA = FloatArray(total)
        val gB = FloatArray(total)
        for (i in 0 until total) {
            val gf = i.toLong()
            val av = synth(gf, bpmA, 110.0)
            val bv = if (gf >= swap) synth(gf - swap, bpmB, 130.81) else 0f
            a[i] = av
            b[i] = synth(gf - swap, bpmB, 130.81)
            mix[i] = when {
                gf < fadeStart -> av.also { gA[i] = 1f }
                gf >= end -> bv.also { gB[i] = 1f }
                else -> {
                    fx.process(gf, av, av)
                    gA[i] = fx.gA
                    gB[i] = fx.gB
                    wet[i] = fx.wetL
                    fx.outL * fx.gA + bv * fx.gB + fx.wetL
                }
            }
        }
        return Render(mix, a, b, wet, gA, gB, fadeStart.toInt(), swap.toInt(), end.toInt())
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i].toDouble()
        return sqrt(s / max(1, to - from))
    }

    /** Clics : pics isolés de la dérivée seconde, comparés à son niveau
     *  efficace local (10 ms autour). Rend le pire rapport trouvé. */
    private fun worstClick(x: FloatArray, from: Int, to: Int): Double {
        val n = to - from
        val d2 = DoubleArray(n)
        for (k in 0 until n) {
            val i = from + k
            d2[k] = (x[i + 1] - 2.0 * x[i] + x[i - 1])
        }
        val pre = DoubleArray(n + 1)
        for (k in 0 until n) pre[k + 1] = pre[k] + d2[k] * d2[k]
        val w = 220
        var worst = 0.0
        for (k in 0 until n) {
            val lo = max(0, k - w)
            val hi = min(n, k + w + 1)
            val local = sqrt((pre[hi] - pre[lo] - d2[k] * d2[k]) / (hi - lo - 1))
            val r = abs(d2[k]) / (local + 2e-4)
            if (r > worst) worst = r
        }
        return worst
    }

    private fun check(kind: Int, bpmA: Double, bpmB: Double) {
        val r = render(kind, bpmA, bpmB)
        val label = "${DjMixer.kindName(kind)} $bpmA→$bpmB BPM"
        val from = r.fadeStart - sr / 5
        val to = r.end + sr / 2
        // 1. Clics
        val click = worstClick(r.mix, from, to)
        val ref = max(worstClick(r.a, from, to), worstClick(r.b, r.swap + 10, to))
        assertTrue("$label : clic (rapport ${"%.1f".format(click)}, sources ${"%.1f".format(ref)})",
            click <= max(CLICK_FACTOR, ref * 1.5))
        // 2. Trous : fenêtres de 50 ms, sous −20 dB du niveau moyen du
        // plus calme des deux morceaux
        val rmsA = rms(r.a, r.fadeStart - 2 * sr, r.fadeStart)
        val rmsB = rms(r.b, r.end, r.end + sr)
        val win = sr / 20
        var gap = 0
        var worstGap = 0
        var i = from
        while (i + win <= to) {
            val v = rms(r.mix, i, i + win)
            if (v < 0.1 * min(rmsA, rmsB)) gap++ else gap = 0
            worstGap = max(worstGap, gap)
            i += win
        }
        // 3. Bosses : sonie « momentary » (fenêtres de 400 ms, comme
        // EBU R128) comparée au maximum momentary des deux morceaux seuls
        val mwin = sr * 2 / 5
        val hop = sr / 20
        fun maxMomentary(x: FloatArray, a: Int, b: Int): Double {
            var m = 0.0
            var k = a
            while (k + mwin <= b) {
                m = max(m, rms(x, k, k + mwin))
                k += hop
            }
            return m
        }
        val refLoud = max(
            maxMomentary(r.a, r.fadeStart - 3 * sr, r.fadeStart),
            maxMomentary(r.b, r.end, r.end + sr)
        )
        val worstBump = 20 * log10(maxMomentary(r.mix, from, to) / refLoud)
        assertTrue("$label : trou de ${worstGap * 50} ms", worstGap * 50 <= MAX_GAP_MS)
        assertTrue("$label : bosse de ${"%.1f".format(worstBump)} dB", worstBump <= MAX_BUMP_DB)
        // 4. Le « 1 »
        for (k in r.fadeStart until r.swap) {
            assertEquals("$label : entrant audible avant le 1", 0f, r.gB[k], 0f)
        }
        assertEquals("$label : sortant encore là 10 ms après le 1", 0f, r.gA[r.swap + 441], 1e-6f)
        if (kind != DjMixer.KIND_WASH) {
            assertEquals("$label : entrant pas plein sur le 1", 1f, r.gB[r.swap + 200], 1e-6f)
        }
        // 5. Queue éteinte avant la fin du fondu
        var tail = 0f
        for (k in r.end - 220 until r.end) tail = max(tail, abs(r.wet[k]))
        assertTrue("$label : queue encore à $tail en fin de fondu", tail < 0.002f)
    }

    private val tempos = listOf(90.0, 128.0, 174.0)

    @Test
    fun `loop roll - propre a tous les tempos`() {
        for (t in tempos) check(DjMixer.KIND_ROLL, t, t)
    }

    @Test
    fun `reverb wash - propre, tempos cales ou non`() {
        for (t in tempos) check(DjMixer.KIND_WASH, t, t)
        check(DjMixer.KIND_WASH, 128.0, 95.0)
    }

    @Test
    fun `brake - propre, tempos cales ou non`() {
        for (t in tempos) check(DjMixer.KIND_BRAKE, t, t)
        check(DjMixer.KIND_BRAKE, 128.0, 95.0)
    }

    @Test
    fun `spinback - propre, tempos cales ou non`() {
        for (t in tempos) check(DjMixer.KIND_SPINBACK, t, t)
        check(DjMixer.KIND_SPINBACK, 140.0, 100.0)
    }

    @Test
    fun `spinback - la relecture remonte le temps sans jamais sortir de la capture`() {
        val len = 40_000L
        val history = 60_000.0
        val vmax = DjMixer.spinbackMaxSpeed(history, len.toDouble())
        var minPos = 0.0
        var prev = DjMixer.spinbackReadPos(0L, len, vmax)
        var t = 1L
        while (t <= len) {
            val p = DjMixer.spinbackReadPos(t, len, vmax)
            assertTrue("en avance sur la capture à $t", p <= t.toDouble())
            // Continue : jamais plus de vmax frames par frame
            assertTrue("saut de position à $t", abs(p - prev) <= vmax + 1e-6)
            minPos = min(minPos, p)
            prev = p
            t++
        }
        assertTrue("remonte trop loin : $minPos", -minPos <= history)
        assertTrue("ne remonte pas : $minPos", -minPos > len * 0.5)
        // Peu d'histoire : vitesse réduite, toujours dans la capture
        val v2 = DjMixer.spinbackMaxSpeed(5_000.0, len.toDouble())
        assertTrue(v2 >= 0.5 && v2 < vmax)
    }

    private companion object {
        const val CLICK_FACTOR = 5.0
        const val MAX_GAP_MS = 100
        const val MAX_BUMP_DB = 3.0
    }
}
