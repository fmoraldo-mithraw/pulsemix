package com.pulsemix.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du malus de sur-lecture (PlayHistory.overplayPenalty). La fonction
 * est pure vis-à-vis d'Android : sans init(context), les prefs restent
 * null et seuls les compteurs importés comptent.
 *
 * PlayHistory est un singleton : les compteurs s'accumulent entre les
 * tests. Les assertions sont donc choisies pour rester vraies quel que
 * soit l'ordre d'exécution (URIs distincts, valeurs extrêmes plutôt que
 * seuils fins).
 */
class PlayHistoryTest {

    @Test
    fun `jamais joue - aucun malus`() {
        assertEquals(0f, PlayHistory.overplayPenalty("test://jamais-joue"), 0f)
    }

    @Test
    fun `moins de trois lectures - aucun malus`() {
        PlayHistory.importCounts(mapOf("test://peu-joue" to 2))
        assertEquals(0f, PlayHistory.overplayPenalty("test://peu-joue"), 0f)
    }

    @Test
    fun `sur-joue a l extreme - malus sature a 1`() {
        // Un morceau écrasant tout le reste : quel que soit ce que les
        // autres tests ont importé, il dépasse largement 4x la moyenne.
        PlayHistory.importCounts(
            mapOf(
                "test://hot" to 100_000,
                "test://h1" to 1, "test://h2" to 1, "test://h3" to 1,
                "test://h4" to 1, "test://h5" to 1, "test://h6" to 1,
                "test://h7" to 1, "test://h8" to 1, "test://h9" to 1
            )
        )
        assertEquals(1f, PlayHistory.overplayPenalty("test://hot"), 1e-6f)
        // Ses voisins peu joués (1 lecture < 3) restent sans malus
        assertEquals(0f, PlayHistory.overplayPenalty("test://h1"), 0f)
    }

    @Test
    fun `dans la norme - aucun malus, sur-joue - malus strictement positif`() {
        // Au moins 5 morceaux comptés (garde-fou « trop peu de données ») :
        // l'import est idempotent, ces entrées peuvent déjà exister.
        PlayHistory.importCounts(
            mapOf(
                "test://hot" to 100_000,
                "test://normal" to 3,
                "test://h1" to 1, "test://h2" to 1, "test://h3" to 1
            )
        )
        // 3 lectures face à un mastodonte : très en dessous de 1,5x la
        // moyenne -> pas de malus
        assertEquals(0f, PlayHistory.overplayPenalty("test://normal"), 0f)
        // Le mastodonte, lui, est pénalisé
        assertTrue(PlayHistory.overplayPenalty("test://hot") > 0f)
        // Et le malus reste borné à 1
        assertTrue(PlayHistory.overplayPenalty("test://hot") <= 1f)
    }
}
