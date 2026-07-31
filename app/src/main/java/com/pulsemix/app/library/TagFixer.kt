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

    /** Dernière erreur réseau/MusicBrainz (null si tout va bien). */
    val lastError = MutableStateFlow<String?>(null)

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
        lastError.value = null
        val list = store.tracks.value
        var applied = 0
        _progress.value = Triple(0, list.size, 0)
        try {
            for ((i, t) in list.withIndex()) {
                if (stopRequested) break
                if (handle(store, t)) applied++
                _progress.value = Triple(i + 1, list.size, applied)
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
        val split = splitArtistTitle(cleaned)

        // Essais du plus précis au plus vague : tag artiste existant, puis
        // découpage « Artiste - Titre » du nom de fichier, puis titre seul.
        // On s'arrête au premier essai qui donne une correction sûre.
        val attempts = buildList {
            if (t.artist.isNotBlank()) add(cleaned to t.artist)
            if (split != null) add(split.second to split.first)
            add((split?.second ?: cleaned) to "")
        }.distinct()

        var proposal: Suggestion? = null
        for ((qTitle, qArtist) in attempts) {
            if (qTitle.isBlank()) continue
            val best = pickBest(lookup(qTitle, qArtist), t.durationMs) ?: continue
            if (best.title.isBlank()) continue
            if (best.title == t.title &&
                (best.artist.isBlank() || best.artist == t.artist)
            ) return false // déjà correct

            val durOk = durationClose(best.lengthMs, t.durationMs)
            val titleMatch = norm(best.title) == norm(qTitle)
            val artistMatch = qArtist.isNotBlank() &&
                norm(best.artist) == norm(qArtist)

            // Sûr : titre et artiste identiques à la casse près, ou titre
            // identique + durée qui colle. Le score MusicBrainz seul ne
            // suffit jamais : il est relatif à la recherche (le premier
            // résultat frôle 100 même quand c'est un mauvais match).
            val sure = (titleMatch && artistMatch) ||
                (titleMatch && durOk) ||
                (artistMatch && durOk && best.score >= 95)
            if (sure) {
                store.update(t.uri) {
                    it.copy(
                        title = best.title,
                        artist = best.artist.ifBlank { it.artist }
                    )
                }
                pending.value = pending.value.filter { it.uri != t.uri }
                return true
            }
            // Proposition : plausible, mais on exige que la durée ne
            // contredise pas la correspondance (inconnue tolérée).
            if (proposal == null && best.score >= 60 &&
                (durOk || best.lengthMs <= 0)
            ) {
                proposal = Suggestion(
                    t.uri, t.title, t.artist, best.title, best.artist, best.score
                )
            }
        }
        if (proposal != null) {
            pending.value = pending.value.filter { it.uri != t.uri } + proposal
        }
        return false
    }

    // ---------------------------------------------------------- MusicBrainz

    private data class Candidate(
        val title: String,
        val artist: String,
        val score: Int,
        val lengthMs: Long
    )

    /** Meilleur candidat : durée compatible d'abord, score ensuite. */
    private fun pickBest(cands: List<Candidate>, durMs: Long): Candidate? {
        val durOk = cands.filter { durationClose(it.lengthMs, durMs) }
        return (durOk.ifEmpty { cands }).maxByOrNull { it.score }
    }

    private fun durationClose(a: Long, b: Long): Boolean =
        a > 0 && b > 0 && kotlin.math.abs(a - b) <= 7_000

    @Volatile private var lastRequestAt = 0L

    /** Espacement ≥ 1,1 s entre requêtes (politesse MusicBrainz). */
    private fun throttle() {
        val wait = lastRequestAt + 1_100 - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    private fun lookup(title: String, artist: String): List<Candidate> {
        if (title.isBlank()) return emptyList()
        return try {
            throttle()
            val q = buildString {
                append("recording:\"").append(title.replace("\"", " ")).append('"')
                if (artist.isNotBlank()) {
                    append(" AND artist:\"").append(artist.replace("\"", " ")).append('"')
                }
            }
            val url = URL(
                "https://musicbrainz.org/ws/2/recording?query=" +
                    URLEncoder.encode(q, "UTF-8") + "&fmt=json&limit=5"
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
            val recs = JSONObject(body).optJSONArray("recordings")
                ?: return emptyList()
            val out = ArrayList<Candidate>()
            for (i in 0 until recs.length()) {
                val r = recs.getJSONObject(i)
                out.add(
                    Candidate(
                        r.optString("title", ""),
                        r.optJSONArray("artist-credit")
                            ?.optJSONObject(0)?.optString("name", "") ?: "",
                        r.optInt("score", 0),
                        r.optLong("length", 0L)
                    )
                )
            }
            lastError.value = null
            out
        } catch (e: Exception) {
            lastError.value = "MusicBrainz injoignable : " +
                (e.message ?: e::class.java.simpleName).take(120)
            emptyList()
        }
    }

    /**
     * « Artiste - Titre » : le format quasi universel des fichiers sans
     * tags. Le séparateur doit être entouré d'espaces pour ne pas couper
     * les mots composés (« Jean-Michel »).
     */
    private fun splitArtistTitle(s: String): Pair<String, String>? {
        val m = Regex("^(.{2,60}?)\\s+[-–—|]\\s+(.{2,})$").find(s) ?: return null
        val artist = m.groupValues[1].trim()
        val title = m.groupValues[2].trim()
        if (artist.isBlank() || title.isBlank()) return null
        return artist to title
    }

    /** Nettoie un titre « nom de fichier » avant la recherche. */
    private fun cleanTitle(raw: String): String {
        var s = raw
            .replace(
                Regex(
                    "\\.(mp3|m4a|aac|flac|ogg|opus|wav|weba)$",
                    RegexOption.IGNORE_CASE
                ), ""
            )
            .replace('_', ' ')
        // Identifiant vidéo yt-dlp en fin de nom : « Titre [dQw4w9WgXcQ] »
        s = s.replace(Regex("\\s*\\[[A-Za-z0-9_-]{6,}\\]\\s*$"), " ")
        s = s.replace(
            Regex(
                "[\\(\\[](official|clip|video|lyrics?|audio|hd|hq|4k|paroles" +
                    "|visuali[sz]er|explicit|remaster(ed)?)" +
                    "[^\\)\\]]*[\\)\\]]",
                RegexOption.IGNORE_CASE
            ), " "
        )
        // « feat. X » : l'invité ne fait pas partie du titre MusicBrainz
        s = s.replace(
            Regex(
                "[\\(\\[]\\s*(feat\\.?|ft\\.?|featuring|avec)\\s[^\\)\\]]*[\\)\\]]",
                RegexOption.IGNORE_CASE
            ), " "
        )
        s = s.replace(
            Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE),
            " "
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
