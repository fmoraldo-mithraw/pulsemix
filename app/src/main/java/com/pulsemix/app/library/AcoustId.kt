package com.pulsemix.app.library

import android.content.Context
import android.net.Uri
import com.pulsemix.app.analysis.AudioDecoder
import com.pulsemix.app.analysis.Chromaprint
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Identification d'un morceau par son empreinte sonore (Chromaprint)
 * via le service AcoustID (https://acoustid.org), qui relie les
 * empreintes aux enregistrements MusicBrainz. Fiable même quand les
 * tags et le nom de fichier ne veulent rien dire : on écoute le son.
 */
object AcoustId {

    private const val CLIENT_KEY = "BkGUEWcJdJ"
    private const val ENDPOINT = "https://api.acoustid.org/v2/lookup"
    private const val USER_AGENT =
        "PulseMix/1 (https://github.com/fmoraldo-mithraw/pulsemix)"

    /** Espacement minimal entre requêtes (limite AcoustID : 3/s). */
    private const val REQUEST_SPACING_MS = 500L
    @Volatile private var lastRequestAt = 0L

    /** Empreinte prête à être envoyée, avec ce qu'il faut dire d'elle. */
    data class Print(
        val value: String,
        /** Secondes d'audio réellement analysées (≤ 120, comme fpcalc). */
        val analysedSec: Int,
        /** Durée du morceau entier, à annoncer au service. */
        val declaredSec: Int
    )

    /**
     * Ce qu'a répondu la dernière interrogation. Sert à dire pourquoi une
     * identification n'a rien donné : sans ça, « aucune correspondance »
     * couvrait aussi bien une empreinte inconnue du service qu'une clé
     * refusée ou un quota dépassé.
     */
    data class Report(
        val analysedSec: Int,
        val declaredSec: Int,
        val fingerprintChars: Int,
        val httpStatus: Int,
        val apiStatus: String,
        /** Empreintes reconnues par le service. */
        val matches: Int,
        /** Enregistrements MusicBrainz rattachés à ces empreintes. */
        val recordings: Int,
        val bestScore: Int
    )

    @Volatile
    var lastReport: Report? = null
        private set

    /** Pose un compte rendu de toutes pièces (tests de [lastFailureExplanation]). */
    internal fun setReportForTest(r: Report?) {
        lastReport = r
    }

    /**
     * Empreinte des ~2 premières minutes du morceau.
     *
     * @param fullDurationMs durée réelle du morceau entier.
     * @return null si le fichier est indécodable ou trop court (< 10 s).
     */
    fun fingerprint(context: Context, uri: String, fullDurationMs: Long): Print? {
        val cp = Chromaprint()
        AudioDecoder().decode(
            context, Uri.parse(uri),
            maxDurationUs = 125_000_000L
        ) { pcm, frames, sampleRate, channels ->
            cp.feed(pcm, frames, sampleRate, channels)
        }
        val analysed = cp.durationSeconds()
        if (analysed < 10) return null
        val fp = cp.fingerprint() ?: return null
        return Print(fp, analysed, declaredDuration(fullDurationMs, analysed))
    }

    /**
     * Durée à annoncer à AcoustID, en secondes.
     *
     * Le service indexe les empreintes avec la durée du morceau ENTIER,
     * alors que l'empreinte elle-même ne couvre que ses deux premières
     * minutes — c'est exactement ce que fait fpcalc, la référence avec
     * laquelle la base a été remplie : il n'analyse que le début et
     * rapporte quand même la durée complète du fichier.
     *
     * On annonçait ici la durée du passage analysé, plafonnée à ~120 s.
     * Tout morceau de plus de deux minutes arrivait donc avec une durée
     * qui ne correspondait à rien dans l'index, et la recherche ne rendait
     * jamais rien — quelle que soit la qualité de l'empreinte.
     *
     * Une durée de fichier absente ou aberrante ne doit pas raccourcir ce
     * qu'on annonce : on ne descend jamais sous ce qui a été analysé.
     */
    internal fun declaredDuration(fullDurationMs: Long, analysedSec: Int): Int {
        if (fullDurationMs <= 0L) return analysedSec
        val full = ((fullDurationMs + 500L) / 1000L).toInt()
        return maxOf(full, analysedSec)
    }

    /**
     * Interroge AcoustID et renvoie les enregistrements correspondants,
     * meilleurs scores d'abord (score = confiance AcoustID sur 100).
     */
    fun lookup(print: Print): List<TagFixer.Candidate> {
        var http = 0
        return try {
            throttle()
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            // L'empreinte est en base64-URL : aucun caractère à échapper
            // releasegroups : l'album d'origine de chaque enregistrement,
            // dont l'identifiant donne la jaquette (Cover Art Archive).
            val body = "client=$CLIENT_KEY&duration=${print.declaredSec}" +
                "&meta=recordings+releasegroups&fingerprint=${print.value}"
            conn.outputStream.use { it.write(body.toByteArray()) }

            http = conn.responseCode
            // Sur une erreur, AcoustID explique dans le corps de la réponse —
            // mais inputStream lève au lieu de le livrer. On lisait donc
            // « injoignable » pour une clé refusée ou un quota dépassé, et le
            // vrai message n'a jamais été affiché.
            val text = (if (http in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val root = try {
                JSONObject(text)
            } catch (_: Exception) {
                report(print, http, "réponse illisible", 0, 0, 0)
                TagFixer.lastError.value =
                    "AcoustID : réponse inattendue (HTTP $http)"
                return emptyList()
            }
            val apiStatus = root.optString("status", "?")
            if (apiStatus != "ok") {
                report(print, http, apiStatus, 0, 0, 0)
                TagFixer.lastError.value = "AcoustID : " +
                    (root.optJSONObject("error")?.optString("message")
                        ?: "réponse inattendue").take(120)
                return emptyList()
            }

            val out = ArrayList<TagFixer.Candidate>()
            val seen = HashSet<String>()
            val results = root.optJSONArray("results")
            var recordingCount = 0
            var bestScore = 0
            for (i in 0 until (results?.length() ?: 0)) {
                val res = results!!.getJSONObject(i)
                val score = (res.optDouble("score", 0.0) * 100).toInt()
                if (score > bestScore) bestScore = score
                val recs = res.optJSONArray("recordings") ?: continue
                recordingCount += recs.length()
                for (j in 0 until recs.length()) {
                    val r = recs.getJSONObject(j)
                    val title = r.optString("title", "")
                    if (title.isBlank()) continue
                    val artists = r.optJSONArray("artists")
                    val artist = buildString {
                        if (artists != null) {
                            for (k in 0 until artists.length()) {
                                val name = artists.getJSONObject(k)
                                    .optString("name", "")
                                if (name.isNotBlank()) {
                                    if (isNotEmpty()) append(" & ")
                                    append(name)
                                }
                            }
                        }
                    }
                    val key = (title + " " + artist).lowercase()
                    if (!seen.add(key)) continue
                    val rg = r.optJSONArray("releasegroups")?.optJSONObject(0)
                    out.add(
                        TagFixer.Candidate(
                            title, artist, score,
                            r.optLong("duration", 0L) * 1000L,
                            rg?.optString("id", "") ?: "",
                            rg?.optString("title", "") ?: ""
                        )
                    )
                }
            }
            report(
                print, http, apiStatus,
                results?.length() ?: 0, recordingCount, bestScore
            )
            TagFixer.lastError.value = null
            out.sortedByDescending { it.score }.take(10)
        } catch (e: Exception) {
            report(print, http, "échec réseau", 0, 0, 0)
            TagFixer.lastError.value = "AcoustID injoignable : " +
                (e.message ?: e::class.java.simpleName).take(120)
            emptyList()
        }
    }

    private fun report(
        print: Print,
        http: Int,
        apiStatus: String,
        matches: Int,
        recordings: Int,
        bestScore: Int
    ) {
        lastReport = Report(
            print.analysedSec, print.declaredSec, print.value.length,
            http, apiStatus, matches, recordings, bestScore
        )
    }

    /**
     * Pourquoi la dernière identification n'a rien donné, en clair. Chaque
     * cas appelle une action différente : refaire le tag à la main, ou
     * signaler que le service ne répond pas comme prévu.
     */
    fun lastFailureExplanation(): String? {
        val r = lastReport ?: return null
        return when {
            r.apiStatus == "échec réseau" -> null // lastError dit déjà quoi
            r.apiStatus != "ok" ->
                "AcoustID a refusé la requête (HTTP ${r.httpStatus}, " +
                    "statut « ${r.apiStatus} »)."
            r.matches == 0 ->
                "Empreinte calculée sur ${r.analysedSec} s, morceau annoncé " +
                    "à ${r.declaredSec} s : AcoustID ne la connaît pas. " +
                    "Ce morceau n'a jamais été soumis à la base, ou c'est " +
                    "une version (live, remix, edit) qui n'y est pas."
            r.recordings == 0 ->
                "AcoustID reconnaît le son (score ${r.bestScore}) mais aucun " +
                    "enregistrement MusicBrainz n'y est rattaché : il n'y a " +
                    "aucun titre à en tirer."
            else ->
                "AcoustID a répondu (score ${r.bestScore}, ${r.recordings} " +
                    "enregistrements) mais aucun n'avait de titre exploitable."
        }
    }

    private fun throttle() {
        val wait = lastRequestAt + REQUEST_SPACING_MS - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt = System.currentTimeMillis()
    }
}
