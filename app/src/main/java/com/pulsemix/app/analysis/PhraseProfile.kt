package com.pulsemix.app.analysis

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Profil PHRASE PAR PHRASE d'un morceau — le niveau d'analyse qui manquait
 * au va-et-vient pour savoir *quoi mettre en avant et quand*.
 *
 * La grille est celle des phrases de 16 temps ancrées sur le premier beat
 * détecté ([phraseIndex]). Pour chaque phrase, trois nombres (0..1) :
 *  - **energy** : niveau moyen rapporté à la crête du morceau (95e
 *    centile) — un couplet calme, un break, un drop ;
 *  - **bass** : part des basses (< 150 Hz) dans le niveau — la présence
 *    du kick et de la ligne de basse ;
 *  - **mid** : part des médiums (250 Hz – 3 kHz) dans le niveau — c'est là
 *    que vivent les voix et les mélodies ; rapportée à la médiane du
 *    morceau ([isVocal], [hookScore]), elle dit si la phrase est un
 *    *hook* (voix, thème) ou de la matière rythmique (drums, nappe).
 *
 * Calculé dans la même passe que le reste de l'analyse, sur les tableaux
 * déjà en mémoire (RMS, basses, médiums par bloc) : pas de seconde
 * lecture du fichier. Encodé compact dans `Track.phraseProfile`.
 *
 * Tout ici est PUR (testé en JVM).
 */
object PhraseProfile {

    data class Phrase(val energy: Float, val bass: Float, val mid: Float)

    /** Durée d'une phrase de 16 temps (ms). */
    fun phraseMs(bpm: Float): Double = 16.0 * 60_000.0 / bpm

    /** Origine de la grille (ms) : le premier beat, ramené avant 0 par
     *  phrases entières — la phrase 0 commence entre 0 et une phrase. */
    fun originMs(bpm: Float, firstBeatMs: Long): Double {
        val p = phraseMs(bpm)
        return firstBeatMs - floor(firstBeatMs / p) * p
    }

    /** Indice de la phrase qui contient [ms] (≥ 0). */
    fun phraseIndex(ms: Long, bpm: Float, firstBeatMs: Long): Int {
        if (bpm <= 0f) return 0
        val p = phraseMs(bpm)
        return floor((ms - originMs(bpm, firstBeatMs)) / p).toInt().coerceAtLeast(0)
    }

    /**
     * Profil d'un morceau à partir des niveaux par bloc ([blockMs] ms) :
     * une entrée par phrase de la grille, jusqu'à la fin du morceau.
     * Vide sans tempo ou sans matière.
     */
    fun compute(
        rms: FloatArray,
        bassRms: FloatArray,
        midRms: FloatArray,
        blockMs: Double,
        bpm: Float,
        firstBeatMs: Long,
        durationMs: Long
    ): List<Phrase> {
        if (bpm <= 0f || rms.isEmpty() || blockMs <= 0.0 || durationMs <= 0L) return emptyList()
        val p = phraseMs(bpm)
        val origin = originMs(bpm, firstBeatMs)
        val peak = percentile(rms, 0.95f).coerceAtLeast(1e-6f)
        val n = floor((durationMs - origin) / p).toInt().coerceAtLeast(0)
        val out = ArrayList<Phrase>(n)
        for (k in 0 until n) {
            val startMs = origin + k * p
            val b0 = floor(startMs / blockMs).toInt().coerceIn(0, rms.size - 1)
            val b1 = floor((startMs + p) / blockMs).toInt().coerceIn(b0 + 1, rms.size)
            var e = 0.0
            var b = 0.0
            var m = 0.0
            for (i in b0 until b1) {
                e += rms[i]
                if (i < bassRms.size) b += bassRms[i]
                if (i < midRms.size) m += midRms[i]
            }
            val cnt = (b1 - b0).toDouble()
            val energy = (e / cnt / peak).toFloat().coerceIn(0f, 1f)
            val bass = if (e > 0.0) (b / e).toFloat().coerceIn(0f, 1f) else 0f
            val mid = if (e > 0.0) (m / e).toFloat().coerceIn(0f, 1f) else 0f
            out.add(Phrase(energy, bass, mid))
        }
        return out
    }

    /** Forme compacte « e:b:m;… » (entiers 0..99). */
    fun encode(list: List<Phrase>): String =
        list.joinToString(";") { "${q(it.energy)}:${q(it.bass)}:${q(it.mid)}" }

    /** Relit [encode]. Tolérant : une entrée illisible vaut une phrase
     *  neutre (jamais un crash), un texte vide vaut « pas de profil ». */
    fun decode(text: String): List<Phrase> {
        if (text.isEmpty()) return emptyList()
        return text.split(';').map { part ->
            val bits = part.split(':')
            if (bits.size != 3) Phrase(0.5f, 0.5f, 0.5f)
            else Phrase(dq(bits[0]), dq(bits[1]), dq(bits[2]))
        }
    }

    /** Médiane des médiums du morceau (référence de [isVocal]). */
    fun midMedian(profile: List<Phrase>): Float {
        if (profile.isEmpty()) return 0.5f
        val s = profile.map { it.mid }.sorted()
        return s[s.size / 2]
    }

    /** Phrase « à voix / thème » : médiums nettement au-dessus de la
     *  médiane du morceau (+0,06) et niveau notable (> 0,3). */
    fun isVocal(profile: List<Phrase>, index: Int): Boolean {
        val p = profile.getOrNull(index) ?: return false
        return p.energy > 0.3f && p.mid > midMedian(profile) + 0.06f
    }

    /**
     * Ce qu'une phrase a à « montrer » (0..~1,5) : son niveau, plus le
     * relief de ses médiums par rapport au morceau (un hook, un thème,
     * une voix), plus un peu de ses basses (un drop). Sert à décider, par
     * cellule du va-et-vient, lequel des deux morceaux a la main.
     */
    fun hookScore(profile: List<Phrase>, index: Int): Float {
        val p = profile.getOrNull(index) ?: return 0f
        val relief = (p.mid - midMedian(profile)).coerceIn(-0.3f, 0.3f)
        return p.energy + 2f * relief + 0.3f * p.bass
    }

    private fun q(v: Float): Int = Math.round(v.coerceIn(0f, 1f) * 99f)
    private fun dq(s: String): Float = ((s.toIntOrNull() ?: 50).coerceIn(0, 99)) / 99f

    private fun percentile(arr: FloatArray, p: Float): Float {
        if (arr.isEmpty()) return 0f
        val s = arr.copyOf()
        s.sort()
        val i = (p * (s.size - 1)).toInt().coerceIn(0, s.size - 1)
        return s[i]
    }

    /** Petit utilitaire pour les appels bornés. */
    @Suppress("unused")
    private fun clampIdx(i: Int, size: Int) = min(max(i, 0), size - 1)
}
