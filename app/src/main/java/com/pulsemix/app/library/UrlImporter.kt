package com.pulsemix.app.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Importe de l'audio depuis une URL vers le dossier déjà scanné par le
 * lecteur (via SAF).
 *
 * Trois chemins : lien direct vers un fichier audio, item Internet Archive,
 * entrée d'un flux podcast RSS — téléchargés en HTTP simple ; toute autre
 * page (plateforme vidéo, radio, mix…) passe par yt-dlp embarqué
 * (YtDlpEngine) qui en extrait le flux audio.
 *
 * La récupération et l'usage des contenus relèvent de la responsabilité de
 * l'utilisateur ; l'app ne fait que télécharger l'URL fournie.
 */
object UrlImporter {

    sealed class State {
        object Idle : State()
        data class Working(val message: String, val done: Int, val total: Int) : State()
        data class Done(val imported: Int, val message: String) : State()
        data class Error(val message: String) : State()
    }

    val state: StateFlow<State> get() = _state
    private val _state = MutableStateFlow<State>(State.Idle)

    @Volatile private var stopRequested = false

    fun requestStop() {
        stopRequested = true
        YtDlpEngine.cancel()
    }

    fun reset() {
        _state.value = State.Idle
    }

    /**
     * @param folderUri tree URI SAF d'un dossier scanné (destination).
     */
    suspend fun import(context: Context, rawUrl: String, folderUri: String) =
        withContext(Dispatchers.IO) {
            stopRequested = false
            val url = rawUrl.trim()
            if (url.isBlank()) {
                _state.value = State.Error("URL vide.")
                return@withContext
            }
            val root = try {
                DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
            } catch (_: Exception) {
                null
            }
            if (root == null || !root.canWrite()) {
                _state.value = State.Error(
                    "Dossier de destination inaccessible en écriture. " +
                        "Re-choisis le dossier dans la bibliothèque."
                )
                return@withContext
            }
            try {
                val targets = resolveTargets(url)
                var imported = 0
                if (targets.isNotEmpty()) {
                    for ((i, t) in targets.withIndex()) {
                        if (stopRequested) break
                        _state.value = State.Working(
                            "Téléchargement : ${t.name}", i, targets.size
                        )
                        if (downloadTo(context, root, t)) imported++
                    }
                } else {
                    // Page web : extraction de l'audio via yt-dlp embarqué
                    imported = importViaYtDlp(context, root, url)
                }
                _state.value = State.Done(
                    imported,
                    if (imported == 0) "Rien n'a pu être importé."
                    else "$imported fichier(s) importé(s). Analyse en cours…"
                )
            } catch (e: Exception) {
                _state.value =
                    if (stopRequested) State.Done(0, "Import interrompu.")
                    else State.Error("Échec : ${shortError(e)}")
            }
        }

    /**
     * Met à jour le yt-dlp embarqué (les sites changent souvent ; c'est le
     * premier réflexe quand une extraction se met à échouer).
     */
    suspend fun updateEngine(context: Context) = withContext(Dispatchers.IO) {
        if (_state.value is State.Working) return@withContext
        _state.value = State.Working("Mise à jour de l'extracteur…", 0, 0)
        try {
            _state.value = State.Done(0, YtDlpEngine.update(context))
        } catch (e: Exception) {
            _state.value =
                State.Error("Mise à jour impossible : ${shortError(e)}")
        }
    }

    // ------------------------------------------------------------- ciblage

    private data class Target(val url: String, val name: String)

    /** Détermine les fichiers audio à télécharger selon le type d'URL. */
    private fun resolveTargets(url: String): List<Target> {
        val lower = url.lowercase()
        // Internet Archive : page d'item -> métadonnées -> fichiers audio
        val ia = Regex("archive\\.org/(details|download)/([^/?#]+)")
            .find(lower)
        if (ia != null) return archiveOrgTargets(ia.groupValues[2])
        // Flux / page podcast (RSS ou Atom) : entrées <enclosure>
        if (lower.endsWith(".xml") || lower.endsWith(".rss") ||
            lower.contains("/feed") || lower.contains("/rss")
        ) {
            val fromFeed = podcastTargets(url)
            if (fromFeed.isNotEmpty()) return fromFeed
        }
        // Lien direct vers un fichier audio
        if (looksAudio(lower)) {
            return listOf(Target(url, fileNameFromUrl(url)))
        }
        // Sinon : page web quelconque, à confier à yt-dlp (liste vide)
        return emptyList()
    }

    private fun archiveOrgTargets(identifier: String): List<Target> {
        val meta = httpText("https://archive.org/metadata/$identifier") ?: return emptyList()
        val obj = JSONObject(meta)
        val files = obj.optJSONArray("files") ?: return emptyList()
        val server = obj.optString("server", "archive.org")
        val dir = obj.optString("dir", "/$identifier")
        val out = ArrayList<Target>()
        val seenTitles = HashSet<String>()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val name = f.optString("name", "")
            if (!looksAudio(name.lowercase())) continue
            // Éviter les doublons de formats : garder un fichier par titre
            val title = f.optString("title", name).lowercase()
            if (!seenTitles.add(title)) continue
            out.add(Target("https://$server$dir/$name", name))
        }
        return out
    }

    private fun podcastTargets(feedUrl: String): List<Target> {
        val xml = httpText(feedUrl) ?: return emptyList()
        val out = ArrayList<Target>()
        val enclosure = Regex(
            "<enclosure[^>]*url=\"([^\"]+)\"[^>]*>", RegexOption.IGNORE_CASE
        )
        val titles = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE)
            .findAll(xml).map { it.groupValues[1] }.toList()
        var idx = 0
        for (m in enclosure.findAll(xml)) {
            val u = m.groupValues[1]
            if (!looksAudio(u.lowercase()) && !u.lowercase().contains("audio")) continue
            val t = titles.getOrNull(idx + 1)?.let { cleanXml(it) }
                ?.take(80)?.ifBlank { null }
            out.add(Target(u, (t ?: fileNameFromUrl(u)).ensureExt(u)))
            idx++
        }
        return out
    }

    // ------------------------------------------------------------- yt-dlp

    /**
     * Extrait l'audio d'une page web avec yt-dlp dans un dossier temporaire,
     * puis copie le résultat dans le dossier scanné.
     * @return nombre de fichiers copiés.
     */
    private fun importViaYtDlp(
        context: Context,
        root: DocumentFile,
        url: String
    ): Int {
        _state.value = State.Working(
            "Préparation de l'extracteur (long au premier lancement)…", 0, 0
        )
        val tmp = java.io.File(
            context.cacheDir, "url-import-${System.currentTimeMillis()}"
        )
        try {
            val files = YtDlpEngine.downloadAudio(context, url, tmp) { pct, eta ->
                if (pct >= 0f) {
                    val p = pct.coerceAtMost(100f).toInt()
                    val rest = if (eta > 0) " — reste ~${eta} s" else ""
                    _state.value = State.Working(
                        "Extraction de l'audio… $p %$rest", p, 100
                    )
                } else {
                    _state.value = State.Working("Extraction de l'audio…", 0, 0)
                }
            }
            var imported = 0
            for ((i, f) in files.withIndex()) {
                if (stopRequested) break
                _state.value = State.Working("Copie : ${f.name}", i, files.size)
                if (copyIntoFolder(context, root, f)) imported++
            }
            return imported
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Copie un fichier local (produit par yt-dlp) dans le dossier SAF. */
    private fun copyIntoFolder(
        context: Context,
        root: DocumentFile,
        file: java.io.File
    ): Boolean {
        val name = uniqueName(root, sanitize(file.name))
        val doc = root.createFile(mimeFor(name), name) ?: return false
        return try {
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
                true
            } ?: run { doc.delete(); false }
        } catch (_: Exception) {
            doc.delete()
            false
        }
    }

    // ---------------------------------------------------------- transfert

    private fun downloadTo(
        context: Context,
        root: DocumentFile,
        t: Target
    ): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(t.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PulseMix/1.4")
            }
            val type = conn.contentType ?: ""
            // Refuser une page HTML renvoyée à la place d'un média
            if (type.startsWith("text/") || type.contains("html")) return false
            val name = uniqueName(root, sanitize(t.name).ensureExt(t.url, type))
            val mime =
                if (type.startsWith("audio/")) type.substringBefore(';')
                else mimeFor(name)
            val doc = root.createFile(mime, name) ?: return false
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        if (stopRequested) {
                            doc.delete()
                            return false
                        }
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        total += r
                    }
                    if (total < 8_192) { // fichier suspect (erreur, page vide)
                        doc.delete()
                        return false
                    }
                }
                return true
            }
            doc.delete()
            false
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    // ------------------------------------------------------------- outils

    private fun httpText(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PulseMix/1.4")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        body
    } catch (_: Exception) {
        null
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".mp3") -> "audio/mpeg"
        name.endsWith(".m4a") -> "audio/mp4"
        name.endsWith(".aac") -> "audio/aac"
        name.endsWith(".flac") -> "audio/flac"
        name.endsWith(".ogg") || name.endsWith(".oga") ||
            name.endsWith(".opus") -> "audio/ogg"
        name.endsWith(".wav") -> "audio/wav"
        name.endsWith(".mka") || name.endsWith(".weba") -> "audio/webm"
        else -> "audio/*"
    }

    /** Les erreurs yt-dlp embarquent tout le stderr : ne garder que l'utile. */
    private fun shortError(e: Exception): String {
        val msg = e.message ?: return e::class.java.simpleName
        val lines = msg.lines().filter { it.isNotBlank() }
        val line = lines.lastOrNull { it.contains("ERROR", ignoreCase = true) }
            ?: lines.firstOrNull() ?: e::class.java.simpleName
        return line.take(300)
    }

    private fun looksAudio(s: String): Boolean =
        Regex("\\.(mp3|m4a|aac|flac|ogg|opus|wav|weba)(\\?|$)").containsMatchIn(s)

    private fun fileNameFromUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/').ifBlank { "import" }
        return sanitize(java.net.URLDecoder.decode(last, "UTF-8"))
    }

    private fun String.ensureExt(url: String, contentType: String = ""): String {
        if (Regex("\\.(mp3|m4a|aac|flac|ogg|opus|wav|weba)$", RegexOption.IGNORE_CASE)
                .containsMatchIn(this)
        ) return this
        val ext = when {
            contentType.contains("mpeg") -> "mp3"
            contentType.contains("mp4") || contentType.contains("m4a") -> "m4a"
            contentType.contains("flac") -> "flac"
            contentType.contains("ogg") || contentType.contains("opus") -> "ogg"
            contentType.contains("wav") -> "wav"
            else -> Regex("\\.(mp3|m4a|aac|flac|ogg|opus|wav)")
                .find(url.lowercase())?.groupValues?.get(1) ?: "mp3"
        }
        return "$this.$ext"
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().take(120)
            .ifBlank { "import.mp3" }

    private fun cleanXml(s: String): String =
        s.replace(Regex("<!\\[CDATA\\[|\\]\\]>"), "")
            .replace("&amp;", "&").replace("&#39;", "'").trim()

    private fun uniqueName(root: DocumentFile, name: String): String {
        if (root.findFile(name) == null) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (root.findFile("$base ($i)$ext") != null) i++
        return "$base ($i)$ext"
    }
}
