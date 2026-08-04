package com.pulsemix.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un échec d'identification doit se lire, sinon on ne sait pas quoi
 * corriger. Ces cas vérifient que chaque façon d'échouer donne bien une
 * explication distincte.
 */
class AcoustIdReportTest {

    private fun report(
        apiStatus: String = "ok",
        http: Int = 200,
        matches: Int = 0,
        recordings: Int = 0,
        bestScore: Int = 0,
        analysed: Int = 120,
        declared: Int = 245
    ) = AcoustId.Report(
        analysedSec = analysed,
        declaredSec = declared,
        fingerprintChars = 900,
        httpStatus = http,
        apiStatus = apiStatus,
        matches = matches,
        recordings = recordings,
        bestScore = bestScore
    )

    private fun explain(r: AcoustId.Report): String? {
        AcoustId.setReportForTest(r)
        return AcoustId.lastFailureExplanation()
    }

    @Test
    fun `sans interrogation il n y a rien a expliquer`() {
        AcoustId.setReportForTest(null)
        assertNull(AcoustId.lastFailureExplanation())
    }

    @Test
    fun `une empreinte inconnue le dit avec les deux durees`() {
        val msg = explain(report(matches = 0))
        assertNotNull(msg)
        assertTrue("doit citer la durée analysée", msg!!.contains("120 s"))
        assertTrue("doit citer la durée annoncée", msg.contains("245 s"))
    }

    @Test
    fun `une cle refusee ne se confond pas avec une empreinte inconnue`() {
        val msg = explain(report(apiStatus = "error", http = 400))
        assertNotNull(msg)
        assertTrue(msg!!.contains("400"))
        assertTrue(msg.contains("refusé"))
    }

    @Test
    fun `un son reconnu sans enregistrement rattache est distingue`() {
        val msg = explain(report(matches = 1, recordings = 0, bestScore = 97))
        assertNotNull(msg)
        assertTrue(msg!!.contains("97"))
        assertTrue("doit parler de MusicBrainz", msg.contains("MusicBrainz"))
    }

    @Test
    fun `des enregistrements sans titre exploitable sont distingues`() {
        val msg = explain(report(matches = 1, recordings = 3, bestScore = 91))
        assertNotNull(msg)
        assertTrue(msg!!.contains("3"))
        assertTrue(msg.contains("titre"))
    }

    @Test
    fun `un echec reseau laisse parler le message reseau`() {
        assertNull(explain(report(apiStatus = "échec réseau")))
    }

    @Test
    fun `les quatre echecs donnent quatre messages differents`() {
        val messages = listOf(
            explain(report(matches = 0)),
            explain(report(apiStatus = "error", http = 400)),
            explain(report(matches = 1, recordings = 0, bestScore = 97)),
            explain(report(matches = 1, recordings = 3, bestScore = 91))
        )
        assertEquals(4, messages.filterNotNull().toSet().size)
    }
}
