package com.pulsemix.app.analysis

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Détection de la structure d'un morceau : intro, montée (build), temps
 * fort (refrain/drop), calme (breakdown), outro. Tout se lit dans les
 * tableaux déjà calculés par [AudioAnalyzer] (RMS par bloc, flux
 * spectral) — aucune seconde passe de décodage, aucune dépendance
 * Android : l'objet est pur et testé en JVM.
 *
 * La segmentation sert à deux choses : colorer la forme d'onde du lecteur
 * (chaque section sa teinte) et offrir au moteur DJ des frontières de
 * phrases où caler ses sorties — la fin d'un temps fort plutôt qu'un
 * point arbitraire au milieu d'une section.
 */
object StructureDetector {

    enum class SectionKind { INTRO, BUILD, DROP, BREAK, OUTRO }

    /** Une section de [startMs] (inclus) à [endMs] (exclu). Les sections
     *  d'un morceau couvrent [0, durationMs] sans trou ni chevauchement. */
    data class Section(val startMs: Long, val endMs: Long, val kind: SectionKind)

    // Seuils RELATIFS au pic d'énergie lissée : un master écrasé et un
    // morceau très dynamique donnent la même lecture de structure.
    const val STRONG_LEVEL = 0.75f
    const val CALM_LEVEL = 0.40f
    // Lissage du RMS (~2 s) : le grain des blocs (~93 ms) ferait détecter
    // une « section » à chaque respiration entre deux mesures.
    const val SMOOTH_MS = 2_000f
    // Phrase musicale : 16 temps — 8 pour les morceaux courts, qui n'ont
    // pas la place d'en dérouler des entières.
    const val PHRASE_BEATS = 16
    const val PHRASE_BEATS_SHORT = 8
    const val SHORT_TRACK_MS = 90_000L
    // Basses : au-dessus de cette part du pic lissé de bassRms, une trame
    // forte a « ses basses » — c'est un drop ; en dessous, l'énergie vient
    // du reste du spectre (break filtré, montée sans kick). Relatif au pic,
    // comme les seuils RMS : indépendant du mastering.
    const val BASS_DROP_LEVEL = 0.60f

    /** Longueur d'une phrase en ms (0.0 si le BPM est inconnu). */
    fun phraseMs(bpm: Float, durationMs: Long): Double =
        if (bpm <= 0f) 0.0
        else (if (durationMs < SHORT_TRACK_MS) PHRASE_BEATS_SHORT else PHRASE_BEATS) *
            60_000.0 / bpm

    /**
     * Segmente le morceau. Le résultat couvre [0, durationMs] sans trou ni
     * chevauchement ; liste vide si les données ne permettent rien de
     * fiable (tableaux vides, silence).
     *
     * @param rms énergie par bloc sur TOUT le morceau, un point tous les
     *   [hopMs] millisecondes.
     * @param flux flux spectral recalé sur la MÊME grille que [rms] ;
     *   valeur négative = pas de mesure à cet endroit (la FFT de l'analyse
     *   ne couvre qu'une fenêtre centrale). Peut être vide.
     * @param bpm 0 ou moins = pas d'arrondi aux phrases.
     * @param firstBeatMs ancre de la grille de phrases (un beat mesuré,
     *   n'importe où dans le morceau : la grille s'étend des deux côtés).
     * @param bassRms énergie de la bande basse (~< 150 Hz) par bloc, sur
     *   la MÊME grille que [rms]. C'est le marqueur n° 1 d'un drop en
     *   electro : le break/build retire la basse, le drop la fait
     *   exploser — le RMS seul ne les distingue pas. Vide (défaut, ou
     *   taille désalignée) : détection historique, à l'identique.
     */
    fun detect(
        rms: FloatArray,
        flux: FloatArray,
        hopMs: Float,
        bpm: Float,
        durationMs: Long,
        firstBeatMs: Long,
        bassRms: FloatArray = FloatArray(0)
    ): List<Section> {
        if (rms.isEmpty() || hopMs <= 0f || durationMs <= 0L) return emptyList()
        val n = rms.size

        // RMS lissé (~2 s), puis niveau relatif au pic
        val win = max(1, (SMOOTH_MS / hopMs).roundToInt())
        val smooth = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f
            var c = 0
            for (j in max(0, i - win / 2)..min(n - 1, i + win / 2)) {
                s += rms[j]; c++
            }
            smooth[i] = s / c
        }
        var peak = 0f
        for (v in smooth) if (v > peak) peak = v
        if (peak <= 1e-6f) return emptyList() // silence : rien à segmenter

        // Basses lissées sur la même fenêtre, quand elles sont fournies
        // (taille alignée sur rms) : c'est elles qui distinguent drop et
        // break — indistinguables au RMS seul, un break filtré garde toute
        // son énergie de médiums et d'aigus. Absentes (ancienne analyse) :
        // comportement historique, bit à bit.
        val hasBass = bassRms.size == n
        val bsmooth = if (hasBass) FloatArray(n) else FloatArray(0)
        var bassPeak = 0f
        if (hasBass) {
            for (i in 0 until n) {
                var s = 0f
                var c = 0
                for (j in max(0, i - win / 2)..min(n - 1, i + win / 2)) {
                    s += bassRms[j]; c++
                }
                bsmooth[i] = s / c
                if (bsmooth[i] > bassPeak) bassPeak = bsmooth[i]
            }
        }
        val bassHi = BASS_DROP_LEVEL * bassPeak

        // Classe par trame : 0 = calme, 1 = moyen, 2 = fort avec basses,
        // 3 = fort SANS basses (break filtré — jamais émis sans bassRms)
        val cls = IntArray(n) {
            val v = smooth[it] / peak
            when {
                v >= STRONG_LEVEL ->
                    if (!hasBass || bsmooth[it] >= bassHi) 2 else 3
                v <= CALM_LEVEL -> 0
                else -> 1
            }
        }

        // Plages contiguës de même classe -> nature de chaque section
        val starts = ArrayList<Int>()
        for (i in 0 until n) if (i == 0 || cls[i] != cls[i - 1]) starts.add(i)
        val kinds = arrayOfNulls<SectionKind>(starts.size)
        for (k in starts.indices) {
            val from = starts[k]
            val until = if (k + 1 < starts.size) starts[k + 1] else n
            val c = cls[from]
            kinds[k] = when {
                c == 2 -> SectionKind.DROP
                // Fort mais basses retirées : toute l'énergie vient des
                // médiums et des aigus — un break filtré, pas un drop.
                c == 3 -> SectionKind.BREAK
                // Montée : segment moyen qui grimpe vers un temps fort,
                // avec un flux spectral qui grimpe aussi quand il est
                // mesuré — la signature d'un build (filtre qui s'ouvre,
                // percussions qui s'épaississent). Avec bassRms : les
                // basses doivent être encore basses ET déboucher sur leur
                // explosion (la classe 2 qui suit exige les basses pleines).
                c == 1 && k + 1 < starts.size && cls[starts[k + 1]] == 2 &&
                    smooth[until - 1] > smooth[from] &&
                    fluxRises(flux, from, until) &&
                    (!hasBass || meanIn(bsmooth, from, until) < bassHi) ->
                    SectionKind.BUILD
                k == 0 -> SectionKind.INTRO
                k == starts.size - 1 -> SectionKind.OUTRO
                else -> SectionKind.BREAK
            }
        }

        // Affinage du « 1 » de chaque DROP : la frontière brute vient des
        // lissages (~2 s), qui traînent derrière l'événement réel. Le vrai
        // départ d'un drop est le plus grand SAUT de bassRms (brut, pas
        // lissé) dans une fenêtre de ± une mesure autour de la frontière —
        // ce bloc devient la frontière, AVANT l'arrondi à la phrase.
        // C'est ce « 1 » précis que le drop-swap du moteur DJ vise.
        val bounds0 = IntArray(starts.size) { starts[it] }
        if (hasBass && bpm > 0f) {
            val barBlocks = Math.round(4f * 60_000f / bpm / hopMs)
            for (k in 1 until starts.size) {
                if (kinds[k] != SectionKind.DROP || barBlocks <= 0) continue
                val b = bounds0[k]
                val lo = max(1, max(bounds0[k - 1] + 1, b - barBlocks))
                val hi = min(
                    (if (k + 1 < starts.size) starts[k + 1] else n) - 1,
                    b + barBlocks
                )
                var bestI = b
                var bestJump = 0f // un vrai saut seulement : sans montée
                // de basses dans la fenêtre, la frontière ne bouge pas
                for (i in lo..hi) {
                    val jump = bassRms[i] - bassRms[i - 1]
                    if (jump > bestJump) {
                        bestJump = jump
                        bestI = i
                    }
                }
                bounds0[k] = bestI
            }
        }

        val raw = ArrayList<Section>(starts.size)
        for (k in starts.indices) {
            val s = if (k == 0) 0L
            else min(durationMs, (bounds0[k] * hopMs.toDouble()).toLong())
            val e = if (k == starts.size - 1) durationMs
            else min(durationMs, (bounds0[k + 1] * hopMs.toDouble()).toLong())
            if (e > s) raw.add(Section(s, e, kinds[k]!!))
        }
        if (raw.isEmpty()) return emptyList()

        // Arrondi des frontières internes à la phrase, ancré sur le
        // premier beat mesuré ; une frontière qui rejoint sa voisine fait
        // disparaître la section (elle n'avait pas une phrase à vivre).
        val phrase = phraseMs(bpm, durationMs)
        var sections: MutableList<Section> = raw
        if (phrase > 0.0) {
            val bounds = LongArray(raw.size + 1)
            bounds[0] = 0L
            bounds[raw.size] = durationMs
            for (k in 1 until raw.size) {
                val b = raw[k].startMs
                val steps = Math.round((b - firstBeatMs) / phrase)
                bounds[k] = Math.round(firstBeatMs + steps * phrase)
                    .coerceIn(0L, durationMs)
                if (bounds[k] < bounds[k - 1]) bounds[k] = bounds[k - 1]
            }
            sections = ArrayList(raw.size)
            for (k in raw.indices) {
                if (bounds[k + 1] > bounds[k]) {
                    sections.add(Section(bounds[k], bounds[k + 1], raw[k].kind))
                }
            }
        }

        // Fusion des sections plus courtes qu'une phrase (~4 s sans BPM) :
        // elles n'ont pas d'existence musicale, le voisin le plus long les
        // absorbe. Une par une, la plus courte d'abord.
        val minLen = if (phrase > 0.0) Math.round(phrase) else 4_000L
        while (sections.size > 1) {
            var idx = -1
            var len = Long.MAX_VALUE
            for (k in sections.indices) {
                val l = sections[k].endMs - sections[k].startMs
                if (l < minLen && l < len) {
                    idx = k; len = l
                }
            }
            if (idx < 0) break
            val victim = sections[idx]
            val prev = if (idx > 0) sections[idx - 1] else null
            val next = if (idx < sections.size - 1) sections[idx + 1] else null
            val toPrev = next == null || (prev != null &&
                prev.endMs - prev.startMs >= next.endMs - next.startMs)
            if (toPrev && prev != null) {
                sections[idx - 1] = prev.copy(endMs = victim.endMs)
            } else if (next != null) {
                sections[idx + 1] = next.copy(startMs = victim.startMs)
            }
            sections.removeAt(idx)
        }

        // Voisines de même nature : une seule section
        val out = ArrayList<Section>(sections.size)
        for (s in sections) {
            val last = out.lastOrNull()
            if (last != null && last.kind == s.kind) {
                out[out.size - 1] = last.copy(endMs = s.endMs)
            } else out.add(s)
        }
        return out
    }

    /** Le flux spectral croît-il sur [from, until) ? Vrai par défaut quand
     *  la fenêtre n'a pas de mesure : l'analyse FFT ne couvre pas tout le
     *  morceau, l'absence de flux ne doit pas interdire les montées. */
    private fun fluxRises(flux: FloatArray, from: Int, until: Int): Boolean {
        val mid = (from + until) / 2
        var headSum = 0.0
        var headCnt = 0
        var tailSum = 0.0
        var tailCnt = 0
        for (i in from until min(until, flux.size)) {
            val v = flux[i]
            if (v < 0f) continue
            if (i < mid) {
                headSum += v; headCnt++
            } else {
                tailSum += v; tailCnt++
            }
        }
        if (headCnt == 0 || tailCnt == 0) return true
        return tailSum / tailCnt >= headSum / headCnt
    }

    /** Moyenne de [arr] sur [from, until) — bornée à la taille du tableau. */
    private fun meanIn(arr: FloatArray, from: Int, until: Int): Float {
        var s = 0f
        var c = 0
        for (i in from until min(until, arr.size)) {
            s += arr[i]; c++
        }
        return if (c > 0) s / c else 0f
    }

    // -------------------------------------------------------- persistance

    /** Forme compacte « KIND:startMs:endMs;… » pour le champ
     *  [com.pulsemix.app.data.Track.structure]. */
    fun encode(sections: List<Section>): String =
        sections.joinToString(";") { "${it.kind.name}:${it.startMs}:${it.endMs}" }

    /** Relit [encode]. Tolérant : une entrée illisible est ignorée (un
     *  champ vide ou corrompu vaut « pas de structure », jamais un crash). */
    fun decode(text: String): List<Section> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Section>()
        for (part in text.split(';')) {
            val bits = part.split(':')
            if (bits.size != 3) continue
            val kind = try {
                SectionKind.valueOf(bits[0])
            } catch (_: IllegalArgumentException) {
                continue
            }
            val s = bits[1].toLongOrNull() ?: continue
            val e = bits[2].toLongOrNull() ?: continue
            if (e > s && s >= 0) out.add(Section(s, e, kind))
        }
        return out
    }
}
