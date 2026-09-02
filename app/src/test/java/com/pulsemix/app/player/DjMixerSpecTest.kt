package com.pulsemix.app.player

import com.pulsemix.app.analysis.StructureDetector
import com.pulsemix.app.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des décisions pures du moteur DJ : calage de tempo (computeRate)
 * et choix de la jonction (fadeSpec). Ce sont elles qui décident si deux
 * morceaux sont battus ensemble ou coupés — une régression s'entend en
 * plein set mais ne se voit dans aucun écran.
 *
 * Rien ici ne touche à Android : les deux fonctions vivent dans le
 * companion de DjMixer, en internal, et ne lisent que des Track.
 */
class DjMixerSpecTest {

    private val EPS = 1e-4f

    private fun track(
        uri: String,
        bpm: Float,
        camelot: String = "--",
        energyMean: Float = 0f,
        centroid: Float = 0f,
        sustainRatio: Float = 0f
    ) = Track(
        uri = uri, title = uri, artist = "", durationMs = 180_000L,
        bpm = bpm, camelot = camelot, energyMean = energyMean,
        centroid = centroid, sustainRatio = sustainRatio, analyzed = true
    )

    // -------------------------------------------------------- computeRate

    @Test
    fun `bpm proches - rate proportionnel dans les 4 pourcents`() {
        // 126 -> 128 : l'entrant est accéléré de 128/126 pour se caler
        assertEquals(128f / 126f, DjMixer.computeRate(128f, 126f), EPS)
        // 130 -> 126 : ralenti
        assertEquals(126f / 130f, DjMixer.computeRate(126f, 130f), EPS)
    }

    @Test
    fun `double et moitie sont cales au tempo naturel`() {
        // 140 vs 70 : le morceau à 70 BPM se joue tel quel (half-time)
        assertEquals(1f, DjMixer.computeRate(140f, 70f), EPS)
        // 70 vs 138 : x2 donne 140, calé sur 138 -> rate 140/138 ~ 1.014
        assertEquals(2f * 70f / 138f, DjMixer.computeRate(70f, 138f), EPS)
    }

    @Test
    fun `ecart trop grand - tempo naturel, pas d'etirement inutile`() {
        // 128 vs 100 : ni direct ni double/moitié ne rentre dans ±4 %.
        // Étirer quand même désaccordait le morceau SANS caler les temps :
        // on le joue à son tempo, et c'est fadeSpec qui transforme ce
        // non-calage en coupe courte (CUT).
        assertEquals(1f, DjMixer.computeRate(128f, 100f), EPS)
        assertEquals(1f, DjMixer.computeRate(100f, 128f), EPS)
    }

    @Test
    fun `ecart de 6 pourcents - plus de calage, tempo naturel`() {
        // 128 vs 121 (~5,8 %) : calable dans l'ancienne fenêtre de ±8 %,
        // plus dans celle de ±4 %.
        assertEquals(1f, DjMixer.computeRate(128f, 121f), EPS)
    }

    @Test
    fun `bpm inconnu - rate neutre`() {
        assertEquals(1f, DjMixer.computeRate(0f, 120f), EPS)
        assertEquals(1f, DjMixer.computeRate(120f, 0f), EPS)
    }

    // ---------------------------------------------------------- clampFadeS

    @Test
    fun `passage d'une minute - le blend de 18 s est ramene au plafond`() {
        // 60 s de passage : 15 % = 9 s, plafonné à MAX_FADE_S (8 s).
        val seg = 60L * DjMixer.OUT_SR
        assertEquals(
            DjMixer.MAX_FADE_S,
            DjMixer.clampFadeS(DjMixer.FADE_LOCKED_HARMONIC_S, seg), 1e-9
        )
    }

    @Test
    fun `passage court - le plafond suit la proportion`() {
        // 30 s de passage : 15 % = 4,5 s, sous le plafond absolu.
        val seg = 30L * DjMixer.OUT_SR
        assertEquals(4.5, DjMixer.clampFadeS(DjMixer.FADE_NORMAL_S, seg), 1e-9)
    }

    @Test
    fun `passage minuscule - jamais sous le plancher`() {
        val seg = 5L * DjMixer.OUT_SR
        assertEquals(
            DjMixer.MIN_FADE_S,
            DjMixer.clampFadeS(DjMixer.FADE_NORMAL_S, seg), 1e-9
        )
    }

    @Test
    fun `fondu deja court - jamais rallonge`() {
        val seg = 120L * DjMixer.OUT_SR
        assertEquals(3.0, DjMixer.clampFadeS(3.0, seg), 1e-9)
    }

    // ------------------------------------------------------------ fadeBars
    // Durées en MESURES, par technique, bornées par le passage sortant.

    @Test
    fun `fadeBars - coupe 2, blend 4, harmonique 4 ou 6 selon le passage`() {
        assertEquals(2, DjMixer.fadeBars(DjMixer.KIND_CUT, 32.0))
        assertEquals(4, DjMixer.fadeBars(DjMixer.KIND_NORMAL, 32.0))
        assertEquals(4, DjMixer.fadeBars(DjMixer.KIND_EQ, 32.0))
        assertEquals(4, DjMixer.fadeBars(DjMixer.KIND_DROP, 32.0))
        // Harmonique : 4 mesures sur un passage d'une minute (32 mesures),
        // 6 sur un long passage (≥ 48 mesures)
        assertEquals(4, DjMixer.fadeBars(DjMixer.KIND_HARMONIC, 32.0))
        assertEquals(6, DjMixer.fadeBars(DjMixer.KIND_HARMONIC, 48.0))
    }

    @Test
    fun `fadeBars - jamais plus d un cinquieme du passage, jamais moins d une mesure`() {
        // 12 mesures de passage : 20 % = 2,4 -> 2 mesures
        assertEquals(2, DjMixer.fadeBars(DjMixer.KIND_NORMAL, 12.0))
        // 4 mesures : 0,8 -> plancher 1
        assertEquals(1, DjMixer.fadeBars(DjMixer.KIND_HARMONIC, 4.0))
    }

    @Test
    fun `barSeconds - 128 BPM = 1,875 s`() {
        assertEquals(1.875, DjMixer.barSeconds(128f), 1e-9)
    }

    // ---------------------------------------------------------- splitRates
    // Calage PARTAGÉ : chaque deck fait la moitié du chemin (en log).

    @Test
    fun `splitRates - ecart de 3 pourcents partage en deux`() {
        val (rA, rB) = DjMixer.splitRates(128f, 124f)
        // Tempo cible = moyenne géométrique ~125,98 : A ralentit, B accélère
        assertTrue(rA < 1f && rB > 1f)
        assertEquals(128f * rA, 124f * rB, 1e-3f) // mêmes tempos effectifs
        assertTrue(rA >= DjMixer.MIN_LOCK_RATE && rB <= DjMixer.MAX_LOCK_RATE)
    }

    @Test
    fun `splitRates - jusqu a 8 pourcents d ecart, calable ; au-dela, tempos naturels`() {
        // 128 vs 118,5 (~7,7 %) : ~3,9 % chacun, encore dans la fenêtre
        val (rA, rB) = DjMixer.splitRates(128f, 118.5f)
        assertTrue(rA != 1f && rB != 1f)
        // 128 vs 100 : impossible sans désaccorder — chacun à son tempo
        assertEquals(1f to 1f, DjMixer.splitRates(128f, 100f))
    }

    @Test
    fun `splitRates - double et moitie de tempo admis pour l entrant`() {
        // 128 vs 64 : B en double-time (128), aucun étirement
        val (rA, rB) = DjMixer.splitRates(128f, 64f)
        assertEquals(1f, rA, EPS)
        assertEquals(1f, rB, EPS)
        assertEquals(1f to 1f, DjMixer.splitRates(0f, 120f))
    }

    // ----------------------------------------------------------- anchorFor
    // Ancre du passage : début du DROP le plus proche du meilleur passage.

    @Test
    fun `anchorFor - debut du drop a portee de phrase`() {
        // Phrase à 128 BPM = 7,5 s ; le drop commence 4 s après le meilleur
        // passage : c'est lui l'ancre, pas le premier beat du passage.
        val s = listOf(
            section(40_000L, 64_000L, StructureDetector.SectionKind.BUILD),
            section(64_000L, 120_000L, StructureDetector.SectionKind.DROP)
        )
        assertEquals(
            64_000L,
            DjMixer.anchorFor(60_000L, 60_000L, 61_000L, 128f, 240_000L, s)
        )
    }

    @Test
    fun `anchorFor - sans drop proche - premier beat du passage, sinon son debut`() {
        val far = listOf(
            section(0L, 100_000L, StructureDetector.SectionKind.INTRO),
            section(100_000L, 200_000L, StructureDetector.SectionKind.DROP)
        )
        assertEquals(
            61_000L,
            DjMixer.anchorFor(60_000L, 60_000L, 61_000L, 128f, 240_000L, far)
        )
        // Premier beat hors du passage : début du passage
        assertEquals(
            60_000L,
            DjMixer.anchorFor(60_000L, 60_000L, 10_000L, 128f, 240_000L, emptyList())
        )
    }

    // ------------------------------------------------ fadeSpec : structure
    // Technique choisie selon la section d'où l'on sort / où l'on entre.

    @Test
    fun `fadeSpec - on sort d un drop - jamais de sweep grave`() {
        val a = track("a", 128f, centroid = 3_000f) // sortant brillant : pool à sweep grave
        val b = track("b", 128f)
        repeat(3) { last ->
            val (_, kind) = DjMixer.fadeSpec(
                a, 1f, b, 1f, jumping = false, lastKind = last - 1,
                exitKind = StructureDetector.SectionKind.DROP
            )
            assertTrue(kind != DjMixer.KIND_DARK)
        }
    }

    @Test
    fun `fadeSpec - on sort d un break - jamais de coupe`() {
        // Deux morceaux percussifs et énergiques : pool avec coupe
        val a = track("a", 128f, energyMean = 0.2f, sustainRatio = 0.2f)
        val b = track("b", 128f, energyMean = 0.2f, sustainRatio = 0.2f)
        repeat(3) { last ->
            val (_, kind) = DjMixer.fadeSpec(
                a, 1f, b, 1f, jumping = false, lastKind = last - 1,
                exitKind = StructureDetector.SectionKind.BREAK
            )
            assertTrue(kind != DjMixer.KIND_CUT)
        }
    }

    @Test
    fun `preRoll - structure sans montee avant l ancre - zero`() {
        // Structure connue, pas de BUILD adjacente : un pré-roll partirait
        // n'importe où dans un couplet — le deck part sur son ancre.
        val s = listOf(
            section(0L, 60_000L, StructureDetector.SectionKind.BREAK),
            section(60_000L, 120_000L, StructureDetector.SectionKind.DROP)
        )
        assertEquals(0L, DjMixer.preRollMs(60_000L, 14_000L, 120f, s))
    }

    // ----------------------------------------------------------- fadeSpec

    @Test
    fun `saut manuel - fondu court neutre`() {
        val (s, kind) = DjMixer.fadeSpec(
            track("a", 128f), 1f, track("b", 128f),
            rate = 1f, jumping = true, lastKind = -1
        )
        assertEquals(DjMixer.FADE_JUMP_S, s, 1e-9)
        assertEquals(DjMixer.KIND_EQ, kind)
    }

    @Test
    fun `lockErr eleve - coupe courte`() {
        // 128 vs 100 : joué à son tempo naturel (rate 1), les tempos ne
        // sont pas verrouillés -> coupe + echo-out
        val rate = DjMixer.computeRate(128f, 100f)
        val (s, kind) = DjMixer.fadeSpec(
            track("a", 128f), 1f, track("b", 100f),
            rate = rate, jumping = false, lastKind = -1
        )
        assertEquals(DjMixer.FADE_CUT_S, s, 1e-9)
        assertEquals(DjMixer.KIND_CUT, kind)
    }

    @Test
    fun `tempos cales et tonalites compatibles - long blend harmonique`() {
        val rate = DjMixer.computeRate(128f, 126f)
        val (s, kind) = DjMixer.fadeSpec(
            track("a", 128f, camelot = "8A"), 1f,
            track("b", 126f, camelot = "8A"),
            rate = rate, jumping = false, lastKind = -1
        )
        assertEquals(DjMixer.FADE_LOCKED_HARMONIC_S, s, 1e-9)
        assertEquals(DjMixer.KIND_HARMONIC, kind)
    }

    @Test
    fun `moitie du tempo compte comme cale`() {
        // 140 vs 70 en half-time (rate 1) : verrouillé, pas de coupe
        val (_, kind) = DjMixer.fadeSpec(
            track("a", 140f), 1f, track("b", 70f),
            rate = 1f, jumping = false, lastKind = -1
        )
        assertTrue(kind != DjMixer.KIND_CUT)
    }

    @Test
    fun `tempos cales sans harmonie - jamais la meme technique deux fois`() {
        val a = track("a", 128f)
        val b = track("b", 128f)
        val (_, first) = DjMixer.fadeSpec(a, 1f, b, 1f, jumping = false, lastKind = -1)
        // Rejouer la même paire en déclarant `first` comme dernière
        // technique : le tirage doit en choisir une autre.
        val (_, second) = DjMixer.fadeSpec(a, 1f, b, 1f, jumping = false, lastKind = first)
        assertTrue(second != first)
    }

    @Test
    fun `bpm manquant - fondu normal neutre`() {
        val (s, kind) = DjMixer.fadeSpec(
            track("a", 0f), 1f, track("b", 128f),
            rate = 1f, jumping = false, lastKind = -1
        )
        assertEquals(DjMixer.FADE_NORMAL_S, s, 1e-9)
        assertEquals(DjMixer.KIND_EQ, kind)
    }

    // ---------------------------------------------------------- fadeSpecPro
    // Sélection « pro » (toggle Transitions pro) : la palette de fadeSpec,
    // plus le drop-swap de festival quand l'entrant a un vrai drop détecté
    // sur son ancre.

    private fun dropAt(startMs: Long) = listOf(
        StructureDetector.Section(
            0L, startMs, StructureDetector.SectionKind.BUILD
        ),
        StructureDetector.Section(
            startMs, startMs + 40_000L, StructureDetector.SectionKind.DROP
        )
    )

    @Test
    fun `pro - tempo non calable - coupe courte meme avec un drop`() {
        val rate = DjMixer.computeRate(128f, 100f)
        val (s, kind) = DjMixer.fadeSpecPro(
            track("a", 128f, energyMean = 0.2f), 1f,
            track("b", 100f, energyMean = 0.2f),
            rate = rate, jumping = false, lastKind = -1, dropStreak = 0,
            nextSections = dropAt(60_000L), anchorMs = 60_000L
        )
        assertEquals(DjMixer.FADE_CUT_S, s, 1e-9)
        assertEquals(DjMixer.KIND_CUT, kind)
    }

    @Test
    fun `pro - drop a une mesure de l ancre - drop swap`() {
        // Drop pile sur l'ancre : le cas nominal du pré-roll
        val (s, kind) = DjMixer.fadeSpecPro(
            track("a", 128f, energyMean = 0.2f), 1f,
            track("b", 128f, energyMean = 0.2f),
            rate = 1f, jumping = false, lastKind = -1, dropStreak = 0,
            nextSections = dropAt(60_000L), anchorMs = 60_000L
        )
        assertEquals(DjMixer.FADE_NORMAL_S, s, 1e-9)
        assertEquals(DjMixer.KIND_DROP, kind)
        // À ± une mesure (1 875 ms à 128 BPM) : encore un drop-swap
        val (_, k2) = DjMixer.fadeSpecPro(
            track("a", 128f, energyMean = 0.2f), 1f,
            track("b", 128f, energyMean = 0.2f),
            rate = 1f, jumping = false, lastKind = -1, dropStreak = 0,
            nextSections = dropAt(61_800L), anchorMs = 60_000L
        )
        assertEquals(DjMixer.KIND_DROP, k2)
        // Au-delà d'une mesure : plus de drop-swap
        val (_, k3) = DjMixer.fadeSpecPro(
            track("a", 128f, energyMean = 0.2f), 1f,
            track("b", 128f, energyMean = 0.2f),
            rate = 1f, jumping = false, lastKind = -1, dropStreak = 0,
            nextSections = dropAt(64_000L), anchorMs = 60_000L
        )
        assertTrue(k3 != DjMixer.KIND_DROP)
    }

    @Test
    fun `pro - entrant calme - jamais de drop swap`() {
        // Un drop-swap sur de l'ambient serait ridicule : l'entrant calme
        // retombe sur la palette douce de fadeSpec, drop détecté ou pas.
        val (_, kind) = DjMixer.fadeSpecPro(
            track("a", 128f, energyMean = 0.2f), 1f,
            track("b", 128f, energyMean = 0.05f),
            rate = 1f, jumping = false, lastKind = -1, dropStreak = 0,
            nextSections = dropAt(60_000L), anchorMs = 60_000L
        )
        assertTrue(kind != DjMixer.KIND_DROP)
    }

    @Test
    fun `pro - sortant calme - jamais de drop swap`() {
        // Le geste tient le SORTANT à plein volume pendant que l'entrant
        // monte dessous : sur de l'acoustique ou du chanté posé, c'est
        // intenable — même quand l'entrant, lui, a un vrai drop.
        val (_, kind) = DjMixer.fadeSpecPro(
            track("a", 128f, energyMean = 0.05f), 1f,
            track("b", 128f, energyMean = 0.2f),
            rate = 1f, jumping = false, lastKind = -1, dropStreak = 0,
            nextSections = dropAt(60_000L), anchorMs = 60_000L
        )
        assertTrue(kind != DjMixer.KIND_DROP)
    }

    @Test
    fun `pro - deux drops d affilee permis - le troisieme force un blend`() {
        val a = track("a", 128f, energyMean = 0.2f)
        val b = track("b", 128f, energyMean = 0.2f)
        // Après UN drop-swap : encore permis (le geste standard en festival)
        val (_, second) = DjMixer.fadeSpecPro(
            a, 1f, b, 1f, jumping = false, lastKind = DjMixer.KIND_DROP,
            dropStreak = 1, nextSections = dropAt(60_000L), anchorMs = 60_000L
        )
        assertEquals(DjMixer.KIND_DROP, second)
        // Après DEUX d'affilée : blend forcé (fadeSpec ne rend jamais DROP)
        val (_, third) = DjMixer.fadeSpecPro(
            a, 1f, b, 1f, jumping = false, lastKind = DjMixer.KIND_DROP,
            dropStreak = 2, nextSections = dropAt(60_000L), anchorMs = 60_000L
        )
        assertTrue(third != DjMixer.KIND_DROP)
    }

    @Test
    fun `pro - sans structure ou saut manuel - delegation exacte`() {
        // Vieille bibliothèque (pas de structure) : fadeSpecPro EST
        // fadeSpec — le mode pro reste sans risque.
        val a = track("a", 128f)
        val b = track("b", 126f, energyMean = 0.2f)
        val rate = DjMixer.computeRate(128f, 126f)
        assertEquals(
            DjMixer.fadeSpec(a, 1f, b, rate, jumping = false, lastKind = -1),
            DjMixer.fadeSpecPro(
                a, 1f, b, rate, jumping = false, lastKind = -1,
                dropStreak = 0, nextSections = emptyList(), anchorMs = 60_000L
            )
        )
        // Saut manuel : fondu court neutre, comme fadeSpec
        val (s, kind) = DjMixer.fadeSpecPro(
            a, 1f, b, rate, jumping = true, lastKind = -1,
            dropStreak = 0, nextSections = dropAt(60_000L), anchorMs = 60_000L
        )
        assertEquals(DjMixer.FADE_JUMP_S, s, 1e-9)
        assertEquals(DjMixer.KIND_EQ, kind)
    }

    // ------------------------------------------------------- drop-swap pur
    // Le chemin de mixage du KIND_DROP passe par des fonctions pures :
    // le « 1 » visé (dropSwapPhase) et les gains (dropGainA/B).

    @Test
    fun `dropSwapPhase - le 1 de mesure le plus proche de la fin du fondu`() {
        // Fin de fondu pile sur une mesure : le drop tombe là
        assertEquals(32.0, DjMixer.dropSwapPhase(32.0), 1e-9)
        // Fin hors grille : la frontière de mesure la plus proche
        assertEquals(32.0, DjMixer.dropSwapPhase(30.3), 1e-9)
        assertEquals(28.0, DjMixer.dropSwapPhase(29.9), 1e-9)
    }

    @Test
    fun `dropGains - montee plafonnee puis bascule nette`() {
        // Montée (st = 0) : sortant quasi plein, entrant plafonné à 0,5
        assertEquals(DjMixer.DROP_HOLD_A, DjMixer.dropGainA(0f), EPS)
        assertEquals(0f, DjMixer.dropGainB(0f, 0f), EPS)
        var x = 0f
        while (x <= 1f) {
            assertTrue(DjMixer.dropGainB(x, 0f) <= 0.5f + EPS)
            x += 0.05f
        }
        assertEquals(0.5f, DjMixer.dropGainB(1f, 0f), EPS)
        // Sur le « 1 » du drop : l'entrant claque à 1, le sortant est
        // coupé bien avant la fin de la rampe anti-clic (geste net)
        assertEquals(1f, DjMixer.dropGainB(0.9f, 1f), EPS)
        assertEquals(0f, DjMixer.dropGainA(1f), EPS)
        assertEquals(0f, DjMixer.dropGainA(0.125f), EPS)
        // Continuité au « 1 » : l'entrant repart de son plafond
        assertEquals(0.5f, DjMixer.dropGainB(1f, 1e-6f), 1e-3f)
    }

    // --------------------------------------------------- snapEndToStructure
    // La fin de passage d'un deck se cale sur une frontière de section
    // (idéalement la fin d'un temps fort) à ± une phrase. À 120 BPM sur un
    // morceau long, la phrase vaut 16 temps = 8 s.

    private fun section(start: Long, end: Long, kind: StructureDetector.SectionKind) =
        StructureDetector.Section(start, end, kind)

    @Test
    fun `sans structure ou sans bpm - fin inchangee`() {
        assertEquals(
            90_000L,
            DjMixer.snapEndToStructure(90_000L, 30_000L, 128f, 240_000L, emptyList())
        )
        val s = listOf(section(30_000L, 92_000L, StructureDetector.SectionKind.DROP))
        assertEquals(
            90_000L,
            DjMixer.snapEndToStructure(90_000L, 30_000L, 0f, 240_000L, s)
        )
    }

    @Test
    fun `fin calee sur la fin du temps fort la plus proche`() {
        val s = listOf(
            section(32_000L, 96_000L, StructureDetector.SectionKind.DROP),
            section(96_000L, 112_000L, StructureDetector.SectionKind.BREAK)
        )
        // 91 s : la fin du DROP (96 s) est à 5 s, dans la fenêtre de ± 8 s
        assertEquals(
            96_000L,
            DjMixer.snapEndToStructure(91_000L, 32_000L, 120f, 240_000L, s)
        )
    }

    @Test
    fun `le temps fort l emporte sur une frontiere plus proche`() {
        // Fin de BREAK à 2 s, fin de DROP à 6 s : le DJ sort sur la fin du
        // temps fort, pas sur la frontière la plus proche.
        val s = listOf(
            section(82_000L, 88_000L, StructureDetector.SectionKind.BREAK),
            section(88_000L, 96_000L, StructureDetector.SectionKind.DROP)
        )
        assertEquals(
            96_000L,
            DjMixer.snapEndToStructure(90_000L, 30_000L, 120f, 240_000L, s)
        )
    }

    @Test
    fun `hors fenetre d une phrase - fin inchangee`() {
        val s = listOf(section(32_000L, 106_000L, StructureDetector.SectionKind.DROP))
        // 106 s est à 16 s de la fin calculée : trop loin, on ne bouge pas
        assertEquals(
            90_000L,
            DjMixer.snapEndToStructure(90_000L, 32_000L, 120f, 240_000L, s)
        )
    }

    @Test
    fun `jamais sous 20 s de passage`() {
        // La seule frontière proche raccourcirait le passage à 16 s
        val s = listOf(section(40_000L, 86_000L, StructureDetector.SectionKind.DROP))
        assertEquals(
            92_000L,
            DjMixer.snapEndToStructure(92_000L, 70_000L, 120f, 240_000L, s)
        )
    }

    @Test
    fun `jamais au dela de la fin du morceau`() {
        // Frontière au-delà de durationMs (structure corrompue) : ignorée
        val s = listOf(section(200_000L, 241_000L, StructureDetector.SectionKind.DROP))
        assertEquals(
            236_000L,
            DjMixer.snapEndToStructure(236_000L, 100_000L, 120f, 240_000L, s)
        )
    }

    // ----------------------------------------------------------- preRollMs
    // Pré-roll du deck entrant d'une transition automatique : il démarre
    // ~la durée du fondu avant son ancre (arrondie à la mesure) pour que
    // son drop tombe à la FIN du fondu. À 120 BPM, la mesure vaut 2 s.

    @Test
    fun `pre-roll - sans bpm ou sans fondu - zero`() {
        assertEquals(0L, DjMixer.preRollMs(60_000L, 14_000L, 0f, emptyList()))
        assertEquals(0L, DjMixer.preRollMs(60_000L, 0L, 120f, emptyList()))
    }

    @Test
    fun `pre-roll - sans structure - fondu arrondi a la mesure`() {
        // 14 s à 120 BPM = pile 7 mesures
        assertEquals(
            14_000L, DjMixer.preRollMs(60_000L, 14_000L, 120f, emptyList())
        )
        // 13,2 s = 6,6 mesures -> 7 mesures (au plus proche)
        assertEquals(
            14_000L, DjMixer.preRollMs(60_000L, 13_200L, 120f, emptyList())
        )
        // 12,9 s = 6,45 mesures -> 6 mesures
        assertEquals(
            12_000L, DjMixer.preRollMs(60_000L, 12_900L, 120f, emptyList())
        )
    }

    @Test
    fun `pre-roll - moins d une demi-mesure de fondu - zero`() {
        assertEquals(0L, DjMixer.preRollMs(60_000L, 900L, 120f, emptyList()))
        // Pile une demi-mesure : une mesure entière
        assertEquals(2_000L, DjMixer.preRollMs(60_000L, 1_000L, 120f, emptyList()))
    }

    @Test
    fun `pre-roll - ancre trop tot - reduit aux mesures qui tiennent`() {
        // 5 s avant l'ancre : seules 2 mesures entières tiennent avant 0
        assertEquals(4_000L, DjMixer.preRollMs(5_000L, 14_000L, 120f, emptyList()))
        // Ancre au début du fichier : rien à rejouer avant
        assertEquals(0L, DjMixer.preRollMs(0L, 14_000L, 120f, emptyList()))
        assertEquals(0L, DjMixer.preRollMs(1_500L, 14_000L, 120f, emptyList()))
    }

    @Test
    fun `pre-roll - borne au debut de la BUILD adjacente`() {
        // BUILD 52..60 s, ancre à 60 s : les 14 s demandées sont bornées
        // aux 4 mesures qui ramènent pile au début de la montée.
        val s = listOf(
            section(30_000L, 52_000L, StructureDetector.SectionKind.BREAK),
            section(52_000L, 60_000L, StructureDetector.SectionKind.BUILD),
            section(60_000L, 100_000L, StructureDetector.SectionKind.DROP)
        )
        assertEquals(8_000L, DjMixer.preRollMs(60_000L, 14_000L, 120f, s))
        // Fin de BUILD à ± une mesure de l'ancre : même borne
        val s2 = listOf(
            section(52_000L, 58_500L, StructureDetector.SectionKind.BUILD),
            section(58_500L, 100_000L, StructureDetector.SectionKind.DROP)
        )
        // (60_000 - 52_000) / 2_000 = 4 mesures entières vers la montée
        assertEquals(8_000L, DjMixer.preRollMs(60_000L, 14_000L, 120f, s2))
    }

    @Test
    fun `pre-roll - BUILD longue - candidat rythmique intact`() {
        // La montée commence bien avant : la borne ne mord pas, le
        // candidat rythmique (7 mesures) s'applique tel quel.
        val s = listOf(
            section(20_000L, 60_000L, StructureDetector.SectionKind.BUILD),
            section(60_000L, 100_000L, StructureDetector.SectionKind.DROP)
        )
        assertEquals(14_000L, DjMixer.preRollMs(60_000L, 14_000L, 120f, s))
    }

    @Test
    fun `pre-roll - ancre en plein drop sans build avant - zero`() {
        // Structure dégénérée : l'ancre est en plein milieu d'un temps
        // fort, sans montée adjacente — entrer en plein drop filtré vaut
        // mieux que rejouer autre chose : pas de pré-roll.
        val s = listOf(
            section(0L, 40_000L, StructureDetector.SectionKind.INTRO),
            section(40_000L, 120_000L, StructureDetector.SectionKind.DROP)
        )
        assertEquals(0L, DjMixer.preRollMs(60_000L, 14_000L, 120f, s))
    }

    // ------------------------------------------------------ nextPhraseBeat
    // Quantisation de phrase (16 temps) du départ des transitions :
    // l'échelon au-dessus de la mesure.

    @Test
    fun `nextPhraseBeat - prochain 1 de phrase`() {
        assertEquals(48.0, DjMixer.nextPhraseBeat(33.0, 0.0), 1e-9)
        // Déjà pile sur une phrase : on ne repousse pas
        assertEquals(32.0, DjMixer.nextPhraseBeat(32.0, 0.0), 1e-9)
        assertEquals(16.0, DjMixer.nextPhraseBeat(0.5, 0.0), 1e-9)
    }

    @Test
    fun `nextPhraseBeat - grille recalee du pre-roll`() {
        // Pré-roll de 7 mesures = 28 temps : la grille de phrases du deck
        // est décalée de 28 % 16 = 12 temps (phrases à 12, 28, 44...)
        assertEquals(44.0, DjMixer.nextPhraseBeat(33.0, 12.0), 1e-9)
        assertEquals(28.0, DjMixer.nextPhraseBeat(28.0, 12.0), 1e-9)
        assertEquals(12.0, DjMixer.nextPhraseBeat(3.0, 12.0), 1e-9)
    }

    // ------------------------------------------------------- bassSwapPhase
    // Le « 1 » du swap net de basses : dernière frontière de mesure du
    // sortant avant la fin du fondu.

    @Test
    fun `bassSwapPhase - une mesure avant la fin du fondu`() {
        // Fin de fondu pile sur une mesure : swap une mesure avant
        assertEquals(28.0, DjMixer.bassSwapPhase(32.0), 1e-9)
        // Fin de fondu hors grille : frontière de mesure la plus proche
        // de « fin - une mesure », toujours avant la fin
        assertEquals(28.0, DjMixer.bassSwapPhase(30.3), 1e-9)
        assertEquals(28.0, DjMixer.bassSwapPhase(33.9), 1e-9)
    }

    // ------------------------------------------------- crossfader manuel
    // Le fader du panneau « Performance » remplace la progression
    // temporelle du fondu : mêmes courbes equal-power que le moteur, et
    // les mêmes exigences — pas de creux de volume au milieu, pas de
    // saut aux extrêmes.

    @Test
    fun `fadeGains - extremes francs`() {
        // Fader à gauche : deck A plein, B muet — et symétriquement
        assertEquals(1f, DjMixer.fadeGainA(0f), EPS)
        assertEquals(0f, DjMixer.fadeGainB(0f), EPS)
        assertEquals(0f, DjMixer.fadeGainA(1f), EPS)
        assertEquals(1f, DjMixer.fadeGainB(1f), EPS)
    }

    @Test
    fun `fadeGains - equal power sur toute la course`() {
        // gA² + gB² = 1 : la puissance perçue ne creuse pas au milieu
        var p = 0f
        while (p <= 1f) {
            val gA = DjMixer.fadeGainA(p)
            val gB = DjMixer.fadeGainB(p)
            assertEquals(1f, gA * gA + gB * gB, 1e-3f)
            p += 0.05f
        }
    }

    @Test
    fun `fadeGains - position hors bornes ramenee dans la course`() {
        // Un geste qui déborde du slider ne doit pas inverser les gains
        assertEquals(DjMixer.fadeGainA(0f), DjMixer.fadeGainA(-0.5f), EPS)
        assertEquals(DjMixer.fadeGainB(1f), DjMixer.fadeGainB(1.5f), EPS)
    }

    @Test
    fun `soloGain - plein volume jusqu'a mi-course puis extinction`() {
        // Hors transition, un seul deck : pousser le fader vers B ne
        // déclenche rien, il n'atténue que le deck actif
        assertEquals(1f, DjMixer.soloGain(0f), EPS)
        assertEquals(1f, DjMixer.soloGain(0.25f), EPS)
        assertEquals(1f, DjMixer.soloGain(0.5f), EPS)
        assertEquals(0f, DjMixer.soloGain(1f), EPS)
        // Décroissance monotone sur la seconde moitié (pas de rebond)
        var prev = 1f
        var p = 0.5f
        while (p <= 1f) {
            val g = DjMixer.soloGain(p)
            assertTrue(g <= prev + EPS)
            prev = g
            p += 0.05f
        }
    }

    @Test
    fun `blendGain - la rampe de reprise va du manuel a l'auto sans saut`() {
        // blend 1 = tout manuel, 0 = courbe du moteur, ½ = à mi-chemin
        assertEquals(0.9f, DjMixer.blendGain(0.2f, 0.9f, 1f), EPS)
        assertEquals(0.2f, DjMixer.blendGain(0.2f, 0.9f, 0f), EPS)
        assertEquals(0.55f, DjMixer.blendGain(0.2f, 0.9f, 0.5f), EPS)
        // Un blend qui déborde (rampe mal bornée) reste aux extrémités
        assertEquals(0.9f, DjMixer.blendGain(0.2f, 0.9f, 1.4f), EPS)
        assertEquals(0.2f, DjMixer.blendGain(0.2f, 0.9f, -0.1f), EPS)
    }
}
