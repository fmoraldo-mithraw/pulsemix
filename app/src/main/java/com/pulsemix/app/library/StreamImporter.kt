package com.pulsemix.app.library

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File

/**
 * Téléchargement audio depuis les plateformes de streaming (YouTube,
 * SoundCloud, Bandcamp…) via le yt-dlp embarqué par youtubedl-android.
 * Les fichiers sont extraits en MP3 dans le cache de l'app ; UrlImporter
 * se charge ensuite de les copier vers le dossier SAF de la bibliothèque.
 *
 * La récupération et l'usage des contenus relèvent de la responsabilité de
 * l'utilisateur ; l'app ne fait qu'exécuter yt-dlp sur l'URL fournie.
 */
object StreamImporter {

    data class SearchResult(
        val videoId: String,
        val url: String,
        val title: String,
        val channel: String,
        val durationText: String
    )

    // Identifiants de processus UNIQUES par opération : la bibliothèque
    // lève CanceledException quand un processus finit en erreur alors que
    // son id a quitté sa table — un id fixe réutilisé entre deux imports
    // qui se chevauchent déclenchait de faux « annulé » masquant la
    // vraie erreur.
    private val procSeq = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var currentDownloadId: String? = null
    @Volatile private var currentSearchId: String? = null
    @Volatile private var stopRequested = false

    // Hôtes confiés à yt-dlp ; le reste passe par le téléchargement direct
    // de UrlImporter (fichier, Internet Archive, podcast).
    private val supportedHosts = listOf(
        "youtube.com", "youtu.be", "soundcloud.com", "bandcamp.com",
        "mixcloud.com", "vimeo.com", "dailymotion.com", "twitch.tv"
    )

    @Volatile private var ready = false

    /** Extraction des binaires python/yt-dlp/ffmpeg au premier usage. */
    @Synchronized
    private fun ensureReady(context: Context) {
        if (ready) return
        val app = context.applicationContext
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        ready = true
    }

    /** Vrai si l'URL pointe vers une plateforme gérée par yt-dlp. */
    fun handles(url: String): Boolean {
        val withScheme =
            if (url.contains("://")) url.trim() else "https://${url.trim()}"
        val host = try {
            java.net.URI(withScheme).host?.lowercase() ?: return false
        } catch (_: Exception) {
            return false
        }
        return supportedHosts.any { host == it || host.endsWith(".$it") }
    }

    /** Interrompt le téléchargement en cours (yt-dlp est tué). */
    fun requestStop() {
        stopRequested = true
        currentDownloadId?.let {
            runCatching { YoutubeDL.getInstance().destroyProcessById(it) }
        }
    }

    /**
     * Télécharge l'audio de [url] (piste seule ou playlist) et l'extrait en
     * MP3 dans le cache. [onProgress] reçoit un message et un pourcentage
     * (négatif quand yt-dlp ne sait pas encore l'estimer).
     *
     * @return les fichiers audio produits, prêts à être copiés.
     */
    fun download(
        context: Context,
        url: String,
        onProgress: (String, Int) -> Unit
    ): List<File> {
        ensureReady(context)
        val dir = workDir(context)
        dir.deleteRecursively()
        dir.mkdirs()
        onProgress("Préparation du téléchargement…", 0)
        val request = YoutubeDLRequest(url.trim())
            .addOption("-x")
            .addOption("--audio-format", "mp3")
            .addOption("--audio-quality", "0")
            // Écrit titre/artiste dans le fichier : le scan de la
            // bibliothèque lit ces tags au lieu du nom de fichier.
            .addOption("--embed-metadata")
            .addOption("--ignore-errors")
            .addOption("--no-mtime")
            .addOption("--no-warnings")
            .addOption("-o", File(dir, "%(title).120B [%(id)s].%(ext)s").absolutePath)
        stopRequested = false
        val procId = "pulsemix-import-${procSeq.incrementAndGet()}"
        currentDownloadId = procId
        try {
            YoutubeDL.getInstance().execute(request, procId) {
                    progress: Float, _: Long, _: String ->
                val pct = progress.toInt()
                onProgress(
                    if (pct in 0..100) "Téléchargement… $pct %"
                    else "Téléchargement…",
                    pct
                )
            }
        } catch (e: YoutubeDL.CanceledException) {
            // Vrai arrêt demandé : remonter pour l'état « Import arrêté ».
            // Sinon (course interne de la lib), garder ce qui a été extrait.
            if (stopRequested) throw e
        } finally {
            currentDownloadId = null
        }
        return dir.listFiles().orEmpty()
            .filter { f ->
                f.isFile && f.length() > 0 &&
                    f.extension.lowercase() in
                    listOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")
            }
            .sortedBy { it.name }
    }

    /** Vide le cache de téléchargement (à appeler après la copie SAF). */
    fun cleanup(context: Context) {
        runCatching { workDir(context).deleteRecursively() }
    }

    /**
     * Met à jour le binaire yt-dlp (canal stable). À relancer quand un site
     * casse ses extracteurs.
     *
     * @return la version installée après mise à jour, si connue.
     */
    fun update(context: Context): String? {
        ensureReady(context)
        val app = context.applicationContext
        YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
        return YoutubeDL.getInstance().version(app)
    }

    // ----------------------------------------------------------- recherche

    /**
     * Recherche YouTube sans clé API : `ytsearchN:` + métadonnées à plat
     * (une ligne JSON par résultat, pas de téléchargement).
     */
    fun search(
        context: Context,
        query: String,
        limit: Int = 15
    ): List<SearchResult> {
        ensureReady(context)
        val request = YoutubeDLRequest("ytsearch$limit:${query.trim()}")
            .addOption("--dump-json")
            .addOption("--flat-playlist")
            .addOption("--skip-download")
            .addOption("--no-warnings")
        val procId = "pulsemix-search-${procSeq.incrementAndGet()}"
        currentSearchId = procId
        val out = try {
            YoutubeDL.getInstance().execute(request, procId).out
        } catch (_: YoutubeDL.CanceledException) {
            return emptyList()
        } finally {
            currentSearchId = null
        }
        return out.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .mapNotNull { line ->
                try {
                    val o = JSONObject(line)
                    val id = o.optStr("id")
                    if (id.isBlank()) return@mapNotNull null
                    SearchResult(
                        videoId = id,
                        url = o.optStr("url")
                            .ifBlank { "https://www.youtube.com/watch?v=$id" },
                        title = o.optStr("title").ifBlank { id },
                        channel = o.optStr("channel")
                            .ifBlank { o.optStr("uploader") },
                        durationText = formatDuration(o.optDouble("duration"))
                    )
                } catch (_: Exception) {
                    null
                }
            }
            .toList()
    }

    /** Interrompt la recherche en cours. */
    fun cancelSearch() {
        currentSearchId?.let {
            runCatching { YoutubeDL.getInstance().destroyProcessById(it) }
        }
    }

    // ------------------------------------------------------------- outils

    private fun workDir(context: Context): File =
        File(context.cacheDir, "stream_import")

    // optString d'Android renvoie la chaîne "null" pour un null JSON
    private fun JSONObject.optStr(name: String): String {
        if (isNull(name)) return ""
        return optString(name, "")
    }

    private fun formatDuration(seconds: Double): String {
        if (seconds.isNaN() || seconds <= 0) return ""
        val s = seconds.toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec)
        else "%d:%02d".format(m, sec)
    }
}
