package com.pulsemix.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'insertion ordonnée a remplacé un tri complet à chaque morceau ajouté.
 * Ces tests vérifient qu'elle donne exactement le même résultat : même
 * ordre, aucun morceau perdu ni dupliqué.
 */
class SortedTracksTest {

    private fun t(uri: String, title: String) =
        Track(uri = uri, title = title, artist = "", durationMs = 0L)

    private fun titles(list: List<Track>) = list.map { it.title }

    private fun assertTrie(list: List<Track>) {
        val keys = list.map { SortedTracks.keyOf(it) }
        assertEquals("la liste doit rester triée", keys.sorted(), keys)
    }

    // ----------------------------------------------------------------- put

    @Test
    fun `les ajouts arrivent dans l ordre quel que soit l ordre d insertion`() {
        var list = emptyList<Track>()
        for ((i, titre) in listOf("Delta", "alpha", "Charlie", "bravo").withIndex()) {
            list = SortedTracks.put(list, t("u$i", titre))
        }
        assertEquals(listOf("alpha", "bravo", "Charlie", "Delta"), titles(list))
    }

    @Test
    fun `l insertion donne le meme resultat qu un tri complet`() {
        val source = (1..200).map { t("u$it", "Titre ${(it * 37) % 200}") }
        var insere = emptyList<Track>()
        for (track in source) insere = SortedTracks.put(insere, track)
        assertEquals(
            titles(SortedTracks.sorted(source)),
            titles(insere)
        )
    }

    @Test
    fun `remplacer un morceau ne le duplique pas`() {
        var list = SortedTracks.put(emptyList(), t("u1", "Believer"))
        list = SortedTracks.put(list, t("u1", "Believer"))
        assertEquals(1, list.size)
    }

    @Test
    fun `un titre corrige change de place`() {
        var list = emptyList<Track>()
        list = SortedTracks.put(list, t("u1", "alpha"))
        list = SortedTracks.put(list, t("u2", "charlie"))
        list = SortedTracks.put(list, t("u3", "zoulou"))
        // « charlie » corrigé en « bravo » : il doit remonter d'un rang
        list = SortedTracks.put(list, t("u2", "bravo"))
        assertEquals(listOf("alpha", "bravo", "zoulou"), titles(list))
        assertEquals(3, list.size)
        assertTrie(list)
    }

    @Test
    fun `la casse ne compte pas dans l ordre`() {
        var list = emptyList<Track>()
        list = SortedTracks.put(list, t("u1", "ZOULOU"))
        list = SortedTracks.put(list, t("u2", "alpha"))
        assertEquals(listOf("alpha", "ZOULOU"), titles(list))
    }

    @Test
    fun `deux homonymes gardent leur ordre d arrivee`() {
        var list = emptyList<Track>()
        list = SortedTracks.put(list, t("premier", "Believer"))
        list = SortedTracks.put(list, t("second", "Believer"))
        assertEquals(listOf("premier", "second"), list.map { it.uri })
    }

    @Test
    fun `aucun morceau n est perdu sur une longue serie`() {
        var list = emptyList<Track>()
        repeat(500) { list = SortedTracks.put(list, t("u$it", "Titre ${(it * 7) % 500}")) }
        assertEquals(500, list.size)
        assertEquals(500, list.map { it.uri }.toSet().size)
        assertTrie(list)
    }

    // -------------------------------------------------------------- update

    @Test
    fun `une modification sans changement de titre reste en place`() {
        var list = emptyList<Track>()
        list = SortedTracks.put(list, t("u1", "alpha"))
        list = SortedTracks.put(list, t("u2", "bravo"))
        val out = SortedTracks.update(list, "u1") { it.copy(favorite = true) }
        assertEquals(listOf("alpha", "bravo"), titles(out))
        assertTrue(out.first { it.uri == "u1" }.favorite)
    }

    @Test
    fun `une modification de titre replace le morceau`() {
        var list = emptyList<Track>()
        list = SortedTracks.put(list, t("u1", "alpha"))
        list = SortedTracks.put(list, t("u2", "bravo"))
        list = SortedTracks.put(list, t("u3", "charlie"))
        val out = SortedTracks.update(list, "u1") { it.copy(title = "delta") }
        assertEquals(listOf("bravo", "charlie", "delta"), titles(out))
        assertEquals(3, out.size)
        assertTrie(out)
    }

    @Test
    fun `une uri inconnue laisse la liste intacte`() {
        val list = SortedTracks.put(emptyList(), t("u1", "alpha"))
        val out = SortedTracks.update(list, "inexistant") { it.copy(title = "zzz") }
        assertEquals(list, out)
    }

    @Test
    fun `la modification ne touche que le morceau vise`() {
        var list = emptyList<Track>()
        list = SortedTracks.put(list, t("u1", "alpha"))
        list = SortedTracks.put(list, t("u2", "bravo"))
        val out = SortedTracks.update(list, "u2") { it.copy(excluded = true) }
        assertTrue(out.first { it.uri == "u2" }.excluded)
        assertTrue(!out.first { it.uri == "u1" }.excluded)
    }

    // ------------------------------------------------------ insertionPoint

    @Test
    fun `le rang d insertion se place apres les titres egaux`() {
        val list = listOf(t("a", "alpha"), t("b", "bravo"), t("c", "bravo"))
        assertEquals(0, SortedTracks.insertionPoint(list, "aaa"))
        assertEquals(1, SortedTracks.insertionPoint(list, "alphaa"))
        assertEquals(3, SortedTracks.insertionPoint(list, "bravo"))
        assertEquals(3, SortedTracks.insertionPoint(list, "zoulou"))
    }

    @Test
    fun `le rang d insertion dans une liste vide est zero`() {
        assertEquals(0, SortedTracks.insertionPoint(emptyList(), "quoi que ce soit"))
    }
}
