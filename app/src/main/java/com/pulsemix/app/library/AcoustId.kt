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

    /**
     * Empreinte des ~2 premières minutes du morceau.
     *
     * @param fullDurationMs durée réelle du morceau entier.
     * @return (empreinte base64, durée à déclarer en secondes), ou null si
     * le fichier est indécodable ou trop court (< 10 s).
     */
    fun fingerprint(context: Context, uri: String, fullDurationMs: Long): Pair<String, Int>? {
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
        return fp to declaredDuration(fullDurationMs, analysed)
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
     * On annonçait ici la durée du passage analysé, plafonnée à ~125 s.
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
    fun lookup(fingerprint: String, durationSec: Int): List<TagFixer.Candidate> {
        return try {
            throttle()
            val url = URL(ENDPOINT)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty(
                "Content-Type", "application/x-www-form-urlencoded"
            )
            // L'empreinte est en base64-URL : aucun caractère à échapper
            val body = "client=$CLIENT_KEY&duration=$durationSec" +
                "&meta=recordings&fingerprint=$fingerprint"
            conn.outputStream.use { it.write(body.toByteArray()) }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val root = JSONObject(text)
            if (root.optString("status") != "ok") {
                TagFixer.lastError.value = "AcoustID : " +
                    (root.optJSONObject("error")?.optString("message")
                        ?: "réponse inattendue").take(120)
                return emptyList()
            }
            val out = ArrayList<TagFixer.Candidate>()
            val seen = HashSet<String>()
            val results = root.optJSONArray("results") ?: return emptyList()
            for (i in 0 until results.length()) {
                val res = results.getJSONObject(i)
                val score = (res.optDouble("score", 0.0) * 100).toInt()
                val recs = res.optJSONArray("recordings") ?: continue
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
                    out.add(
                        TagFixer.Candidate(
                            title, artist, score,
                            r.optLong("duration", 0L) * 1000L
                        )
                    )
                }
            }
            TagFixer.lastError.value = null
            out.sortedByDescending { it.score }.take(10)
        } catch (e: Exception) {
            TagFixer.lastError.value = "AcoustID injoignable : " +
                (e.message ?: e::class.java.simpleName).take(120)
            emptyList()
        }
    }

    private fun throttle() {
        val wait = lastRequestAt + REQUEST_SPACING_MS - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt = System.currentTimeMillis()
    }
}
