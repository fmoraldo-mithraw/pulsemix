package com.pulsemix.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'évaluation des playlists intelligentes. La fonction est PURE :
 * l'horloge et l'historique de lecture sont injectés, rien ne touche à
 * Android (init(context) n'est jamais appelé, le fichier reste null).
 */
class SmartPlaylistsTest {

    private val DAY = 86_400_000L
    private val now = 1_000L * DAY

    private fun track(
        uri: String,
        bpm: Float = 120f,
        genre: String = "rock",
        energy: Float = 0.2f,
        favorite: Boolean = false,
        excluded: Boolean = false
    ) = Track(
        uri = uri,
        title = uri,
        artist = "",
        durationMs = 200_000L,
        bpm = bpm,
        energyMean = energy,
        genre = genre,
        favorite = favorite,
        excluded = excluded,
        analyzed = bpm > 0f
    )

    private val never: (String) -> Long? = { null }

    @Test
    fun `regle vide - toute la bibliotheque passe`() {
        val all = listOf(track("a"), track("b", bpm = 0f), track("c", excluded = true))
        assertEquals(all, SmartPlaylists.evaluate(Rule(), all, now, never))
    }

    @Test
    fun `fourchette de bpm - seuls les tempos dedans restent`() {
        val all = listOf(
            track("lent", bpm = 80f),
            track("bon", bpm = 120f),
            track("rapide", bpm = 160f),
            track("pas-analyse", bpm = 0f)
        )
        val out = SmartPlaylists.evaluate(
            Rule(minBpm = 100f, maxBpm = 140f), all, now, never
        )
        assertEquals(listOf("bon"), out.map { it.uri })
    }

    @Test
    fun `genre - compare sans tenir compte de la casse`() {
        val all = listOf(track("r", genre = "rock"), track("p", genre = "pop"))
        val out = SmartPlaylists.evaluate(Rule(genre = "  Rock "), all, now, never)
        assertEquals(listOf("r"), out.map { it.uri })
    }

    @Test
    fun `fourchette d energie`() {
        val all = listOf(
            track("doux", energy = 0.05f),
            track("moyen", energy = 0.18f),
            track("fort", energy = 0.35f)
        )
        val out = SmartPlaylists.evaluate(
            Rule(minEnergy = 0.1f, maxEnergy = 0.25f), all, now, never
        )
        assertEquals(listOf("moyen"), out.map { it.uri })
    }

    @Test
    fun `pas joue depuis N jours - jamais joue passe, recent non`() {
        val all = listOf(track("jamais"), track("hier"), track("vieux"))
        val lastPlayed: (String) -> Long? = { uri ->
            when (uri) {
                "hier" -> now - 1 * DAY
                "vieux" -> now - 40 * DAY
                else -> null
            }
        }
        val out = SmartPlaylists.evaluate(
            Rule(notPlayedDays = 30), all, now, lastPlayed
        )
        assertEquals(listOf("jamais", "vieux"), out.map { it.uri })
    }

    @Test
    fun `favoris seulement et exclus ecartes`() {
        val all = listOf(
            track("fav", favorite = true),
            track("fav-exclu", favorite = true, excluded = true),
            track("normal")
        )
        val out = SmartPlaylists.evaluate(
            Rule(favoritesOnly = true, excludeExcluded = true), all, now, never
        )
        assertEquals(listOf("fav"), out.map { it.uri })
        // Sans excludeExcluded, le favori exclu revient
        val out2 = SmartPlaylists.evaluate(Rule(favoritesOnly = true), all, now, never)
        assertTrue(out2.any { it.uri == "fav-exclu" })
    }

    // ------------------------------------------------- aller-retour JSON

    @Test
    fun `le json fait l aller-retour sans perte`() {
        val list = listOf(
            SmartPlaylist("Cardio", Rule(minBpm = 140f, notPlayedDays = 7)),
            SmartPlaylist(
                "Douceur",
                Rule(maxEnergy = 0.12f, genre = "ambient", favoritesOnly = false)
            )
        )
        assertEquals(list, SmartPlaylists.readList(SmartPlaylists.writeList(list)))
    }
}
