package com.pulsemix.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du glissement des bornes de phases quand un morceau est déplacé
 * dans la file (QueueMath, extrait de PlayerCore). Une borne fausse ne
 * plante rien : elle décale silencieusement l'affichage de la phase en
 * cours, ou laisse une phase commencer hors de la file (bouton mort).
 */
class QueueMathTest {

    @Test
    fun `deplacement vers l avant - les bornes traversees reculent d un cran`() {
        // Phases [0..2], [3..5], [6..] ; le morceau 1 part en position 4 :
        // la borne 3 (traversée) glisse à 2, la borne 6 (au-delà) ne bouge pas
        assertEquals(
            listOf(0, 2, 6),
            QueueMath.shiftPhaseStartsForMove(listOf(0, 3, 6), from = 1, to = 4, queueSize = 9)
        )
    }

    @Test
    fun `deplacement vers l arriere - les bornes traversees avancent d un cran`() {
        // Le morceau 4 remonte en position 1 : la borne 3 glisse à 4
        assertEquals(
            listOf(0, 4, 6),
            QueueMath.shiftPhaseStartsForMove(listOf(0, 3, 6), from = 4, to = 1, queueSize = 9)
        )
    }

    @Test
    fun `deplacement local - aucune borne traversee, rien ne bouge`() {
        assertEquals(
            listOf(0, 3, 6),
            QueueMath.shiftPhaseStartsForMove(listOf(0, 3, 6), from = 4, to = 5, queueSize = 9)
        )
    }

    @Test
    fun `la borne ne sort jamais de la file`() {
        // Dernière phase réduite à son unique morceau (index 5, file de 6) :
        // le remonter ferait passer sa borne à 6, hors de la file — elle
        // doit rester bornée au dernier index valide.
        val shifted = QueueMath.shiftPhaseStartsForMove(
            listOf(0, 5), from = 5, to = 1, queueSize = 6
        )
        assertEquals(2, shifted.size)
        assertTrue(shifted.all { it in 0..5 })
    }

    @Test
    fun `sans phases - liste vide inchangee`() {
        assertEquals(
            emptyList<Int>(),
            QueueMath.shiftPhaseStartsForMove(emptyList(), from = 0, to = 3, queueSize = 5)
        )
    }

    @Test
    fun `aller-retour - revenir a la position d origine restaure les bornes`() {
        val starts = listOf(0, 3, 6)
        val once = QueueMath.shiftPhaseStartsForMove(starts, from = 1, to = 7, queueSize = 9)
        val back = QueueMath.shiftPhaseStartsForMove(once, from = 7, to = 1, queueSize = 9)
        assertEquals(starts, back)
    }
}
