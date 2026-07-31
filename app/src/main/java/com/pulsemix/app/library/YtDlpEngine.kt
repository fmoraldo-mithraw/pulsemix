package com.pulsemix.app.library

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/**
 * Enveloppe fine autour de youtubedl-android (yt-dlp + ffmpeg embarqués).
 * Sert à l'import depuis une URL quand le lien n'est pas un fichier audio
 * direct : yt-dlp extrait le meilleur flux audio de la page (plateformes
 * vidéo, radios, mixes…) vers un dossier temporaire local, puis UrlImporter
 * copie le résultat dans le dossier scanné (SAF).
 */
object YtDlpEngine {

    /** Un seul import à la fois : identifiant fixe pour pouvoir l'annuler. */
    private const val PROCESS_ID = "pulsemix-url-import"

    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "weba", "mka"
    )

    @Volatile private var initialized = false
    private val initLock = Any()

    /**
     * Extrait Python, yt-dlp et ffmpeg des jniLibs au premier appel
     * (10-30 s la première fois, quasi instantané ensuite). Bloquant.
     */
    fun ensureInit(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            val app = context.applicationContext
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            initialized = true
        }
    }

    /**
     * Télécharge l'audio de [url] dans [destDir] : conteneur natif du flux
     * (opus, m4a…), métadonnées et pochette incorporées quand c'est possible.
     * Bloquant ; à appeler depuis Dispatchers.IO.
     *
     * @param onProgress pourcentage 0-100 (ou -1 si inconnu) et ETA en secondes.
     * @return les fichiers audio produits.
     */
    fun downloadAudio(
        context: Context,
        url: String,
        destDir: File,
        onProgress: (percent: Float, etaSeconds: Long) -> Unit
    ): List<File> {
        ensureInit(context)
        destDir.mkdirs()
        val request = YoutubeDLRequest(url).apply {
            addOption("-x")
            addOption("--embed-metadata")
            addOption("--embed-thumbnail")
            // Une URL de vidéo qui traîne un paramètre de playlist ne doit
            // importer que la vidéo ; une URL de playlist reste une playlist.
            addOption("--no-playlist")
            addOption("--socket-timeout", "15")
            addOption("--retries", "2")
            addOption("-o", File(destDir, "%(title).100s.%(ext)s").absolutePath)
        }
        YoutubeDL.getInstance().execute(request, PROCESS_ID) { percent, eta, _ ->
            onProgress(percent, eta)
        }
        return destDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in audioExtensions }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /** Tue le processus yt-dlp en cours (l'execute() en vol lève alors). */
    fun cancel() {
        try {
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        } catch (_: Exception) {
        }
    }

    /**
     * Met à jour le binaire yt-dlp embarqué (canal stable). Utile quand un
     * site change et que l'extraction se met à échouer. Bloquant.
     */
    fun update(context: Context): String {
        ensureInit(context)
        val app = context.applicationContext
        val status = YoutubeDL.getInstance().updateYoutubeDL(app)
        val version = YoutubeDL.getInstance().version(app) ?: "?"
        return if (status == YoutubeDL.UpdateStatus.DONE) {
            "Extracteur mis à jour (yt-dlp $version)."
        } else {
            "Extracteur déjà à jour (yt-dlp $version)."
        }
    }
}
