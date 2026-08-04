package com.pulsemix.app.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La durée annoncée à AcoustID doit être celle du morceau ENTIER, pas
 * celle des deux minutes analysées. Annoncer la seconde faisait échouer
 * toutes les recherches sur les morceaux de plus de deux minutes —
 * c'est-à-dire la quasi-totalité d'une bibliothèque.
 */
class AcoustIdTest {

    @Test
    fun `un morceau de quatre minutes est annonce pour quatre minutes`() {
        // 245 s de musique, dont seules les ~125 premières sont analysées
        assertEquals(245, AcoustId.declaredDuration(245_000L, 125))
    }

    @Test
    fun `un morceau plus court que l analyse garde sa duree`() {
        assertEquals(95, AcoustId.declaredDuration(95_000L, 95))
    }

    @Test
    fun `une duree de fichier inconnue retombe sur l analyse`() {
        assertEquals(125, AcoustId.declaredDuration(0L, 125))
        assertEquals(125, AcoustId.declaredDuration(-1L, 125))
    }

    @Test
    fun `une duree de fichier aberrante ne raccourcit jamais l annonce`() {
        // Métadonnée fantaisiste : 3 s pour un morceau dont on a décodé 125
        assertEquals(125, AcoustId.declaredDuration(3_000L, 125))
    }

    @Test
    fun `les millisecondes sont arrondies au plus proche`() {
        assertEquals(200, AcoustId.declaredDuration(200_400L, 60))
        assertEquals(201, AcoustId.declaredDuration(200_600L, 60))
        assertEquals(200, AcoustId.declaredDuration(199_500L, 60))
    }

    @Test
    fun `un morceau tres long reste correct`() {
        // Set de 74 minutes : l'empreinte ne couvre que le début
        assertEquals(4_440, AcoustId.declaredDuration(4_440_000L, 125))
    }
}
