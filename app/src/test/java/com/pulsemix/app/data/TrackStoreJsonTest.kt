package com.pulsemix.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trip JSON d'un Track (TrackStore.trackToJson/trackFromJson).
 * Le point sensible est la rétro-compatibilité : un library.json écrit
 * avant l'arrivée d'un champ doit se relire avec la valeur par défaut,
 * jamais lever — sinon la bibliothèque entière disparaît au démarrage.
 */
class TrackStoreJsonTest {

    @Test
    fun `aller-retour complet - structure comprise`() {
        val t = Track(
            uri = "content://x/1",
            title = "Titre",
            artist = "Artiste",
            durationMs = 200_000L,
            bpm = 128f,
            analyzed = true,
            structure = "INTRO:0:24000;BUILD:24000:32000;DROP:32000:160000;OUTRO:160000:200000"
        )
        val back = TrackStore.trackFromJson(TrackStore.trackToJson(t))
        assertEquals(t.structure, back.structure)
        assertEquals(t, back)
    }

    @Test
    fun `json d avant le champ structure - defaut vide`() {
        val o = TrackStore.trackToJson(
            Track(uri = "content://x/2", title = "t", artist = "", durationMs = 1_000L)
        )
        o.remove("structure")
        assertEquals("", TrackStore.trackFromJson(o).structure)
    }
}
