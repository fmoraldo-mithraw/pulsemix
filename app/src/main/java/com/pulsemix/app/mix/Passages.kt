package com.pulsemix.app.mix

import com.pulsemix.app.analysis.PhraseProfile
import com.pulsemix.app.analysis.StructureDetector
import com.pulsemix.app.data.Track
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Passages par RÔLE d'un morceau — la « meilleure minute » étendue.
 *
 * L'analyse retient un seul passage par morceau : le plus énergique
 * (`bestStartMs`/`segmentMs`, le **FORT**). Un set chill, une accalmie ou
 * un flow n'ont pas besoin du drop : ils veulent le groove, le break, la
 * montée. Cet objet dérive, **sans réanalyse**, jusqu'à trois passages
 * supplémentaires à partir de ce que l'analyse a déjà rangé dans le
 * morceau — la structure (sections) et le profil phrase par phrase :
 *
 *  - **MONTEE** : la montée (BUILD) qui précède le passage fort, jouée
 *    jusqu'au bout du passage fort — « de la montée au drop » ;
 *  - **GROOVE** : la suite de phrases d'énergie moyenne la plus STABLE
 *    (kick présent, pas de hook qui crie) — la matière rythmique ;
 *  - **CALME** : la suite de phrases calmes la plus MUSICALE (relief des
 *    médiums : une mélodie, une voix), de préférence dans un break.
 *
 * Un candidat ne chevauche jamais le passage fort, tient au moins
 * [MIN_PHRASES] phrases de 16 temps et s'arrête avant les 15 dernières
 * secondes. Morceau de moins de trois minutes : un seul passage, le fort
 * (pas la place d'en découper d'autres sans tomber dans l'intro ou l'outro).
 *
 * Chaque type de mix dit quel rôle il veut ([Mood]) ; [choose] applique
 * cette préférence, les refus de l'utilisateur ([Track.passageRefused])
 * et la **rotation** entre sets (le rôle joué la dernière fois cède la
 * place au suivant compatible). Tout ici est PUR (testé en JVM).
 */
object Passages {

    enum class Role(val label: String) {
        FORT("fort"), MONTEE("montée"), GROOVE("groove"), CALME("calme")
    }

    /** Un passage : [startMs] inclus, [endMs] exclu, aligné sur les phrases. */
    data class Passage(val role: Role, val startMs: Long, val endMs: Long) {
        val lengthMs: Long get() = endMs - startMs
    }

    /**
     * Ce qu'une phase de mix attend d'un morceau, par ordre de préférence.
     * FORT seul pour les phases qui tapent (jamais de calme en peak time) ;
     * les autres retombent toujours sur FORT quand le morceau n'offre rien
     * d'autre.
     */
    enum class Mood(val prefs: List<Role>) {
        /** Le drop, toujours (peak, vagues, relance). */
        FORT(listOf(Role.FORT)),
        /** De la montée au drop (phases de montée). */
        MONTEE(listOf(Role.MONTEE, Role.FORT)),
        /** La matière rythmique (flow, décollage). */
        GROOVE(listOf(Role.GROOVE, Role.FORT)),
        /** Le calme d'abord (chill, accalmies, chauffe, pause). */
        DOUX(listOf(Role.CALME, Role.GROOVE, Role.FORT)),
        /** Fort et groove en alternance (auto-DJ, similaires). */
        VARIE(listOf(Role.FORT, Role.GROOVE))
    }

    /** Sous cette durée, un seul passage : le fort. */
    const val MIN_TRACK_MS = 180_000L
    /** Un candidat tient au moins deux phrases de 16 temps. */
    const val MIN_PHRASES = 2
    /** Marge de fin : rien ne commence ni ne s'étend dans les 15 dernières s. */
    const val END_MARGIN_MS = 15_000L
    // Seuils d'énergie (rapportée à la crête du morceau, cf. PhraseProfile)
    internal const val STRONG_LEVEL = 0.78f
    internal const val CALM_LEVEL = 0.55f
    internal const val SILENCE_LEVEL = 0.12f

    /** Le passage fort tel que l'analyse l'a retenu (toujours défini). */
    fun fort(track: Track): Passage {
        val start = track.bestStartMs.coerceIn(0L, max(0L, track.durationMs - END_MARGIN_MS))
        val end = min(track.durationMs, start + track.segmentMs.coerceAtLeast(1L))
        return Passage(Role.FORT, start, end)
    }

    /**
     * Les passages SUPPLÉMENTAIRES (MONTEE, GROOVE, CALME — au plus un par
     * rôle) que le morceau offre ; vide quand il n'offre rien d'autre que
     * son passage fort (court, non analysé, sans structure ni profil).
     */
    fun candidates(track: Track): List<Passage> {
        if (!track.analyzed || track.bpm <= 0f) return emptyList()
        if (track.durationMs < MIN_TRACK_MS) return emptyList()
        val sections = if (track.structure.isEmpty()) emptyList()
        else StructureDetector.decode(track.structure)
        val profile = if (track.phraseProfile.isEmpty()) emptyList()
        else PhraseProfile.decode(track.phraseProfile)
        if (sections.isEmpty() && profile.isEmpty()) return emptyList()

        val fort = fort(track)
        val out = ArrayList<Passage>(3)
        montee(track, fort, sections)?.let { out.add(it) }

        // Grille des phrases (celle du profil quand il existe, sinon celle
        // du premier beat) : une mesure d'énergie/basses/médiums par phrase,
        // lue dans le profil ou, à défaut, déduite du type de section.
        val p = PhraseProfile.phraseMs(track.bpm)
        val origin = PhraseProfile.originMs(track.bpm, track.firstBeatMs)
        val n = if (profile.isNotEmpty()) profile.size
        else Math.floor((track.durationMs - origin) / p).toInt().coerceAtLeast(0)
        if (n < MIN_PHRASES) return out
        val energy = FloatArray(n)
        val bass = FloatArray(n)
        val mid = FloatArray(n)
        for (k in 0 until n) {
            if (profile.isNotEmpty()) {
                energy[k] = profile[k].energy
                bass[k] = profile[k].bass
                mid[k] = profile[k].mid
            } else {
                val mids = (origin + (k + 0.5) * p).toLong()
                when (sections.firstOrNull { mids >= it.startMs && mids < it.endMs }?.kind) {
                    StructureDetector.SectionKind.DROP -> { energy[k] = 0.9f; bass[k] = 0.7f }
                    StructureDetector.SectionKind.BUILD -> { energy[k] = 0.65f; bass[k] = 0.4f }
                    StructureDetector.SectionKind.BREAK -> { energy[k] = 0.4f; bass[k] = 0.3f }
                    else -> { energy[k] = 0.3f; bass[k] = 0.3f }
                }
                mid[k] = 0.5f
            }
        }
        val midMedian = median(mid)
        val bassMedian = median(bass)

        // Étiquette par phrase : fort / groove / calme / interdit (passage
        // fort, début avant la musique, queue du morceau, silence).
        val label = IntArray(n) { k ->
            val s = (origin + k * p).toLong()
            val e = (origin + (k + 1) * p).toLong()
            when {
                e > track.durationMs - END_MARGIN_MS -> 0
                s < track.musicStartMs -> 0
                s < fort.endMs && e > fort.startMs -> 0
                energy[k] < SILENCE_LEVEL -> 0
                energy[k] >= STRONG_LEVEL -> 1
                energy[k] >= CALM_LEVEL -> 2
                else -> 3
            }
        }

        fun sectionKindAt(ms: Long) =
            sections.firstOrNull { ms >= it.startMs && ms < it.endMs }?.kind

        // Suites de phrases de même étiquette : la meilleure par rôle.
        var bestGroove: Passage? = null
        var bestGrooveScore = Float.NEGATIVE_INFINITY
        var bestCalme: Passage? = null
        var bestCalmeScore = Float.NEGATIVE_INFINITY
        var k = 0
        while (k < n) {
            val l = label[k]
            var j = k
            while (j < n && label[j] == l) j++
            val len = j - k
            if ((l == 2 || l == 3) && len >= MIN_PHRASES) {
                val start = (origin + k * p).toLong().coerceAtLeast(0L)
                val end = (origin + j * p).toLong()
                val lenBonus = min(len, 6).toFloat()
                if (l == 2) {
                    // Groove : stable (faible écart-type d'énergie), kick
                    // présent (basses au-dessus de la médiane du morceau),
                    // pas un hook (médiums sans relief marqué).
                    var mE = 0f
                    var mB = 0f
                    var mM = 0f
                    for (i in k until j) { mE += energy[i]; mB += bass[i]; mM += mid[i] }
                    mE /= len; mB /= len; mM /= len
                    var v = 0f
                    for (i in k until j) v += (energy[i] - mE) * (energy[i] - mE)
                    val sd = sqrt(v / len)
                    val score = lenBonus - 8f * sd + 3f * (mB - bassMedian) -
                        2f * max(0f, mM - midMedian - 0.06f)
                    if (score > bestGrooveScore) {
                        bestGrooveScore = score
                        bestGroove = Passage(Role.GROOVE, start, end)
                    }
                } else {
                    // Calme : musical (relief des médiums : mélodie, voix),
                    // de préférence dans un break détecté.
                    var mM = 0f
                    for (i in k until j) mM += mid[i]
                    mM /= len
                    // Un break détecté vaut bien plus qu'une intro ou un
                    // outro aussi calmes : c'est là que le morceau respire
                    // sans s'être arrêté.
                    val kind = sectionKindAt((start + end) / 2)
                    val sectionBonus = when (kind) {
                        StructureDetector.SectionKind.BREAK -> 3f
                        StructureDetector.SectionKind.INTRO,
                        StructureDetector.SectionKind.OUTRO -> -1.5f
                        else -> 0f
                    }
                    val score = lenBonus + 6f * (mM - midMedian) + sectionBonus
                    if (score > bestCalmeScore) {
                        bestCalmeScore = score
                        bestCalme = Passage(Role.CALME, start, end)
                    }
                }
            }
            k = j
        }
        bestGroove?.let { out.add(it) }
        bestCalme?.let { out.add(it) }
        return out
    }

    /** Tous les passages connus du morceau, le fort en tête. */
    fun all(track: Track): List<Passage> = listOf(fort(track)) + candidates(track)

    /**
     * La montée : section BUILD (≥ 1 phrase) qui se termine à ± une mesure
     * de l'ancre du passage fort — le drop le plus proche du passage fort,
     * comme DjMixer.anchorFor — jouée jusqu'à la fin du passage fort.
     */
    private fun montee(
        track: Track,
        fort: Passage,
        sections: List<StructureDetector.Section>
    ): Passage? {
        if (sections.isEmpty()) return null
        val phraseMs = PhraseProfile.phraseMs(track.bpm)
        val barMs = phraseMs / 4.0
        val drop = sections.filter {
            it.kind == StructureDetector.SectionKind.DROP &&
                kotlin.math.abs(it.startMs - fort.startMs) <= phraseMs &&
                it.startMs <= track.durationMs - END_MARGIN_MS
        }.minByOrNull { kotlin.math.abs(it.startMs - fort.startMs) }
        val anchor = drop?.startMs ?: fort.startMs
        val build = sections.firstOrNull {
            it.kind == StructureDetector.SectionKind.BUILD &&
                kotlin.math.abs(it.endMs - anchor) <= barMs &&
                it.endMs - it.startMs >= phraseMs - 1.0
        } ?: return null
        if (build.startMs < track.musicStartMs) return null
        val end = max(fort.endMs, anchor + fort.lengthMs)
        return Passage(Role.MONTEE, build.startMs, min(end, track.durationMs))
    }

    // ------------------------------------------------------------- choix

    /** Rôles refusés par l'utilisateur (champ `passageRefused`, « a,b »). */
    fun refused(track: Track): Set<Role> =
        if (track.passageRefused.isEmpty()) emptySet()
        else track.passageRefused.split(',').mapNotNull { s ->
            Role.entries.firstOrNull { it.name == s.trim() }
        }.toSet()

    /** Encode un jeu de rôles refusés pour `passageRefused`. */
    fun encodeRefused(roles: Set<Role>): String =
        Role.entries.filter { it in roles }.joinToString(",") { it.name }

    /**
     * Le passage à jouer pour ce morceau dans une phase d'humeur [mood],
     * sachant le rôle joué la dernière fois ([lastRole], null = jamais) :
     * le premier rôle préféré que le morceau offre et que l'utilisateur
     * n'a pas refusé — sauf s'il vient d'être joué et qu'un autre rôle
     * compatible existe (rotation). Un passage FORT est rendu tel quel
     * (le moteur garde alors son calage historique : ancre sur le drop).
     */
    fun choose(track: Track, mood: Mood, lastRole: Role?): Passage {
        val fort = fort(track)
        val offered = HashMap<Role, Passage>()
        offered[Role.FORT] = fort
        val refused = refused(track)
        for (c in candidates(track)) if (c.role !in refused) offered[c.role] = c
        val ordered = mood.prefs.mapNotNull { offered[it] }
        if (ordered.isEmpty()) return fort
        if (ordered.size >= 2 && lastRole != null && ordered[0].role == lastRole) {
            return ordered[1]
        }
        return ordered[0]
    }

    /** « 1:32 → 2:35 » */
    fun describe(p: Passage): String =
        "${mmss(p.startMs)} → ${mmss(p.endMs)}"

    private fun mmss(ms: Long): String {
        val s = ms / 1000L
        return "%d:%02d".format(s / 60L, s % 60L)
    }

    private fun median(arr: FloatArray): Float {
        if (arr.isEmpty()) return 0.5f
        val s = arr.copyOf()
        s.sort()
        return s[s.size / 2]
    }
}
