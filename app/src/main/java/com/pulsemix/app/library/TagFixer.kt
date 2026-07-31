package com.pulsemix.app.library

import android.content.Context
import com.pulsemix.app.data.Track
import com.pulsemix.app.data.TrackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Recherche des « vrais » tags (titre / artiste) en ligne via MusicBrainz,
 * pour un morceau ou toute la bibliothèque.
 *
 *  - Les corrections SÛRES (même titre à la casse/ponctuation près, ou
 *    correspondance quasi certaine) sont appliquées automatiquement.
 *  - Les propositions INCERTAINES vont dans une liste à valider à la main.
 *  - Les fichiers audio ne sont JAMAIS modifiés : les tags corrigés vivent
 *    dans la bibliothèque (et sa sauvegarde), comme les types de musique.
 */
object TagFixer {

    data class Suggestion(
        val uri: String,
        val oldTitle: String,
        val oldArtist: String,
        val newTitle: String,
        val newArtist: String,
        val score: Int
    )

    /** Propositions incertaines en attente de validation. */
    val pending = MutableStateFlow<List<Suggestion>>(emptyList())

    /** Progression du passage bibliothèque : (faits, total, appliqués auto). */
    val progress: StateFlow<Triple<Int, Int, Int>?> get() = _progress
    private val _progress = MutableStateFlow<Triple<Int, Int, Int>?>(null)

    @Volatile private var stopRequested = false
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        load()
    }

    fun requestStop() {
        stopRequested = true
    }

    // ------------------------------------------------------------ traitement

    /** Cherche les tags d'un seul morceau (proposition ou application sûre). */
    suspend fun fixOne(store: TrackStore, track: Track): Unit =
        withContext(Dispatchers.IO) {
            handle(store, track)
            save()
            store.save()
        }

    /** Passe toute la bibliothèque (1 requête/s : politesse MusicBrainz). */
    suspend fun fixAll(store: TrackStore): Unit = withContext(Dispatchers.IO) {
        if (_progress.value != null) return@withContext
        stopRequested = false
        val list = store.tracks.value
        var applied = 0
        _progress.value = Triple(0, list.size, 0)
        try {
            for ((i, t) in list.withIndex()) {
                if (stopRequested) break
                if (handle(store, t)) applied++
                _progress.value = Triple(i + 1, list.size, applied)
                Thread.sleep(1_100)
            }
        } finally {
            save()
            store.save()
            _progress.value = null
        }
    }

    /** Valide une proposition : applique à la bibliothèque (pas au fichier). */
    fun accept(store: TrackStore, s: Suggestion) {
        store.update(s.uri) {
            it.copy(
                title = s.newTitle,
                artist = s.newArtist.ifBlank { it.artist }
            )
        }
        pending.value = pending.value.filter { it.uri != s.uri }
        save()
    }

    fun reject(s: Suggestion) {
        pending.value = pending.value.filter { it.uri != s.uri }
        save()
    }

    /** @return true si une correction sûre a été appliquée automatiquement. */
    private fun handle(store: TrackStore, t: Track): Boolean {
        val cleaned = cleanTitle(t.title)
        val res = lookup(cleaned, t.artist) ?: return false
        val (nt, na, score) = res
        if (nt.isBlank()) return false
        if (nt == t.title && (na.isBlank() || na == t.artist)) return false

        val sameNorm = norm(nt) == norm(t.title) &&
            (na.isBlank() || t.artist.isBlank() || norm(na) == norm(t.artist))
        val sure = sameNorm || (score >= 97 && t.artist.isNotBlank())
        if (sure) {
            store.update(t.uri) {
                it.copy(title = nt, artist = na.ifBlank { it.artist })
            }
            pending.value = pending.value.filter { it.uri != t.uri }
            return true
        }
        if (score >= 55) {
            pending.value = pending.value.filter { it.uri != t.uri } +
                Suggestion(t.uri, t.title, t.artist, nt, na, score)
        }
        return false
    }

    // ---------------------------------------------------------- MusicBrainz

    private fun lookup(title: String, artist: String): Triple<String, String, Int>? {
        if (title.isBlank()) return null
        return try {
            val q = buildString {
                append("recording:\"").append(title.replace("\"", " ")).append('"')
                if (artist.isNotBlank()) {
                    append(" AND artist:\"").append(artist.replace("\"", " ")).append('"')
                }
            }
            val url = URL(
                "https://musicbrainz.org/ws/2/recording?query=" +
                    URLEncoder.encode(q, "UTF-8") + "&fmt=json&limit=1"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty(
                "User-Agent",
                "PulseMix/1.4 (https://github.com/fmoraldo-mithraw/pulsemix)"
            )
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val recs = JSONObject(body).optJSONArray("recordings") ?: return null
            if (recs.length() == 0) return null
            val r = recs.getJSONObject(0)
            val nt = r.optString("title", "")
            val na = r.optJSONArray("artist-credit")
                ?.optJSONObject(0)?.optString("name", "") ?: ""
            Triple(nt, na, r.optInt("score", 0))
        } catch (_: Exception) {
            null
        }
    }

    /** Nettoie un titre « nom de fichier » avant la recherche. */
    private fun cleanTitle(raw: String): String {
        var s = raw
            .removeSuffix(".mp3").removeSuffix(".m4a").removeSuffix(".flac")
            .removeSuffix(".ogg").removeSuffix(".wav")
            .replace('_', ' ')
        s = s.replace(
            Regex(
                "[\\(\\[](official|clip|video|lyrics?|audio|hd|hq|4k|paroles)" +
                    "[^\\)\\]]*[\\)\\]]",
                RegexOption.IGNORE_CASE
            ), " "
        )
        s = s.replace(Regex("^\\s*\\d{1,3}\\s*[-.]\\s*"), "")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun norm(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9à-ÿ]"), "")

    // ---------------------------------------------------------- persistance

    private fun file() = java.io.File(appContext!!.filesDir, "tag_suggestions.json")

    private fun load() {
        try {
            val arr = JSONArray(file().readText())
            val list = ArrayList<Suggestion>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Suggestion(
                        o.getString("uri"),
                        o.optString("oldTitle", ""),
                        o.optString("oldArtist", ""),
                        o.optString("newTitle", ""),
                        o.optString("newArtist", ""),
                        o.optInt("score", 0)
                    )
                )
            }
            pending.value = list
        } catch (_: Exception) {
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (s in pending.value) {
                arr.put(
                    JSONObject()
                        .put("uri", s.uri)
                        .put("oldTitle", s.oldTitle)
                        .put("oldArtist", s.oldArtist)
                        .put("newTitle", s.newTitle)
                        .put("newArtist", s.newArtist)
                        .put("score", s.score)
                )
            }
            file().writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}
