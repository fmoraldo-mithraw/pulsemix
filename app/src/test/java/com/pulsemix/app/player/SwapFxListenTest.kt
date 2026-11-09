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
 * charley tonal — aucun bruit dans les morceaux, pour que tout clic
 * ressorte) et passée au crible de critères d'écoute objectifs.
 *
 * Pour tous :
 *  1. AUCUN CLIC : pas de pic isolé dans la dérivée seconde du mélange
 *     (> [CLICK_FACTOR] × son niveau efficace local sur 10 ms) ;
 *  2. LE « 1 » EST TENU : l'entrant muet avant le « 1 », plein juste
 *     après ; le sortant muet 10 ms après sa coupure ;
 *  3. EFFETS ÉTEINTS avant la fin du fondu (le moteur jette alors SwapFx :
 *     un effet encore audible serait coupé net).
 *
 * Drop explosif (drop swap, roll, brake, spinback) — un drop frappe par
 * contraste :
 *  4. LA COUPURE S'ENTEND : sous −20 dB des morceaux sur au moins les
 *     deux tiers du silence qui précède le « 1 » (la queue de réverbe
 *     s'y éteint vite), et aucun autre trou de plus de 100 ms ;
 *  5. LA MONTÉE NE DÉBORDE PAS : sonie « momentary » (400 ms, EBU R128)
 *     jamais au-dessus de +3 dB du plus fort des deux morceaux avant le
 *     « 1 » (le riser porte la tension sans écraser) ;
 *  6. LE REDÉMARRAGE FRAPPE : les 400 ms après le « 1 » plus fortes que
 *     la suite du drop — +1 à +4,5 dB en intensité normale, au moins
 *     +0,5 dB en douce, au plus +6 dB en explosive ;
 *  7. LE LIMITEUR N'ÉCRASE PAS L'ATTAQUE : le limiteur du moteur, rejoué
 *     ici, ne retire pas plus de 1,5 dB de plus que sur l'entrant seul.
 *
 * Reverb wash (geste doux) : aucun trou de plus de 100 ms, aucune bosse
 * de plus de +3 dB.
 *
 * Chaque geste est rendu à 90, 128 et 174 BPM, et entre tempos non
 * calables pour wash, brake et spinback ; le drop explosif aux trois
 * intensités.
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
        val fadeStart: Int, val cut: Int, val swap: Int, val end: Int
    )

    private fun render(kind: Int, bpmA: Double, bpmB: Double, intensity: Int = 1): Render {
        val beat = sr * 60.0 / bpmA
        val bar = 4.0 * beat
        val swap = (8 * bar).toLong()
        val fadeStart = swap - (DjMixer.swapPreBars(kind) * bar).toLong()
        val end = swap + (DjMixer.swapTailBars(kind) * bar).toLong()
        val total = (end + sr).toInt()
        val fx = SwapFx(kind, fadeStart, swap, end, beat, intensity)
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
        return Render(
            mix, a, b, wet, gA, gB,
            fadeStart.toInt(), fx.cutF.toInt(), swap.toInt(), end.toInt()
        )
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

    /** Sonie « momentary » maximale (fenêtres de 400 ms, pas de 50 ms). */
    private fun maxMomentary(x: FloatArray, a: Int, b: Int): Double {
        val mwin = sr * 2 / 5
        var m = 0.0
        var k = a
        while (k + mwin <= b) {
            m = max(m, rms(x, k, k + mwin))
            k += sr / 20
        }
        return m
    }

    /** Le limiteur doux du moteur (DjMixer), rejoué : gain minimal
     *  appliqué sur [a, b). */
    private fun limiterMin(x: FloatArray, from: Int, a: Int, b: Int): Float {
        var g = 1f
        var worst = 1f
        for (k in from until b) {
            val peak = abs(x[k])
            if (peak * g > 0.98f) g = 0.98f / peak
            else g += (1f - g) * 0.0008f
            if (k >= a) worst = min(worst, g)
        }
        return worst
    }

    /** Plus long trou (fenêtres de 50 ms sous [floor]) sur [a, b), en ms. */
    private fun longestGapMs(x: FloatArray, a: Int, b: Int, floor: Double): Int {
        val win = sr / 20
        var gap = 0
        var worst = 0
        var k = a
        while (k + win <= b) {
            if (rms(x, k, k + win) < floor) gap++ else gap = 0
            worst = max(worst, gap)
            k += win
        }
        return worst * 50
    }

    private fun check(kind: Int, bpmA: Double, bpmB: Double, intensity: Int = 1) {
        val r = render(kind, bpmA, bpmB, intensity)
        val label = "${DjMixer.kindName(kind)} $bpmA→$bpmB BPM, intensité $intensity"
        val from = r.fadeStart - sr / 5
        val to = r.end + sr / 2
        // 1. Clics
        val click = worstClick(r.mix, from, to)
        val ref = max(worstClick(r.a, from, to), worstClick(r.b, r.swap + 10, to))
        assertTrue("$label : clic (rapport ${"%.1f".format(click)}, sources ${"%.1f".format(ref)})",
            click <= max(CLICK_FACTOR, ref * 1.5))
        // 2. Le « 1 »
        for (k in r.fadeStart until r.swap) {
            assertEquals("$label : entrant audible avant le 1", 0f, r.gB[k], 0f)
        }
        assertEquals("$label : sortant encore là 10 ms après sa coupure", 0f, r.gA[r.cut + 441], 1e-6f)
        if (kind != DjMixer.KIND_WASH) {
            assertEquals("$label : entrant pas plein sur le 1", 1f, r.gB[r.swap + 200], 1e-6f)
        }
        // 3. Effets éteints avant la fin du fondu
        var tail = 0f
        for (k in r.end - 220 until r.end) tail = max(tail, abs(r.wet[k]))
        assertTrue("$label : effet encore à $tail en fin de fondu", tail < 0.002f)

        val rmsA = rms(r.a, r.fadeStart - 2 * sr, r.fadeStart)
        val rmsB = rms(r.b, r.end, r.end + sr)
        val floor = 0.1 * min(rmsA, rmsB)
        val refLoud = max(
            maxMomentary(r.a, r.fadeStart - 3 * sr, r.fadeStart),
            maxMomentary(r.b, r.end, r.end + sr)
        )
        if (kind == DjMixer.KIND_WASH) {
            val gap = longestGapMs(r.mix, from, to, floor)
            assertTrue("$label : trou de $gap ms", gap <= MAX_GAP_MS)
            val bump = 20 * log10(maxMomentary(r.mix, from, to) / refLoud)
            assertTrue("$label : bosse de ${"%.1f".format(bump)} dB", bump <= MAX_BUMP_DB)
            return
        }
        // 4. La coupure s'entend, et c'est le seul trou
        val silenceFrom = r.cut + (r.swap - r.cut) / 3
        val inGap = rms(r.mix, silenceFrom, r.swap - 44)
        assertTrue(
            "$label : coupure pas assez nette (${"%.1f".format(20 * log10(inGap / min(rmsA, rmsB)))} dB)",
            inGap < floor
        )
        assertTrue("$label : silence trop court (${(r.swap - r.cut) * 1000 / sr} ms)",
            (r.swap - r.cut) * 1000 / sr >= 115)
        // (brake, spinback : leur extinction précède la coupure)
        val quietFrom = if (kind == DjMixer.KIND_BRAKE || kind == DjMixer.KIND_SPINBACK)
            r.cut - sr * 3 / 10 else r.cut
        val gapBefore = longestGapMs(r.mix, from, quietFrom, floor)
        val gapAfter = longestGapMs(r.mix, r.swap, to, floor)
        assertTrue("$label : autre trou de ${max(gapBefore, gapAfter)} ms",
            max(gapBefore, gapAfter) <= MAX_GAP_MS)
        // 5. La montée ne déborde pas
        val build = 20 * log10(maxMomentary(r.mix, from, r.cut) / refLoud)
        assertTrue("$label : montée à ${"%.1f".format(build)} dB", build <= MAX_BUMP_DB)
        // 6. Le redémarrage frappe
        val drop = rms(r.mix, r.swap, r.swap + sr * 2 / 5)
        val dropRef = maxMomentary(r.b, r.end, r.end + sr)
        val lift = 20 * log10(drop / dropRef)
        val (lo, hi) = when (intensity) {
            0 -> 0.5 to 4.5
            1 -> 1.0 to 4.5
            else -> 1.0 to 6.0
        }
        assertTrue("$label : redémarrage à ${"%+.1f".format(lift)} dB (attendu $lo..$hi)",
            lift in lo..hi)
        // 7. Le limiteur n'écrase pas l'attaque
        val withFx = limiterMin(r.mix, r.cut, r.swap, r.swap + sr * 2 / 5)
        val alone = limiterMin(r.b, r.swap, r.swap, r.swap + sr * 2 / 5)
        val squash = 20 * log10(alone / withFx)
        assertTrue("$label : limiteur −${"%.1f".format(squash)} dB sur l'attaque", squash <= 1.5)
    }

    private val tempos = listOf(90.0, 128.0, 174.0)

    @Test
    fun `drop swap - explosif a tous les tempos et toutes les intensites`() {
        for (t in tempos) for (lvl in 0..2) check(DjMixer.KIND_DROP, t, t, lvl)
    }

    @Test
    fun `loop roll - explosif a tous les tempos`() {
        for (t in tempos) for (lvl in 0..2) check(DjMixer.KIND_ROLL, t, t, lvl)
    }

    @Test
    fun `reverb wash - doux, tempos cales ou non`() {
        for (t in tempos) check(DjMixer.KIND_WASH, t, t)
        check(DjMixer.KIND_WASH, 128.0, 95.0)
    }

    @Test
    fun `brake - explosif, tempos cales ou non`() {
        for (t in tempos) check(DjMixer.KIND_BRAKE, t, t)
        check(DjMixer.KIND_BRAKE, 128.0, 95.0)
    }

    @Test
    fun `spinback - explosif, tempos cales ou non`() {
        for (t in tempos) check(DjMixer.KIND_SPINBACK, t, t)
        check(DjMixer.KIND_SPINBACK, 140.0, 100.0)
    }

    @Test
    fun `dropGapFrames - demi-temps en douce, un temps sinon, 120 a 600 ms`() {
        val beat128 = sr * 60.0 / 128
        assertEquals(10_335L, DjMixer.dropGapFrames(beat128, 0, 200_000L))
        assertEquals(20_671L, DjMixer.dropGapFrames(beat128, 1, 200_000L))
        assertEquals(sr * 6L / 10L, DjMixer.dropGapFrames(sr * 1.0, 2, 200_000L))
        assertEquals(sr * 12L / 100L, DjMixer.dropGapFrames(8_000.0, 0, 200_000L))
        assertEquals(5_000L, DjMixer.dropGapFrames(beat128, 1, 10_000L))
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
