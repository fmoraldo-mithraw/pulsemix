package com.pulsemix.app.analysis

import com.pulsemix.app.analysis.StructureDetector.SectionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la détection de structure sur des morceaux synthétiques : des
 * plateaux de RMS dessinent une intro calme, une montée, des temps forts,
 * un calme et une outro — et la segmentation doit les retrouver, avec des
 * frontières calées sur les phrases musicales.
 *
 * Rien ici ne touche à Android : StructureDetector est un objet pur qui
 * ne lit que des tableaux (mêmes données que celles d'AudioAnalyzer).
 */
class StructureDetectorTest {

    // Un point de RMS toutes les 100 ms : 10 trames par seconde
    private val HOP_MS = 100f

    /** RMS synthétique par morceaux : (nombre de trames, niveau de départ,
     *  niveau d'arrivée) — rampe linéaire entre les deux. */
    private fun profile(vararg parts: Triple<Int, Float, Float>): FloatArray {
        val out = ArrayList<Float>()
        for ((count, from, to) in parts) {
            for (i in 0 until count) {
                out.add(from + (to - from) * i / count.toFloat())
            }
        }
        return out.toFloatArray()
    }

    /** Pas de mesure de flux (l'analyse FFT ne couvre pas tout le morceau). */
    private fun noFlux(n: Int) = FloatArray(n) { -1f }

    /**
     * Profil de référence, 200 s à 120 BPM (phrase = 16 temps = 8 s) :
     * intro calme, montée, temps fort, calme, temps fort, outro.
     */
    private fun referenceRms() = profile(
        Triple(160, 0.2f, 0.2f), // 0-16 s : intro calme
        Triple(240, 0.2f, 1.0f), // 16-40 s : montée (croise 0.40 à 22 s, 0.75 à 32,5 s)
        Triple(560, 1.0f, 1.0f), // 40-96 s : temps fort
        Triple(160, 0.25f, 0.25f), // 96-112 s : calme
        Triple(480, 1.0f, 1.0f), // 112-160 s : temps fort
        Triple(400, 0.15f, 0.15f) // 160-200 s : outro
    )

    private val DUR = 200_000L

    @Test
    fun `profil complet - sections dans l ordre attendu`() {
        val rms = referenceRms()
        val sections = StructureDetector.detect(
            rms, noFlux(rms.size), HOP_MS, bpm = 120f,
            durationMs = DUR, firstBeatMs = 0L
        )
        assertEquals(
            listOf(
                SectionKind.INTRO, SectionKind.BUILD, SectionKind.DROP,
                SectionKind.BREAK, SectionKind.DROP, SectionKind.OUTRO
            ),
            sections.map { it.kind }
        )
        // Frontières attendues, arrondies à la phrase (8 s) : la montée
        // croise les seuils à 22 s et 32,5 s -> 24 s et 32 s.
        assertEquals(0L, sections[0].startMs)
        assertEquals(24_000L, sections[1].startMs)
        assertEquals(32_000L, sections[2].startMs)
        assertEquals(96_000L, sections[3].startMs)
        assertEquals(112_000L, sections[4].startMs)
        assertEquals(160_000L, sections[5].startMs)
        assertEquals(DUR, sections[5].endMs)
    }

    @Test
    fun `frontieres internes sur la grille des phrases`() {
        val rms = referenceRms()
        val sections = StructureDetector.detect(
            rms, noFlux(rms.size), HOP_MS, 120f, DUR, firstBeatMs = 0L
        )
        val phrase = 16L * 60_000L / 120L // 8 000 ms
        for (k in 1 until sections.size) {
            assertEquals(
                "frontière ${sections[k].startMs} hors grille",
                0L, sections[k].startMs % phrase
            )
        }
    }

    @Test
    fun `l ancre du premier beat decale la grille`() {
        val rms = referenceRms()
        val sections = StructureDetector.detect(
            rms, noFlux(rms.size), HOP_MS, 120f, DUR, firstBeatMs = 1_000L
        )
        // La grille passe par firstBeatMs : toute frontière interne en est
        // à un nombre entier de phrases.
        for (k in 1 until sections.size) {
            assertEquals(
                "frontière ${sections[k].startMs} hors grille ancrée",
                0L, (sections[k].startMs - 1_000L) % 8_000L
            )
        }
    }

    @Test
    fun `couverture totale sans trou ni chevauchement`() {
        val rms = referenceRms()
        val sections = StructureDetector.detect(
            rms, noFlux(rms.size), HOP_MS, 120f, DUR, 0L
        )
        assertTrue(sections.isNotEmpty())
        assertEquals(0L, sections.first().startMs)
        assertEquals(DUR, sections.last().endMs)
        for (k in 1 until sections.size) {
            assertEquals(sections[k - 1].endMs, sections[k].startMs)
            assertTrue(sections[k].endMs > sections[k].startMs)
        }
    }

    @Test
    fun `mini sections fusionnees avec leur voisine`() {
        // Un creux de 2 s au milieu d'un temps fort : trop court pour être
        // une vraie section, il doit être absorbé (bpm = 0 pour tester la
        // fusion elle-même, sans l'arrondi qui ferait déjà disparaître le
        // creux).
        val rms = profile(
            Triple(200, 0.2f, 0.2f), // 0-20 s : intro
            Triple(300, 1.0f, 1.0f), // 20-50 s : fort
            Triple(20, 0.2f, 0.2f), // 50-52 s : creux de 2 s
            Triple(300, 1.0f, 1.0f), // 52-82 s : fort
            Triple(200, 0.15f, 0.15f) // 82-102 s : outro
        )
        val dur = rms.size * 100L
        val sections = StructureDetector.detect(
            rms, noFlux(rms.size), HOP_MS, bpm = 0f, durationMs = dur, firstBeatMs = 0L
        )
        assertEquals(
            listOf(SectionKind.INTRO, SectionKind.DROP, SectionKind.OUTRO),
            sections.map { it.kind }
        )
        // Le plancher de fusion sans BPM est de 4 s
        for (s in sections) assertTrue(s.endMs - s.startMs >= 4_000L)
    }

    @Test
    fun `bpm inconnu - pas d arrondi aux phrases`() {
        val rms = referenceRms()
        val sections = StructureDetector.detect(
            rms, noFlux(rms.size), HOP_MS, bpm = 0f, durationMs = DUR, firstBeatMs = 0L
        )
        // La montée croise le seuil calme à ~22 s : sans BPM la frontière
        // reste là (l'arrondi l'aurait poussée à 24 s).
        val b = sections[1].startMs
        assertTrue("frontière $b arrondie sans BPM", b in 21_000L..23_000L)
        assertTrue(b % 8_000L != 0L)
    }

    @Test
    fun `tableaux vides ou silence - liste vide`() {
        assertTrue(
            StructureDetector.detect(
                FloatArray(0), FloatArray(0), HOP_MS, 120f, DUR, 0L
            ).isEmpty()
        )
        // Un morceau entièrement silencieux ne se segmente pas
        assertTrue(
            StructureDetector.detect(
                FloatArray(2000), noFlux(2000), HOP_MS, 120f, DUR, 0L
            ).isEmpty()
        )
    }

    @Test
    fun `flux decroissant - la montee n en est pas une`() {
        // Même profil d'énergie, mais un flux spectral MESURÉ qui retombe
        // pendant la rampe : ce n'est pas un build (une nappe qui gonfle,
        // pas des percussions qui s'installent) -> section calme.
        val rms = referenceRms()
        val flux = noFlux(rms.size)
        for (i in 160 until 400) flux[i] = 1f - (i - 160) / 240f
        val sections = StructureDetector.detect(
            rms, flux, HOP_MS, 120f, DUR, 0L
        )
        assertTrue(sections.none { it.kind == SectionKind.BUILD })
        assertEquals(SectionKind.INTRO, sections[0].kind)
        assertEquals(SectionKind.DROP, sections.first { it.startMs >= 32_000L }.kind)
    }

    // ------------------------------------------------------------- basses
    // Avec bassRms, le détecteur lit le marqueur n° 1 des drops en
    // electro : le break retire la basse, le drop la fait exploser.

    /** bassRms alignée sur la grille : paliers (nombre de trames, niveau). */
    private fun bassProfile(vararg parts: Pair<Int, Float>): FloatArray {
        val out = ArrayList<Float>()
        for ((count, level) in parts) repeat(count) { out.add(level) }
        return out.toFloatArray()
    }

    @Test
    fun `bassRms vide - resultat identique a avant`() {
        // Non-régression bit à bit : sans basses (défaut ou tableau vide),
        // le profil de référence garde exactement sa lecture historique.
        val rms = referenceRms()
        val before = StructureDetector.detect(
            rms, noFlux(rms.size), HOP_MS, 120f, DUR, 0L
        )
        val explicit = StructureDetector.detect(
            rms, noFlux(rms.size), HOP_MS, 120f, DUR, 0L, FloatArray(0)
        )
        assertEquals(before, explicit)
        // Une bassRms désalignée (taille différente) est ignorée aussi
        val misaligned = StructureDetector.detect(
            rms, noFlux(rms.size), HOP_MS, 120f, DUR, 0L, FloatArray(7) { 1f }
        )
        assertEquals(before, misaligned)
        assertEquals(
            listOf(
                SectionKind.INTRO, SectionKind.BUILD, SectionKind.DROP,
                SectionKind.BREAK, SectionKind.DROP, SectionKind.OUTRO
            ),
            before.map { it.kind }
        )
    }

    @Test
    fun `basses retirees - le fort sans basses est un break`() {
        // Énergie RMS pleine PARTOUT dans le corps du morceau : au RMS
        // seul, 40-160 s serait un unique temps fort. Les basses
        // tranchent : retirées sur 96-112 s, c'est un break filtré.
        val rms = profile(
            Triple(160, 0.2f, 0.2f), // 0-16 s : intro
            Triple(240, 0.2f, 1.0f), // 16-40 s : montée
            Triple(560, 1.0f, 1.0f), // 40-96 s : drop
            Triple(160, 1.0f, 1.0f), // 96-112 s : break FILTRÉ (rms plein !)
            Triple(480, 1.0f, 1.0f), // 112-160 s : drop
            Triple(400, 0.15f, 0.15f) // 160-200 s : outro
        )
        val bass = bassProfile(
            310 to 0.05f, // 0-31 s : pas de basses (intro + montée)
            650 to 0.5f, // 31-96 s : basses pleines
            160 to 0.02f, // 96-112 s : basses retirées
            480 to 0.5f, // 112-160 s : basses pleines
            400 to 0.05f // 160-200 s : outro
        )
        val sections = StructureDetector.detect(
            rms, noFlux(rms.size), HOP_MS, 120f, DUR, 0L, bass
        )
        assertEquals(
            listOf(
                SectionKind.INTRO, SectionKind.BUILD, SectionKind.DROP,
                SectionKind.BREAK, SectionKind.DROP, SectionKind.OUTRO
            ),
            sections.map { it.kind }
        )
        val brk = sections.first { it.kind == SectionKind.BREAK }
        assertEquals(96_000L, brk.startMs)
        assertEquals(112_000L, brk.endMs)
    }

    @Test
    fun `basses deja pleines pendant la rampe - pas un build`() {
        // Les basses tournent à plein pendant la montée d'énergie : ce
        // n'est pas un build d'electro (qui retire la basse pour la
        // tension avant de la faire exploser).
        val rms = referenceRms()
        val bass = bassProfile(
            160 to 0.5f, 240 to 0.5f, 560 to 0.5f,
            160 to 0.02f, 480 to 0.5f, 400 to 0.05f
        )
        val sections = StructureDetector.detect(
            rms, noFlux(rms.size), HOP_MS, 120f, DUR, 0L, bass
        )
        assertTrue(sections.none { it.kind == SectionKind.BUILD })
        assertTrue(sections.any { it.kind == SectionKind.DROP })
    }

    @Test
    fun `saut de basses decale - le 1 du drop est recale`() {
        // La frontière brute vient du RMS lissé (croise 0,75 vers
        // 35,25 s → arrondie à 32 s) ; le vrai « 1 » du drop est le grand
        // saut de basses à 36,5 s, environ une demi-mesure plus loin.
        // Recalée sur le saut PUIS arrondie à la phrase, la frontière
        // tombe à 40 s.
        val rms = profile(
            Triple(160, 0.2f, 0.2f), // 0-16 s : intro
            Triple(280, 0.2f, 1.0f), // 16-44 s : montée (0,75 à 35,25 s)
            Triple(520, 1.0f, 1.0f), // 44-96 s : drop
            Triple(400, 0.15f, 0.15f) // 96-136 s : outro
        )
        val dur = rms.size * 100L // 136 s
        val bass = bassProfile(
            300 to 0.02f, // 0-30 s : pas de basses
            65 to 0.7f, // 30-36,5 s : les basses s'installent
            995 to 1.0f // 36,5 s : le vrai « 1 » claque ici
        )
        val without = StructureDetector.detect(
            rms, noFlux(rms.size), HOP_MS, 120f, dur, 0L
        )
        assertEquals(
            32_000L,
            without.first { it.kind == SectionKind.DROP }.startMs
        )
        val sections = StructureDetector.detect(
            rms, noFlux(rms.size), HOP_MS, 120f, dur, 0L, bass
        )
        assertEquals(
            listOf(
                SectionKind.INTRO, SectionKind.BUILD,
                SectionKind.DROP, SectionKind.OUTRO
            ),
            sections.map { it.kind }
        )
        assertEquals(
            40_000L,
            sections.first { it.kind == SectionKind.DROP }.startMs
        )
    }

    @Test
    fun `encode decode - aller-retour exact`() {
        val sections = listOf(
            StructureDetector.Section(0L, 24_000L, SectionKind.INTRO),
            StructureDetector.Section(24_000L, 32_000L, SectionKind.BUILD),
            StructureDetector.Section(32_000L, 160_000L, SectionKind.DROP),
            StructureDetector.Section(160_000L, 200_000L, SectionKind.OUTRO)
        )
        assertEquals(sections, StructureDetector.decode(StructureDetector.encode(sections)))
    }

    @Test
    fun `decode tolerant - champ vide ou corrompu ignore`() {
        assertTrue(StructureDetector.decode("").isEmpty())
        assertTrue(StructureDetector.decode("nawak").isEmpty())
        // Une entrée illisible n'invalide pas les autres
        val mixed = StructureDetector.decode("PLOUF:0:10;DROP:0:5000;DROP:9:3")
        assertEquals(1, mixed.size)
        assertEquals(SectionKind.DROP, mixed[0].kind)
    }
}
