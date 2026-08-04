package com.pulsemix.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Une seule ligne par morceau dans la liste des corrections, et un
 * « revenir à l'original » qui ramène bien au tag de départ même après
 * plusieurs corrections successives.
 */
class AppliedTagsTest {

    private val MAX = 300

    private fun s(
        uri: String,
        oldTitle: String,
        oldArtist: String,
        newTitle: String,
        newArtist: String = "",
        score: Int = 100
    ) = TagFixer.Suggestion(uri, oldTitle, oldArtist, newTitle, newArtist, score)

    private fun record(
        list: List<TagFixer.Suggestion>,
        entry: TagFixer.Suggestion
    ) = AppliedTags.record(list, entry, MAX)

    // ------------------------------------------------------------- record

    @Test
    fun `une premiere correction cree une ligne`() {
        val out = record(emptyList(), s("u1", "03 - foo", "Downloads", "Foo", "Bar"))
        assertEquals(1, out.size)
        assertEquals("Foo", out[0].newTitle)
    }

    @Test
    fun `une correction manuelle par dessus ne laisse qu une ligne`() {
        var l = record(emptyList(), s("u1", "03 - foo", "Downloads", "Foo", "Bar"))
        l = record(l, s("u1", "Foo", "Bar", "Foo Fighters", "Bar Band"))
        assertEquals("une seule ligne par morceau", 1, l.size)
        assertEquals("Foo Fighters", l[0].newTitle)
        assertEquals("Bar Band", l[0].newArtist)
    }

    @Test
    fun `la ligne fusionnee garde le tag d origine du fichier`() {
        var l = record(emptyList(), s("u1", "03 - foo", "Downloads", "Foo", "Bar"))
        l = record(l, s("u1", "Foo", "Bar", "Foo Fighters", "Bar Band"))
        assertEquals(
            "revenir à l'original doit ramener au tag de départ",
            "03 - foo", l[0].oldTitle
        )
        assertEquals("Downloads", l[0].oldArtist)
    }

    @Test
    fun `l origine survit a une longue suite de corrections`() {
        var l = record(emptyList(), s("u1", "origine", "artiste zéro", "v1", "a1"))
        for (i in 2..10) {
            l = record(l, s("u1", "v${i - 1}", "a${i - 1}", "v$i", "a$i"))
        }
        assertEquals(1, l.size)
        assertEquals("origine", l[0].oldTitle)
        assertEquals("artiste zéro", l[0].oldArtist)
        assertEquals("v10", l[0].newTitle)
    }

    @Test
    fun `la correction reprise passe en tete de liste`() {
        var l = record(emptyList(), s("u1", "a", "", "A"))
        l = record(l, s("u2", "b", "", "B"))
        l = record(l, s("u3", "c", "", "C"))
        // u1 est repris à la main : il redevient la correction la plus récente
        l = record(l, s("u1", "A", "", "A bis"))
        assertEquals(listOf("u1", "u3", "u2"), l.map { it.uri })
    }

    @Test
    fun `revenir a la main au tag d origine efface la ligne`() {
        var l = record(emptyList(), s("u1", "origine", "Downloads", "Corrigé", "Artiste"))
        // L'utilisateur retape exactement le tag de départ
        l = record(l, s("u1", "Corrigé", "Artiste", "origine", "Downloads"))
        assertTrue("plus rien à signaler pour ce morceau", l.none { it.uri == "u1" })
    }

    @Test
    fun `les autres morceaux ne sont pas touches`() {
        var l = record(emptyList(), s("u1", "a", "", "A"))
        l = record(l, s("u2", "b", "", "B"))
        l = record(l, s("u1", "A", "", "A bis"))
        assertEquals(2, l.size)
        assertEquals("B", l.first { it.uri == "u2" }.newTitle)
        assertEquals("b", l.first { it.uri == "u2" }.oldTitle)
    }

    @Test
    fun `la liste reste plafonnee`() {
        var l = emptyList<TagFixer.Suggestion>()
        for (i in 1..10) l = AppliedTags.record(l, s("u$i", "o$i", "", "n$i"), 5)
        assertEquals(5, l.size)
        assertEquals("les plus récentes sont gardées", "u10", l[0].uri)
    }

    // ------------------------------------------------------------ collapse

    @Test
    fun `un historique deja empile est reduit a une ligne par morceau`() {
        // Tel qu'enregistré avant la règle : du plus récent au plus ancien
        val empile = listOf(
            s("u1", "Foo", "Bar", "Foo Fighters", "Bar Band"),
            s("u2", "b", "", "B"),
            s("u1", "03 - foo", "Downloads", "Foo", "Bar")
        )
        val out = AppliedTags.collapse(empile)
        assertEquals(2, out.size)
        val u1 = out.first { it.uri == "u1" }
        assertEquals("Foo Fighters", u1.newTitle)
        assertEquals("03 - foo", u1.oldTitle)
        assertEquals("Downloads", u1.oldArtist)
    }

    @Test
    fun `la reduction garde l ordre du plus recent au plus ancien`() {
        val empile = listOf(
            s("u3", "c", "", "C"),
            s("u1", "A", "", "A bis"),
            s("u2", "b", "", "B"),
            s("u1", "a", "", "A")
        )
        assertEquals(listOf("u3", "u1", "u2"), AppliedTags.collapse(empile).map { it.uri })
    }

    @Test
    fun `la reduction efface les corrections revenues a l origine`() {
        val empile = listOf(
            s("u1", "Corrigé", "Artiste", "origine", "Downloads"),
            s("u1", "origine", "Downloads", "Corrigé", "Artiste")
        )
        assertTrue(AppliedTags.collapse(empile).isEmpty())
    }

    @Test
    fun `une liste vide se reduit a rien`() {
        assertTrue(AppliedTags.collapse(emptyList()).isEmpty())
    }

    @Test
    fun `la reduction est stable si elle est rejouee`() {
        val empile = listOf(
            s("u1", "Foo", "Bar", "Foo Fighters", "Bar Band"),
            s("u2", "b", "", "B"),
            s("u1", "03 - foo", "Downloads", "Foo", "Bar")
        )
        val une = AppliedTags.collapse(empile)
        assertEquals(une, AppliedTags.collapse(une))
    }

    // -------------------------------------------------------------- remove

    @Test
    fun `annuler retire toute trace du morceau`() {
        var l = record(emptyList(), s("u1", "a", "", "A"))
        l = record(l, s("u2", "b", "", "B"))
        val out = AppliedTags.remove(l, "u1")
        assertEquals(listOf("u2"), out.map { it.uri })
    }

    @Test
    fun `annuler un morceau absent ne change rien`() {
        val l = record(emptyList(), s("u1", "a", "", "A"))
        assertEquals(l, AppliedTags.remove(l, "inconnu"))
    }
}
