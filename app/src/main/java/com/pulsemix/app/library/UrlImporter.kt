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
 * lecteur (via SAF). Pensé pour les sources dont on a le droit de récupérer
 * les fichiers : lien direct vers un fichier audio, item Internet Archive,
 * ou entrée d'un flux podcast RSS.
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
        StreamImporter.requestStop()
    }

    fun reset() {
        _state.value = State.Idle
    }

    /**
     * @param folderUri tree URI SAF d'un dossier scanné (destination).
     */
    suspend fun import(context: Context, rawUrl: String, folderUri: String) =
        withContext(Dispatchers.IO) {
            // Un seul import à la fois : relancer pendant qu'un import
            // tourne créait deux yt-dlp concurrents sur le même cache
            // (erreurs « CanceledException » et fichiers écrasés).
            if (_state.value is State.Working) return@withContext
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
            // Plateformes de streaming (YouTube, SoundCloud…) : yt-dlp embarqué
            if (StreamImporter.handles(url)) {
                importStream(context, url, root)
                return@withContext
            }
            try {
                val targets = resolveTargets(url)
                if (targets.isEmpty()) {
                    _state.value = State.Error(
                        "Aucun fichier audio trouvé à cette URL."
                    )
                    return@withContext
                }
                val imported = ArrayList<DocumentFile>()
                for ((i, t) in targets.withIndex()) {
                    if (stopRequested) break
                    _state.value = State.Working(
                        "Téléchargement : ${t.name}", i, targets.size
                    )
                    downloadTo(context, root, t)?.let { imported.add(it) }
                }
                _state.value = State.Done(
                    imported.size,
                    if (imported.isEmpty()) "Rien n'a pu être importé."
                    else "${imported.size} fichier(s) importé(s). " +
                        "Analyse en cours…" + duplicateWarning(context, imported)
                )
            } catch (e: Exception) {
                _state.value = State.Error(
                    "Échec : ${e.message ?: e::class.java.simpleName}"
                )
            }
        }

    // -------------------------------------------------- streaming (yt-dlp)

    /**
     * Import via yt-dlp : téléchargement + extraction MP3 dans le cache,
     * puis copie vers le dossier SAF. Gère les playlists (une piste = un
     * fichier). La barre de progression suit le pourcentage yt-dlp.
     */
    private fun importStream(
        context: Context,
        url: String,
        root: DocumentFile
    ) {
        StreamImporter.log("import demandé : $url")
        try {
            val files = StreamImporter.download(context, url) { msg, pct ->
                _state.value = State.Working(msg, pct.coerceAtLeast(0), 100)
            }
            StreamImporter.log(
                "${files.size} fichier(s) extrait(s)" +
                    files.joinToString("") { " ; ${it.name} (${it.length() / 1_000_000} Mo)" }
            )
            if (files.isEmpty()) {
                _state.value = if (stopRequested) {
                    State.Done(0, "Import arrêté.")
                } else {
                    State.Error(
                        "Aucun audio récupéré à cette URL. Si l'erreur vient " +
                            "de l'extraction, essaie « Mettre à jour le moteur »."
                    )
                }
                return
            }
            val imported = ArrayList<DocumentFile>()
            for ((i, f) in files.withIndex()) {
                if (stopRequested) break
                _state.value = State.Working(
                    "Copie : ${f.name}", i, files.size
                )
                val doc = copyLocalFile(context, root, f)
                if (doc != null) imported.add(doc)
                else StreamImporter.log("copie impossible : ${f.name}")
            }
            StreamImporter.log("${imported.size} fichier(s) copié(s) dans la bibliothèque")
            _state.value = State.Done(
                imported.size,
                if (imported.isEmpty()) "Rien n'a pu être importé."
                else "${imported.size} fichier(s) importé(s). " +
                    "Analyse en cours…" + duplicateWarning(context, imported)
            )
        } catch (e: Exception) {
            StreamImporter.log(
                (if (stopRequested) "arrêté" else "échec") +
                    " : ${e::class.java.simpleName} ${e.message?.take(300)}"
            )
            _state.value = if (stopRequested) {
                State.Done(0, "Import arrêté.")
            } else {
                State.Error(
                    "Échec du téléchargement : " +
                        (e.message ?: e::class.java.simpleName).take(200) +
                        "\nSi l'erreur mentionne l'extraction ou le site, " +
                        "essaie « Mettre à jour le moteur » puis relance."
                )
            }
        } finally {
            StreamImporter.cleanup(context)
        }
    }

    /** Copie un fichier local (cache) vers le dossier SAF de la bibliothèque.
     *  @return le document créé, null en cas d'échec. */
    private fun copyLocalFile(
        context: Context,
        root: DocumentFile,
        src: java.io.File
    ): DocumentFile? {
        return try {
            // Suffixe de durée du fichier de travail retiré (voir
            // StreamImporter.download)
            val name = uniqueName(root, sanitize(StreamImporter.cleanName(src.name)))
            val mime = when {
                name.endsWith(".mp3") -> "audio/mpeg"
                name.endsWith(".m4a") -> "audio/mp4"
                name.endsWith(".flac") -> "audio/flac"
                name.endsWith(".ogg") || name.endsWith(".opus") -> "audio/ogg"
                name.endsWith(".wav") -> "audio/wav"
                else -> "audio/*"
            }
            val doc = root.createFile(mime, name) ?: return null
            val out = context.contentResolver.openOutputStream(doc.uri)
            if (out == null) {
                doc.delete()
                return null
            }
            out.use { o -> src.inputStream().use { it.copyTo(o, 64 * 1024) } }
            doc
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Met à jour le binaire yt-dlp embarqué. À lancer quand un import
     * streaming échoue : les extracteurs cassent quand les sites changent.
     */
    suspend fun updateEngine(context: Context) = withContext(Dispatchers.IO) {
        _state.value = State.Working(
            "Mise à jour du moteur de téléchargement…", 0, 0
        )
        try {
            val version = StreamImporter.update(context)
            _state.value = State.Done(
                0,
                "Moteur à jour" +
                    (version?.let { " (yt-dlp $it)" } ?: "") +
                    ". Relance l'import."
            )
        } catch (e: Exception) {
            _state.value = State.Error(
                "Mise à jour impossible : " +
                    (e.message ?: e::class.java.simpleName).take(200)
            )
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
        // Dernier recours : tenter le direct, la vérification du type se fera
        // au téléchargement
        return listOf(Target(url, fileNameFromUrl(url)))
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

    // ---------------------------------------------------------- transfert

    /** @return le document créé, null en cas d'échec. */
    private fun downloadTo(
        context: Context,
        root: DocumentFile,
        t: Target
    ): DocumentFile? {
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
            if (type.startsWith("text/") || type.contains("html")) return null
            val name = uniqueName(root, sanitize(t.name).ensureExt(t.url, type))
            val mime = when {
                type.startsWith("audio/") -> type.substringBefore(';')
                name.endsWith(".mp3") -> "audio/mpeg"
                name.endsWith(".m4a") -> "audio/mp4"
                name.endsWith(".flac") -> "audio/flac"
                name.endsWith(".ogg") || name.endsWith(".opus") -> "audio/ogg"
                name.endsWith(".wav") -> "audio/wav"
                else -> "audio/*"
            }
            val doc = root.createFile(mime, name) ?: return null
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        if (stopRequested) {
                            doc.delete()
                            return null
                        }
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        total += r
                    }
                    if (total < 8_192) { // fichier suspect (erreur, page vide)
                        doc.delete()
                        return null
                    }
                }
                return doc
            }
            doc.delete()
            null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    // -------------------------------------------------- garde anti-doublon

    /**
     * Compare les fichiers importés à la bibliothèque : nom de fichier
     * normalisé (mêmes mots) ET durée à ±7 s. En cas de forte ressemblance,
     * le message final le signale — sans rien empêcher : le fichier est
     * importé, l'utilisateur décide.
     */
    private fun duplicateWarning(
        context: Context,
        imported: List<DocumentFile>
    ): String {
        val library = try {
            com.pulsemix.app.Graph.store.tracks.value
        } catch (_: Exception) {
            emptyList()
        }
        val sb = StringBuilder()
        // Fichiers déjà traités du MÊME lot (tokens, durée, nom affiché) :
        // une playlist YouTube peut contenir deux uploads du même titre —
        // la bibliothèque ne les connaît pas encore (le rescan n'a pas eu
        // lieu), il faut donc aussi comparer les importés entre eux.
        data class BatchEntry(val tokens: Set<String>, val durMs: Long, val label: String)
        val batch = ArrayList<BatchEntry>()
        for (doc in imported) {
            val name = (doc.name ?: continue).substringBeforeLast('.')
            val fileTokens = NameMatch.tokens(name)
            if (fileTokens.isEmpty()) continue
            val durMs = mediaDurationMs(context, doc)
            if (durMs <= 0L) continue // durée illisible : pas de verdict fiable
            var bestLabel: String? = null
            var bestSim = 0f
            for (t in library) {
                if (t.durationMs <= 0L) continue
                if (kotlin.math.abs(t.durationMs - durMs) > 7_000L) continue
                val sim = NameMatch.similarityToFile(fileTokens, t.title, t.artist)
                    .coerceAtLeast(
                        NameMatch.similarity(fileTokens, NameMatch.tokens(t.title))
                    )
                if (sim > bestSim) {
                    bestSim = sim
                    bestLabel = buildString {
                        if (t.artist.isNotBlank()) append(t.artist).append(" - ")
                        append(t.title)
                    }
                }
            }
            for (b in batch) {
                if (kotlin.math.abs(b.durMs - durMs) > 7_000L) continue
                val sim = NameMatch.similarity(fileTokens, b.tokens)
                if (sim > bestSim) {
                    bestSim = sim
                    bestLabel = "${b.label} (même import)"
                }
            }
            batch.add(BatchEntry(fileTokens, durMs, name))
            if (bestLabel != null && bestSim >= 0.6f) {
                sb.append("\n⚠ Doublon probable de : ").append(bestLabel)
            }
        }
        return sb.toString()
    }

    /** Durée du fichier importé (MediaMetadataRetriever), 0 si illisible. */
    private fun mediaDurationMs(context: Context, doc: DocumentFile): Long {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, doc.uri)
            mmr.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
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
