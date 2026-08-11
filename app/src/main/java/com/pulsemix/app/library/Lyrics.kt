package com.pulsemix.app.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.pulsemix.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Paroles synchronisées d'un morceau.
 *
 * Sources, dans l'ordre : cache disque (filesDir/lyrics), fichier .lrc posé
 * à côté du morceau dans le dossier de musique, puis l'API publique
 * lrclib.net. Le résultat est parsé en lignes horodatées ([parseLrc], pure
 * et testée en JVM) et exposé par un StateFlow — l'UI viendra se brancher
 * dessus.
 */
object Lyrics {

    sealed class State {
        object Idle : State()
        object Loading : State()
        /**
         * Paroles chargées : (temps ms depuis le début, ligne), triées par
         * temps. Des paroles NON synchronisées (plainLyrics, .lrc sans
         * timestamps) donnent toutes les lignes à 0 ms — l'UI sait ainsi
         * qu'il n'y a pas de calage à suivre.
         */
        data class Loaded(val lines: List<Pair<Long, String>>) : State()
        object None : State()
    }

    val state: StateFlow<State> get() = _state
    private val _state = MutableStateFlow<State>(State.Idle)

    /** Morceau dont le chargement est en cours ou affiché : un résultat qui
     *  arrive pour un autre morceau (chargement lent doublé par un skip) ne
     *  doit pas écraser l'état du morceau courant. */
    @Volatile private var currentUri: String? = null

    fun clear() {
        currentUri = null
        _state.value = State.Idle
    }

    suspend fun load(context: Context, track: Track) =
        withContext(Dispatchers.IO) {
            currentUri = track.uri
            _state.value = State.Loading
            // Le cache stocke aussi le résultat NÉGATIF (fichier vide) :
            // sans lui, chaque ouverture de la feuille sur un morceau sans
            // paroles refaisait un appel réseau de 8 s, même hors ligne.
            val cached = cachedText(context, track)
            val text = when {
                cached != null -> cached.takeIf { it.isNotBlank() }
                else -> localLrc(context, track)
                    ?.also { cacheText(context, track, it) }
                    ?: remoteLrc(track)
                        // null = échec réseau (on retentera) ; "" = réponse
                        // « pas de paroles », négatif cachable
                        ?.also { cacheText(context, track, it) }
                        ?.takeIf { it.isNotBlank() }
            }
            // Un autre morceau a pris la main pendant le chargement
            if (currentUri != track.uri) return@withContext
            val lines = text?.let { t ->
                parseLrc(t).ifEmpty {
                    // Paroles non synchronisées : chaque ligne à 0 ms (les
                    // lignes de métadonnées [ar:…] restent écartées)
                    t.lines().map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("[") }
                        .map { 0L to it }
                }
            }.orEmpty()
            _state.value = if (lines.isEmpty()) State.None else State.Loaded(lines)
        }

    // ------------------------------------------------------------ parse LRC

    /** Timestamps [mm:ss], [mm:ss.xx], [mm:ss.xxx] (ou « : » en séparateur). */
    private val STAMP = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")

    /**
     * Parse un texte LRC en lignes horodatées, triées par temps. Fonction
     * PURE (testable JVM) :
     *  - plusieurs timestamps en tête de ligne = la même ligne à chaque
     *    temps (refrains) ;
     *  - lignes sans timestamp (métadonnées [ar:…], texte nu) écartées ;
     *  - minutages en désordre dans le fichier → triés.
     */
    fun parseLrc(text: String): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        for (raw in text.lines()) {
            // Suite de timestamps en tête de ligne, le texte vient après
            val times = ArrayList<Long>()
            var idx = 0
            while (true) {
                val m = STAMP.matchAt(raw, idx) ?: break
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                // « .5 » = 500 ms, « .50 » = 500 ms, « .500 » = 500 ms
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100L
                    2 -> frac.toLong() * 10L
                    else -> frac.take(3).toLong()
                }
                times.add(min * 60_000L + sec * 1_000L + ms)
                idx = m.range.last + 1
            }
            if (times.isEmpty()) continue
            val content = raw.substring(idx).trim()
            if (content.isEmpty()) continue
            for (t in times) out.add(t to content)
        }
        return out.sortedBy { it.first }
    }

    // ---------------------------------------------------------- .lrc local

    /**
     * Fichier .lrc posé à côté du morceau. DocumentFile.fromSingleUri n'a
     * pas de parent : on reconstruit l'URI du voisin à partir du docId du
     * morceau (même chemin, extension .lrc), essayé sous chacun des
     * dossiers de la bibliothèque — seul le dossier qui contient vraiment
     * le morceau acceptera l'ouverture, les autres lèvent et sont ignorés.
     */
    private fun localLrc(context: Context, track: Track): String? {
        val docId = try {
            DocumentsContract.getDocumentId(Uri.parse(track.uri))
        } catch (_: Exception) {
            return null
        }
        if (!docId.contains('.')) return null
        val lrcId = docId.substringBeforeLast('.') + ".lrc"
        val folders = try {
            com.pulsemix.app.Graph.store.folders.value
        } catch (_: Exception) {
            emptyList()
        }
        for (folder in folders) {
            try {
                val lrcUri = DocumentsContract.buildDocumentUriUsingTree(
                    Uri.parse(folder), lrcId
                )
                context.contentResolver.openInputStream(lrcUri)?.use {
                    return it.bufferedReader().readText()
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    // -------------------------------------------------------------- lrclib

    /**
     * GET lrclib.net : paroles synchronisées si connues, sinon plaines.
     * @return les paroles ; "" si le serveur a répondu « inconnu » (négatif
     *         définitif, cachable) ; null si le réseau a échoué (à retenter).
     */
    private fun remoteLrc(track: Track): String? {
        // Titre vide : rien à chercher, et rien à cacher non plus (le
        // titre peut être corrigé plus tard, le cache est par URI).
        if (track.title.isBlank()) return null
        var conn: HttpURLConnection? = null
        return try {
            val url = "https://lrclib.net/api/get" +
                "?artist_name=" + URLEncoder.encode(track.artist, "UTF-8") +
                "&track_name=" + URLEncoder.encode(track.title, "UTF-8") +
                "&duration=" + (track.durationMs / 1000L)
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "PulseMix/1.4 (lecteur audio Android)")
            }
            // 404 = le serveur ne connaît pas ce morceau : négatif franc
            if (conn.responseCode != 200) return ""
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
                ?: o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
                ?: ""
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    // --------------------------------------------------------------- cache

    private fun cacheFile(context: Context, track: Track): File {
        val dir = File(context.filesDir, "lyrics")
        dir.mkdirs()
        return File(dir, md5(track.uri) + ".lrc")
    }

    /** Négatif (fichier vide) retenté après 7 jours : lrclib s'enrichit. */
    private const val NEGATIVE_TTL_MS = 7L * 86_400_000L

    private fun cachedText(context: Context, track: Track): String? = try {
        val f = cacheFile(context, track).takeIf { it.exists() }
        val text = f?.readText()
        if (text != null && text.isBlank() &&
            System.currentTimeMillis() - f.lastModified() > NEGATIVE_TTL_MS
        ) null else text
    } catch (_: Exception) {
        null
    }

    private fun cacheText(context: Context, track: Track, text: String) {
        try {
            cacheFile(context, track).writeText(text)
        } catch (_: Exception) {
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
