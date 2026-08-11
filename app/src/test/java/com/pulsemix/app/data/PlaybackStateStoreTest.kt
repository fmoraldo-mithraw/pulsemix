package com.pulsemix.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Aller-retour JSON de l'état de lecture (PlaybackStateStore.encode /
 * decode, séparés du Context précisément pour être testables ici). Une
 * asymétrie encode/decode ne plante pas : elle fait "juste" reprendre la
 * lecture au mauvais endroit après un redémarrage.
 *
 * Nécessite le vrai org.json sur le classpath de test (les stubs
 * d'android.jar lèvent à l'appel) : voir testImplementation("org.json:json").
 */
class PlaybackStateStoreTest {

    @Test
    fun `aller-retour complet - mode mix avec plan et phases`() {
        val state = PlaybackState(
            mode = "MIX",
            queueUris = listOf("content://a", "content://b", "content://c"),
            planId = "flow",
            planName = "Flow continu",
            planDescription = "Un mix qui coule",
            phaseNames = listOf("Chauffe", "Peak"),
            phaseUris = listOf(
                listOf("content://a"),
                listOf("content://b", "content://c")
            ),
            currentIndex = 2,
            positionMs = 42_500L,
            currentPhase = 1,
            shuffle = true
        )
        val decoded = PlaybackStateStore.decode(PlaybackStateStore.encode(state))
        assertEquals(state, decoded)
    }

    @Test
    fun `aller-retour minimal - mode normal sans plan`() {
        val state = PlaybackState(
            mode = "NORMAL",
            queueUris = listOf("content://seul"),
            currentIndex = 0,
            positionMs = 0L
        )
        val decoded = PlaybackStateStore.decode(PlaybackStateStore.encode(state))
        // Les champs plan vides sont encodés "" et relus null : le decode
        // doit rendre exactement l'état de départ (planId null, etc.)
        assertEquals(state, decoded)
    }

    @Test
    fun `texte corrompu - decode rend null plutot que de planter`() {
        assertNull(PlaybackStateStore.decode("{tronqué…"))
        assertNull(PlaybackStateStore.decode("{}")) // pas de champ mode
    }
}
