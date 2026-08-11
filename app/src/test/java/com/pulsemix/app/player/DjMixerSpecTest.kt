package com.pulsemix.app.player

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
    fun `bpm proches - rate proportionnel dans les 8 pourcents`() {
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
    fun `ecart trop grand - rate borne aux 8 pourcents`() {
        // 128 vs 100 : ni direct ni double/moitié ne rentre dans ±8 % ;
        // le rate est borné à la limite du pitch fader (1.08), et c'est
        // fadeSpec qui transformera ce non-calage en coupe (CUT).
        assertEquals(1.08f, DjMixer.computeRate(128f, 100f), EPS)
        assertEquals(0.92f, DjMixer.computeRate(100f, 128f), EPS)
    }

    @Test
    fun `bpm inconnu - rate neutre`() {
        assertEquals(1f, DjMixer.computeRate(0f, 120f), EPS)
        assertEquals(1f, DjMixer.computeRate(120f, 0f), EPS)
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
        // 128 vs 100 : même avec le rate borné à 1.08 (108 BPM effectifs),
        // les tempos ne sont pas verrouillés -> coupe + echo-out
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
}
