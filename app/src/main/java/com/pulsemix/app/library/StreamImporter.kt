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
        // Durée dans le nom du fichier de travail (« {secondes} ») : c'est
        // ce qui permet d'ESTIMER l'avancement de la conversion MP3 —
        // yt-dlp n'en dit rien, et sur une vidéo de 14 min le téléchargement
        // affichait « 100 % » puis plus rien pendant une à deux minutes
        // (vécu comme bloqué). UrlImporter retire ce suffixe à la copie.
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
            .addOption(
                "-o",
                File(dir, "%(title).120B [%(id)s] {%(duration)s}.%(ext)s").absolutePath
            )
        stopRequested = false
        val procId = "pulsemix-import-${procSeq.incrementAndGet()}"
        currentDownloadId = procId
        val t0 = android.os.SystemClock.elapsedRealtime()
        val downloadDoneAt = java.util.concurrent.atomic.AtomicLong(0L)
        // Vigie de la conversion : yt-dlp ne remonte que le téléchargement.
        // Ce fil regarde le MP3 grossir dans le cache et en déduit un
        // pourcentage (VBR qualité 0 ≈ 245 kbit/s), puis signale l'écriture
        // des tags (fichier « .temp.mp3 »).
        val monitoring = java.util.concurrent.atomic.AtomicBoolean(true)
        val monitor = Thread({
            var lastMsg = ""
            while (monitoring.get()) {
                try {
                    Thread.sleep(500L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!monitoring.get()) break
                val files = dir.listFiles().orEmpty()
                val temp = files.firstOrNull { it.name.endsWith(".temp.mp3") }
                val mp3 = files.filter { it.name.endsWith(".mp3") && !it.name.endsWith(".temp.mp3") }
                    .maxByOrNull { it.length() }
                val msg: String
                val pct: Int
                when {
                    temp != null -> {
                        msg = "Écriture des tags…"
                        pct = 99
                    }
                    mp3 != null -> {
                        val dur = durationFromName(mp3.name)
                        val est = if (dur > 0) (mp3.length() * 100L / (dur * 30_600L))
                            .toInt().coerceIn(1, 99) else -1
                        msg = if (est > 0) "Conversion en MP3… $est %"
                        else "Conversion en MP3… ${mp3.length() / 1_000_000} Mo"
                        pct = est
                    }
                    else -> continue
                }
                downloadDoneAt.compareAndSet(0L, android.os.SystemClock.elapsedRealtime())
                if (msg != lastMsg) {
                    lastMsg = msg
                    onProgress(msg, pct)
                }
            }
        }, "ImportMonitor").apply { isDaemon = true }
        try {
            monitor.start()
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
            monitoring.set(false)
            monitor.interrupt()
            currentDownloadId = null
            val now = android.os.SystemClock.elapsedRealtime()
            val conv = downloadDoneAt.get()
            log(
                "yt-dlp terminé en ${(now - t0) / 1000} s" +
                    (if (conv > 0L) " (dont conversion ${(now - conv) / 1000} s)" else "")
            )
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

    /** Durée (s) glissée dans le nom du fichier de travail : « … {842}.mp3 ». */
    private fun durationFromName(name: String): Long =
        Regex("\\{(\\d+)\\}").findAll(name).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    /** Retire le suffixe de durée du nom de travail (voir download). */
    fun cleanName(name: String): String =
        name.replace(Regex(" ?\\{(\\d+|NA)\\}"), "")

    /** Journal de l'import (service_log.txt, tag [Import]). */
    fun log(message: String) {
        try {
            com.pulsemix.app.player.PlayerCore.engineLog("Import", message)
        } catch (_: Exception) {
        }
    }

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
