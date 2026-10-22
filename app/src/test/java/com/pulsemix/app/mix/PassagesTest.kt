package com.pulsemix.app.mix

import com.pulsemix.app.analysis.PhraseProfile
import com.pulsemix.app.analysis.StructureDetector
import com.pulsemix.app.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Passages par rôle (Passages) : dérivation depuis la structure et le
 * profil par phrase, garde-fous, choix selon l'humeur, refus, rotation.
 *
 * Morceau synthétique à 128 BPM (phrase = 7,5 s), 5 min, premier beat à
 * 0 : intro 0-15 s, montée 15-30 s, drop 30-90 s (= passage fort),
 * break 90-120 s, montée 120-135 s, second drop (plus retenu) 135-195 s,
 * outro 195-300 s.
 */
class PassagesTest {

    private fun sections() = listOf(
        StructureDetector.Section(0L, 15_000L, StructureDetector.SectionKind.INTRO),
        StructureDetector.Section(15_000L, 30_000L, StructureDetector.SectionKind.BUILD),
        StructureDetector.Section(30_000L, 90_000L, StructureDetector.SectionKind.DROP),
        StructureDetector.Section(90_000L, 120_000L, StructureDetector.SectionKind.BREAK),
        StructureDetector.Section(120_000L, 135_000L, StructureDetector.SectionKind.BUILD),
        StructureDetector.Section(135_000L, 195_000L, StructureDetector.SectionKind.DROP),
        StructureDetector.Section(195_000L, 300_000L, StructureDetector.SectionKind.OUTRO)
    )

    /** Une phrase par 7,5 s : 40 phrases pour 300 s. */
    private fun profile(): List<PhraseProfile.Phrase> {
        val out = ArrayList<PhraseProfile.Phrase>()
        for (k in 0 until 40) {
            val s = k * 7_500L
            out.add(
                when {
                    s < 15_000L -> PhraseProfile.Phrase(0.30f, 0.30f, 0.50f)
                    s < 30_000L -> PhraseProfile.Phrase(0.65f, 0.40f, 0.50f)
                    s < 90_000L -> PhraseProfile.Phrase(0.95f, 0.70f, 0.40f)
                    s < 120_000L -> PhraseProfile.Phrase(0.40f, 0.20f, 0.70f)
                    s < 135_000L -> PhraseProfile.Phrase(0.65f, 0.40f, 0.50f)
                    s < 195_000L -> PhraseProfile.Phrase(0.70f, 0.70f, 0.40f)
                    else -> PhraseProfile.Phrase(0.30f, 0.30f, 0.50f)
                }
            )
        }
        return out
    }

    private fun track(
        durationMs: Long = 300_000L,
        structure: String = StructureDetector.encode(sections()),
        phraseProfile: String = PhraseProfile.encode(profile()),
        refused: String = ""
    ) = Track(
        uri = "test://passages", title = "Passages", artist = "", durationMs = durationMs,
        bpm = 128f, bestStartMs = 30_000L, segmentMs = 60_000L, firstBeatMs = 0L,
        analyzed = true, structure = structure, phraseProfile = phraseProfile,
        passageRefused = refused
    )

    private fun role(list: List<Passages.Passage>, r: Passages.Role) =
        list.firstOrNull { it.role == r }

    @Test
    fun `fort - le passage de l analyse`() {
        val f = Passages.fort(track())
        assertEquals(Passages.Role.FORT, f.role)
        assertEquals(30_000L, f.startMs)
        assertEquals(90_000L, f.endMs)
    }

    @Test
    fun `candidats - montee, groove et calme aux bons endroits`() {
        val c = Passages.candidates(track())
        val montee = role(c, Passages.Role.MONTEE)!!
        assertEquals(15_000L, montee.startMs)
        assertEquals(90_000L, montee.endMs)
        // La seconde montée et le second drop, plus retenu (0,70 < seuil
        // fort), forment la suite d'énergie moyenne la plus longue et la
        // plus stable : le groove
        val groove = role(c, Passages.Role.GROOVE)!!
        assertEquals(120_000L, groove.startMs)
        assertEquals(195_000L, groove.endMs)
        // Le break (médiums en relief, section BREAK) l'emporte sur l'outro
        // plus long mais plat
        val calme = role(c, Passages.Role.CALME)!!
        assertEquals(90_000L, calme.startMs)
        assertEquals(120_000L, calme.endMs)
        assertEquals(3, c.size)
    }

    @Test
    fun `candidats - jamais sur le passage fort, jamais dans la queue`() {
        val t = track()
        val f = Passages.fort(t)
        for (p in Passages.candidates(t)) {
            if (p.role != Passages.Role.MONTEE) {
                assertTrue(p.endMs <= f.startMs || p.startMs >= f.endMs)
            }
            assertTrue(p.endMs <= t.durationMs - Passages.END_MARGIN_MS)
            assertTrue(p.lengthMs >= Passages.MIN_PHRASES * 7_500L)
        }
    }

    @Test
    fun `garde-fous - morceau court, non analyse, sans matiere`() {
        assertTrue(Passages.candidates(track(durationMs = 170_000L)).isEmpty())
        assertTrue(Passages.candidates(track().copy(analyzed = false)).isEmpty())
        assertTrue(Passages.candidates(track(structure = "", phraseProfile = "")).isEmpty())
    }

    @Test
    fun `sans profil - la structure seule suffit`() {
        val c = Passages.candidates(track(phraseProfile = ""))
        assertEquals(90_000L, role(c, Passages.Role.CALME)!!.startMs)
        assertEquals(15_000L, role(c, Passages.Role.MONTEE)!!.startMs)
        // Les montées (énergie moyenne déduite) donnent le groove
        assertTrue(role(c, Passages.Role.GROOVE) != null)
    }

    @Test
    fun `choix - le peak ne joue que le fort, quoi qu il ait joue avant`() {
        val t = track()
        assertEquals(Passages.Role.FORT, Passages.choose(t, Passages.Mood.FORT, null).role)
        assertEquals(
            Passages.Role.FORT,
            Passages.choose(t, Passages.Mood.FORT, Passages.Role.FORT).role
        )
    }

    @Test
    fun `choix - preferences par humeur et rotation`() {
        val t = track()
        assertEquals(Passages.Role.CALME, Passages.choose(t, Passages.Mood.DOUX, null).role)
        // Déjà joué calme la dernière fois : le groove prend le relais
        assertEquals(
            Passages.Role.GROOVE,
            Passages.choose(t, Passages.Mood.DOUX, Passages.Role.CALME).role
        )
        assertEquals(Passages.Role.MONTEE, Passages.choose(t, Passages.Mood.MONTEE, null).role)
        assertEquals(Passages.Role.GROOVE, Passages.choose(t, Passages.Mood.GROOVE, null).role)
        assertEquals(Passages.Role.FORT, Passages.choose(t, Passages.Mood.VARIE, null).role)
        assertEquals(
            Passages.Role.GROOVE,
            Passages.choose(t, Passages.Mood.VARIE, Passages.Role.FORT).role
        )
    }

    @Test
    fun `choix - refus de l utilisateur, et repli sur le fort`() {
        val t = track(refused = Passages.encodeRefused(setOf(Passages.Role.CALME)))
        assertEquals(setOf(Passages.Role.CALME), Passages.refused(t))
        assertEquals(Passages.Role.GROOVE, Passages.choose(t, Passages.Mood.DOUX, null).role)
        // Rien d'autre que le fort : toujours le fort, sans rotation
        val short = track(durationMs = 150_000L)
        assertEquals(Passages.Role.FORT, Passages.choose(short, Passages.Mood.DOUX, null).role)
        assertEquals(
            Passages.Role.FORT,
            Passages.choose(short, Passages.Mood.DOUX, Passages.Role.FORT).role
        )
        assertNull(role(Passages.candidates(short), Passages.Role.CALME))
    }

    @Test
    fun `encodage des refus - stable et tolerant`() {
        assertEquals("", Passages.encodeRefused(emptySet()))
        assertEquals(
            "GROOVE,CALME",
            Passages.encodeRefused(setOf(Passages.Role.CALME, Passages.Role.GROOVE))
        )
        assertEquals(
            setOf(Passages.Role.GROOVE),
            Passages.refused(track(refused = "GROOVE, inconnu"))
        )
        assertEquals("1:30 → 2:00", Passages.describe(Passages.Passage(Passages.Role.CALME, 90_000L, 120_000L)))
    }
}
