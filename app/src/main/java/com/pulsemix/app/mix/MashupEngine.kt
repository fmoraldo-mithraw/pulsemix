package com.pulsemix.app.mix

import com.pulsemix.app.analysis.StructureDetector
import com.pulsemix.app.data.Track
import com.pulsemix.app.player.DjMixer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mashup « deux platines » : deux morceaux compatibles (tempo à ±8 %,
 * tonalités voisines sur la roue de Camelot) joués EN MÊME TEMPS, calés
 * sur un tempo commun et sur leurs phrases, séparés par un filtre — les
 * basses de l'un sous les voix et mélodies de l'autre, puis les rôles
 * s'échangent. C'est la technique classique du mashup DJ à deux decks,
 * sans pistes séparées : le partage se fait au crossover (~300 Hz).
 *
 * Forme du rendu, en mesures au tempo commun :
 *
 *     A seul   | A basses + B aigus | A aigus + B basses | B seul (fondu)
 *     8 mes.   |      L mes.        |       L mes.       |   8 mes.
 *
 * (L = 8 à 16 mesures, selon ce que chaque morceau a devant lui.) Chaque
 * morceau part de son ANCRE (le « 1 » de son drop, ou le premier beat de
 * son meilleur passage — même repère que le moteur DJ), B entrant sur une
 * frontière de phrase de A. Les changements de rôle se croisent sur une
 * mesure. Le tempo est le moyen géométrique des deux ; chaque morceau est
 * étiré SANS changer sa hauteur (atempo), pour que la compatibilité de
 * tonalité tienne.
 *
 * Tout ici est PUR (testé en JVM) ; le rendu ffmpeg est dans
 * [com.pulsemix.app.library.MashupRenderer].
 */
object MashupEngine {

    /** Écart de tempo maximal (%) entre les deux morceaux, octave près. */
    const val MAX_TEMPO_PCT = 8f

    /** Fréquence de partage basses / aigus (Hz). */
    const val CROSSOVER_HZ = 300

    const val INTRO_BARS = 8
    const val OUTRO_BARS = 8
    const val MIN_PART_BARS = 8
    const val MAX_PART_BARS = 16

    /** Longueur du fondu de fin (mesures). */
    const val END_FADE_BARS = 2

    /**
     * Calage de tempo : [target] le tempo commun (moyenne géométrique),
     * [rateA]/[rateB] les facteurs d'étirement de chaque morceau,
     * [factorB] l'octave retenue pour B (1, 2 ou ½ : un morceau à 64 BPM
     * se cale sur un 128 en jouant ses temps un sur deux).
     */
    data class Tempo(
        val target: Float,
        val rateA: Float,
        val rateB: Float,
        val factorB: Float
    ) {
        /** Écart de tempo (%) entre A et B après choix de l'octave. */
        val deviationPct: Float get() = abs(rateA / rateB - 1f) * 100f
    }

    /** Tempo commun de deux morceaux, ou null s'ils sont trop éloignés. */
    fun tempoMatch(bpmA: Float, bpmB: Float): Tempo? {
        if (bpmA <= 0f || bpmB <= 0f) return null
        var best: Tempo? = null
        var bestDev = Float.MAX_VALUE
        for (k in floatArrayOf(1f, 2f, 0.5f)) {
            val b = bpmB * k
            val dev = abs(bpmA / b - 1f)
            if (dev < bestDev) {
                bestDev = dev
                val t = sqrt(bpmA * b)
                best = Tempo(t, t / bpmA, t / b, k)
            }
        }
        return if (bestDev * 100f <= MAX_TEMPO_PCT) best else null
    }

    /**
     * Compatibilité de tonalité pour une SUPERPOSITION (plus stricte que
     * pour un enchaînement) : même clé 1,0 ; relative majeure/mineure 0,9 ;
     * voisine à ±1 sur la roue (une quinte) 0,7 ; sinon 0 (exclu).
     */
    fun keyScore(a: String, b: String): Float {
        if (a.length < 2 || b.length < 2 || a == "--" || b == "--") return 0f
        val na = a.dropLast(1).toIntOrNull() ?: return 0f
        val nb = b.dropLast(1).toIntOrNull() ?: return 0f
        val la = a.last()
        val lb = b.last()
        if (na == nb) return if (la == lb) 1f else 0.9f
        val diff = min((na - nb + 12) % 12, (nb - na + 12) % 12)
        return if (diff == 1 && la == lb) 0.7f else 0f
    }

    /** Partenaire proposé pour un mashup avec le morceau de base. */
    data class Candidate(
        val track: Track,
        /** 0..1, pour le tri et l'affichage. */
        val score: Float,
        val tempo: Tempo,
        val keyScore: Float,
        /** Vrai : le morceau de base tient les AIGUS (voix) dans la
         *  première moitié, le partenaire les basses. */
        val baseTopFirst: Boolean,
        /** Longueur L de chaque moitié (mesures). */
        val partBars: Int
    )

    /** Ancre d'un morceau (même repère que le moteur DJ). */
    fun anchorMs(t: Track): Long {
        val best = t.bestStartMs.coerceIn(0L, maxOf(0L, t.durationMs - 15_000L))
        val sections = if (t.structure.isEmpty()) emptyList()
        else StructureDetector.decode(t.structure)
        return DjMixer.anchorFor(
            best, t.segmentMs, t.firstBeatMs, t.bpm, t.durationMs, sections
        )
    }

    /** Mesures (au tempo commun) que [t] a devant lui après son ancre,
     *  une fois étiré de [rate]. */
    fun availableBars(t: Track, rate: Float, targetBpm: Float): Int {
        val end = if (t.musicEndMs > 0L) t.musicEndMs else t.durationMs
        val outS = (end - anchorMs(t)).coerceAtLeast(0L) / 1000.0 / rate
        return (outS / DjMixer.barSeconds(targetBpm)).toInt()
    }

    /** Longueur de chaque moitié, ou 0 si l'un des deux manque de matière. */
    fun partBars(availA: Int, availB: Int): Int {
        val room = min(availA, availB) - INTRO_BARS
        val l = (room / 2 / 4) * 4
        return if (l < MIN_PART_BARS) 0 else min(l, MAX_PART_BARS)
    }

    /**
     * Partenaires compatibles de [base] dans [library], du meilleur au
     * moins bon. Il faut, des deux côtés, une analyse faite, un tempo, une
     * tonalité, et de quoi tenir la forme du mashup.
     */
    fun candidates(base: Track, library: List<Track>, limit: Int = 12): List<Candidate> {
        if (!base.analyzed || base.bpm <= 0f) return emptyList()
        val out = ArrayList<Candidate>()
        for (t in library) {
            if (t.uri == base.uri || !t.analyzed || t.excluded || t.bpm <= 0f) continue
            val tempo = tempoMatch(base.bpm, t.bpm) ?: continue
            val key = keyScore(base.camelot, t.camelot)
            if (key <= 0f) continue
            val l = partBars(
                availableBars(base, tempo.rateA, tempo.target),
                availableBars(t, tempo.rateB, tempo.target)
            )
            if (l == 0) continue
            // Complémentarité : le morceau le plus « voix » (énergie dans
            // les bas-médiums) prend les aigus, l'autre les basses.
            val voiceGap = t.lowMidRatio - base.lowMidRatio
            val baseTopFirst = voiceGap < -0.05f
            val energyMax = maxOf(base.energyMean, t.energyMean, 1e-3f)
            val energySim = 1f - abs(base.energyMean - t.energyMean) / energyMax
            val score = (0.45f * key +
                0.30f * (1f - tempo.deviationPct / MAX_TEMPO_PCT) +
                0.15f * energySim.coerceIn(0f, 1f) +
                0.10f * abs(voiceGap).coerceAtMost(0.3f) / 0.3f +
                (if (t.favorite) 0.05f else 0f)).coerceIn(0f, 1f)
            out.add(Candidate(t, score, tempo, key, baseTopFirst, l))
        }
        return out.sortedByDescending { it.score }.take(limit)
    }

    /** Plan de rendu : qui joue quoi, quand, à quelle vitesse. */
    data class Plan(
        val base: Track,
        val partner: Track,
        val tempo: Tempo,
        val partBars: Int,
        val baseTopFirst: Boolean,
        val anchorAMs: Long,
        val anchorBMs: Long
    ) {
        val totalBars: Int get() = INTRO_BARS + 2 * partBars + OUTRO_BARS
        val barSeconds: Double get() = DjMixer.barSeconds(tempo.target)
        val durationSeconds: Double get() = totalBars * barSeconds

        /** Secondes de SOURCE à lire pour A (étiré de rateA), marge comprise. */
        val sourceSecondsA: Double
            get() = (INTRO_BARS + 2 * partBars + 1) * barSeconds * tempo.rateA

        /** Secondes de SOURCE à lire pour B. */
        val sourceSecondsB: Double
            get() = (2 * partBars + OUTRO_BARS + 1) * barSeconds * tempo.rateB
    }

    fun plan(base: Track, c: Candidate): Plan = Plan(
        base, c.track, c.tempo, c.partBars, c.baseTopFirst,
        anchorMs(base), anchorMs(c.track)
    )

    /**
     * Graphe de filtres ffmpeg (`-filter_complex`) du rendu. Entrée 0 = A
     * lue depuis son ancre, entrée 1 = B lue depuis la sienne. Chaque
     * morceau est étiré à sa vitesse (atempo, hauteur conservée), B est
     * retardé de l'intro, puis chacun se divise en trois couches — pleine
     * bande, basses (passe-bas), aigus (passe-haut) — dont les enveloppes
     * (afade entrée/sortie) dessinent la forme du mashup ; les six couches
     * sont sommées, coupées à la durée et limitées. Sortie : [out].
     */
    fun filterGraph(p: Plan): String {
        val bar = p.barSeconds
        val l = p.partBars
        val p1 = INTRO_BARS.toDouble()
        val p2 = p1 + l
        val p3 = p2 + l
        val end = p3 + OUTRO_BARS
        fun s(bars: Double) = "%.3f".format(Locale.US, bars * bar)
        fun trapezoid(inAt: Double, inLen: Double, outAt: Double, outLen: Double) =
            "afade=t=in:st=${s(inAt)}:d=${s(inLen)},afade=t=out:st=${s(outAt)}:d=${s(outLen)}"
        val lo = "lowpass=f=$CROSSOVER_HZ:p=2,lowpass=f=$CROSSOVER_HZ:p=2"
        val hi = "highpass=f=$CROSSOVER_HZ:p=2,highpass=f=$CROSSOVER_HZ:p=2"
        // Rôles par moitié : dans la première, A tient les basses (ou les
        // aigus si baseTopFirst), B l'inverse ; puis échange.
        val aFirst = if (p.baseTopFirst) hi else lo
        val bFirst = if (p.baseTopFirst) lo else hi
        val aSecond = if (p.baseTopFirst) lo else hi
        val bSecond = if (p.baseTopFirst) hi else lo
        val fmt = "aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo"
        val rateA = "%.5f".format(Locale.US, p.tempo.rateA)
        val rateB = "%.5f".format(Locale.US, p.tempo.rateB)
        val delayMs = Math.round(p1 * bar * 1000.0)
        return buildString {
            append("[0:a]$fmt,atempo=$rateA,asplit=3[a0][a1][a2];")
            append("[a0]${trapezoid(0.0, 0.25, p1, 1.0)}[af];")
            append("[a1]$aFirst,${trapezoid(p1, 1.0, p2, 1.0)}[a1f];")
            append("[a2]$aSecond,${trapezoid(p2, 1.0, p3, 1.0)}[a2f];")
            append("[1:a]$fmt,atempo=$rateB,adelay=delays=$delayMs:all=1,asplit=3[b0][b1][b2];")
            append("[b1]$bFirst,${trapezoid(p1, 1.0, p2, 1.0)}[b1f];")
            append("[b2]$bSecond,${trapezoid(p2, 1.0, p3, 1.0)}[b2f];")
            append("[b0]${trapezoid(p3, 1.0, end - END_FADE_BARS, END_FADE_BARS.toDouble())}[bf];")
            append("[af][a1f][a2f][b1f][b2f][bf]amix=inputs=6:normalize=0:duration=longest,")
            append("atrim=duration=${s(end)},")
            append("alimiter=limit=0.9:attack=5:release=50:level=false[out]")
        }
    }

    /** Nom de fichier du mashup (sans extension). */
    fun fileBaseName(p: Plan): String {
        val a = p.base.title.ifBlank { "A" }
        val b = p.partner.title.ifBlank { "B" }
        return "Mashup - $a x $b".replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100)
    }
}
