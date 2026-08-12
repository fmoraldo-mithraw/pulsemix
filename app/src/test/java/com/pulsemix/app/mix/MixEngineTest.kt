package com.pulsemix.app.mix

import com.pulsemix.app.data.PlayHistory
import com.pulsemix.app.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des fonctions pures du moteur de mix. Elles décident quels
 * morceaux entrent dans un mix et lesquels sont des doublons : une
 * régression y est silencieuse à l'usage (un morceau joué deux fois, un
 * autre jamais choisi) et donc difficile à repérer sans filet.
 *
 * Rien ici ne touche à Android : ni PlayHistory, ni org.json.
 */
class MixEngineTest {

    private val EPS = 1e-6f

    private fun track(
        uri: String,
        title: String,
        artist: String = "",
        durationMs: Long = 0L
    ) = Track(uri = uri, title = title, artist = artist, durationMs = durationMs)

    // ------------------------------------------------------------ camelot

    @Test
    fun `meme cle donne 1`() {
        assertEquals(1f, MixEngine.camelotScore("8A", "8A"), EPS)
    }

    @Test
    fun `relative majeur mineur donne 0_8`() {
        assertEquals(0.8f, MixEngine.camelotScore("8A", "8B"), EPS)
    }

    @Test
    fun `voisine sur la roue donne 0_8`() {
        assertEquals(0.8f, MixEngine.camelotScore("8A", "9A"), EPS)
        assertEquals(0.8f, MixEngine.camelotScore("9A", "8A"), EPS)
    }

    @Test
    fun `la roue boucle entre 12 et 1`() {
        assertEquals(0.8f, MixEngine.camelotScore("12A", "1A"), EPS)
        assertEquals(0.8f, MixEngine.camelotScore("1A", "12A"), EPS)
    }

    @Test
    fun `cles eloignees donnent 0`() {
        assertEquals(0f, MixEngine.camelotScore("1A", "6A"), EPS)
    }

    @Test
    fun `cle inconnue donne 0`() {
        assertEquals(0f, MixEngine.camelotScore("--", "8A"), EPS)
        assertEquals(0f, MixEngine.camelotScore("8A", "--"), EPS)
        assertEquals(0f, MixEngine.camelotScore("", "8A"), EPS)
        assertEquals(0f, MixEngine.camelotScore("xA", "8A"), EPS)
    }

    // ------------------------------------------------------------- bandes

    @Test
    fun `les bandes de tempo se suivent sans trou`() {
        assertEquals(MixEngine.Band.CALME, MixEngine.bandOf(80f))
        assertEquals(MixEngine.Band.GROOVE, MixEngine.bandOf(95f))
        assertEquals(MixEngine.Band.DANCE, MixEngine.bandOf(115f))
        assertEquals(MixEngine.Band.INTENSE, MixEngine.bandOf(135f))
    }

    // ------------------------------------------------------------- genres

    @Test
    fun `le genre est normalise en minuscules sans suffixe`() {
        assertEquals("rock", MixEngine.normalizeGenre("Rock"))
        assertEquals("rock", MixEngine.normalizeGenre("  ROCK  "))
        assertEquals("rock", MixEngine.normalizeGenre("Rock;Pop"))
        assertEquals("rock", MixEngine.normalizeGenre("Rock/Metal"))
        assertEquals("", MixEngine.normalizeGenre(null))
    }

    // ---------------------------------------------------------- normTitle

    @Test
    fun `le numero de piste et l extension disparaissent`() {
        assertEquals(
            MixEngine.normTitle("le bien qui fait mal"),
            MixEngine.normTitle("03 - Le Bien Qui Fait Mal.mp3")
        )
    }

    @Test
    fun `l habillage youtube disparait`() {
        assertEquals(
            MixEngine.normTitle("Believer"),
            MixEngine.normTitle("Believer (Official Video)")
        )
        assertEquals(
            MixEngine.normTitle("Believer"),
            MixEngine.normTitle("Believer [Lyrics]")
        )
    }

    @Test
    fun `le suffixe d identifiant youtube disparait`() {
        assertEquals(
            MixEngine.normTitle("Believer"),
            MixEngine.normTitle("Believer [dQw4w9WgXcQ]")
        )
    }

    @Test
    fun `les accents et la ponctuation ne comptent pas`() {
        assertEquals(
            MixEngine.normTitle("Ou est passee ma tete"),
            MixEngine.normTitle("Où est passée ma tête !")
        )
    }

    @Test
    fun `deux chansons differentes ne se confondent pas`() {
        assertNotEquals(
            MixEngine.normTitle("Believer"),
            MixEngine.normTitle("Thunder")
        )
    }

    // ------------------------------------------------------------ dupKeys

    @Test
    fun `deux copies de la meme chanson partagent une cle`() {
        val a = track("content://a", "03 - Le Bien Qui Fait Mal.mp3", "Downloads", 245_000)
        val b = track("content://b", "le bien qui fait mal", "Mozart l'Opéra Rock", 246_500)
        val ka = MixEngine.dupKeys(a)
        val kb = MixEngine.dupKeys(b)
        assertTrue(
            "les deux copies devraient se reconnaître",
            ka.any { it in kb }
        )
    }

    @Test
    fun `deux chansons distinctes ne partagent aucune cle`() {
        val a = track("content://a", "Believer", "Imagine Dragons", 204_000)
        val b = track("content://b", "Thunder", "Imagine Dragons", 187_000)
        assertTrue(MixEngine.dupKeys(a).none { it in MixEngine.dupKeys(b) })
    }

    @Test
    fun `un titre vide retombe sur l uri seule`() {
        val t = track("content://a", "")
        assertEquals(listOf("content://a"), MixEngine.dupKeys(t))
    }

    @Test
    fun `l uri est toujours une cle`() {
        val t = track("content://a", "Believer", "Imagine Dragons", 204_000)
        assertTrue("content://a" in MixEngine.dupKeys(t))
    }

    // ------------------------------------------------- rotation des lectures

    @Test
    fun `un morceau jamais joue pese trois fois plus au tirage`() {
        // URIs propres à ce test : PlayHistory est un singleton, on ne
        // touche pas aux morceaux des autres tests.
        val fresh = track("content://rot-fresh", "Jamais joué", "X", 200_000)
        assertEquals(3f, MixEngine.drawWeight(fresh), 1e-4f)
    }

    @Test
    fun `un morceau sur-joue et recent ne pese presque plus rien`() {
        // Compteur écrasant : quel que soit ce que les autres tests ont
        // laissé dans le singleton, ce morceau sature overplayPenalty à 1.
        // Même ordre de grandeur que le « test://hot » de PlayHistoryTest,
        // exprès : chacun reste ~8x au-dessus de la moyenne même quand
        // l'autre est déjà importé, quel que soit l'ordre d'exécution.
        // Le record() final marque la lecture récente (penalty 48 h à ~1).
        PlayHistory.importCounts(
            mapOf(
                "content://rot-hot" to 100_000,
                "content://rot-avg1" to 1, "content://rot-avg2" to 1,
                "content://rot-avg3" to 1, "content://rot-avg4" to 1,
                "content://rot-avg5" to 1
            )
        )
        PlayHistory.record("content://rot-hot")
        val hot = track("content://rot-hot", "Trop joué", "X", 200_000)
        // 1 (déjà joué) − 0,8 (sur-joué) − 0,6 (récent) → plancher 0,1
        assertEquals(0.1f, MixEngine.drawWeight(hot), 1e-4f)
        // Et au coût d'enchaînement, ce morceau doit perdre contre un
        // jamais-joué équivalent malgré un enchaînement légèrement moins bon.
        val prev = track("content://rot-prev", "Précédent", "Y", 200_000)
            .copy(bpm = 130f, camelot = "8A", energyMean = 0.5f, analyzed = true)
        val hotGood = hot.copy(bpm = 130f, camelot = "8A", energyMean = 0.5f, analyzed = true)
        val freshOk = track("content://rot-neuf", "Neuf", "Z", 200_000)
            .copy(bpm = 133f, camelot = "7A", energyMean = 0.5f, analyzed = true)
        assertTrue(
            MixEngine.cost(prev, freshOk, ascending = true) <
                MixEngine.cost(prev, hotGood, ascending = true)
        )
    }

    // ------------------------------------------- continuité d'enchaînement

    private fun chained(
        uri: String,
        bpm: Float,
        camelot: String,
        energy: Float,
        genre: String = "pop"
    ) = Track(
        uri = uri, title = uri, artist = "", durationMs = 240_000L,
        bpm = bpm, camelot = camelot, energyMean = energy, genre = genre,
        analyzed = true
    )

    @Test
    fun `un grand saut d energie penalise meme a genre identique`() {
        val prev = chained("content://a", 130f, "8A", 0.80f)
        // Énergie proche mais tempo et clé un peu moins bons…
        val close = chained("content://b", 133f, "7A", 0.75f)
        // …contre tempo et clé parfaits mais énergie aux antipodes :
        // la continuité d'énergie doit l'emporter.
        val far = chained("content://c", 130f, "8A", 0.15f)
        assertTrue(
            MixEngine.cost(prev, close, ascending = true) <
                MixEngine.cost(prev, far, ascending = true)
        )
    }

    @Test
    fun `une petite variation d energie reste preferable au reste`() {
        val prev = chained("content://a", 130f, "8A", 0.60f)
        // Le saut d'énergie ne doit pas écraser tempo/tonalité pour des
        // écarts ordinaires : ici l'énergie quasi identique mais tout le
        // reste mauvais doit perdre contre un bon enchaînement.
        val good = chained("content://b", 131f, "8A", 0.52f)
        val bad = chained("content://c", 100f, "3B", 0.60f)
        assertTrue(
            MixEngine.cost(prev, good, ascending = true) <
                MixEngine.cost(prev, bad, ascending = true)
        )
    }

    // ----------------------------------------------- plancher d'une heure

    /** Bibliothèque synthétique analysée : tempos étalés sur toutes les
     *  bandes, tonalités voisines, titres tous distincts (pas de dédup). */
    private fun library(n: Int) = List(n) { i ->
        Track(
            uri = "content://t$i",
            title = "Morceau $i",
            artist = "Artiste ${i % 7}",
            durationMs = 240_000L,
            bpm = 80f + (i % 24) * 3f,
            camelot = "${1 + i % 12}${if (i % 2 == 0) "A" else "B"}",
            energyMean = 0.2f + (i % 10) * 0.07f,
            energyPeak = 0.4f + (i % 10) * 0.05f,
            centroid = 1000f + (i % 8) * 300f,
            onsetRate = 1f + (i % 5) * 0.4f,
            segmentMs = 60_000L,
            analyzed = true
        )
    }

    private fun planMs(p: MixEngine.MixPlan, dj: Boolean) =
        p.phases.sumOf { ph ->
            ph.tracks.sumOf { t ->
                (if (dj) t.segmentMs else t.durationMs).coerceAtLeast(60_000L)
            }
        }

    @Test
    fun `sans cible tous les plans durent au moins une heure`() {
        val all = library(40) // 40 × 4 min = 160 min de matière
        val plans = MixEngine.proposeMixes(all)
        assertTrue(plans.isNotEmpty())
        for (p in plans) {
            assertTrue(
                "plan ${p.id} trop court : ${planMs(p, dj = false) / 60_000} min",
                planMs(p, dj = false) >= MixEngine.MIN_MIX_MS
            )
        }
    }

    @Test
    fun `une cible sous l heure est remontee au plancher`() {
        val all = library(40)
        val plans = MixEngine.proposeMixes(all, targetMinutes = 30)
        assertTrue(plans.isNotEmpty())
        for (p in plans) {
            assertTrue(
                "plan ${p.id} trop court : ${planMs(p, dj = false) / 60_000} min",
                planMs(p, dj = false) >= MixEngine.MIN_MIX_MS
            )
        }
    }

    @Test
    fun `en mode dj les segments courts sont compenses en nombre`() {
        // 90 morceaux à segments d'1 min : il en faut ~60 par set
        val all = library(90)
        val plans = MixEngine.proposeMixes(all, dj = true)
        assertTrue(plans.isNotEmpty())
        for (p in plans) {
            assertTrue(
                "set DJ ${p.id} trop court : ${planMs(p, dj = true) / 60_000} min",
                planMs(p, dj = true) >= MixEngine.MIN_MIX_MS
            )
        }
    }

    @Test
    fun `le mix similaire atteint aussi l heure`() {
        val all = library(40)
        val plan = MixEngine.similarPlan(all, all[0])
        assertTrue(plan != null && planMs(plan, dj = false) >= MixEngine.MIN_MIX_MS)
    }

    @Test
    fun `bibliotheque trop petite - le plan prend tout sans crasher`() {
        // 6 morceaux de 4 min : impossible d'atteindre l'heure, le plan
        // doit juste prendre ce qu'il y a.
        val all = library(6)
        val plans = MixEngine.proposeMixes(all)
        assertTrue(plans.isNotEmpty())
    }
}
