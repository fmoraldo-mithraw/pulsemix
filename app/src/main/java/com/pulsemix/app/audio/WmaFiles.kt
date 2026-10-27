package com.pulsemix.app.audio

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/**
 * Accès Android aux fichiers WMA pour [WmaDecoder] : reconnaissance d'un
 * WMA, ouverture d'une URI SAF / content par son descripteur, métadonnées.
 */
object WmaFiles {

    private val MIME = setOf("audio/x-ms-wma", "audio/x-ms-asf", "video/x-ms-asf", "audio/wma")

    /** Le décodeur natif est-il chargeable sur ce téléphone ? (évalué une
     *  fois ; faux = bibliothèques absentes, on n'insiste pas.) */
    val available: Boolean by lazy {
        try {
            org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.avformat::class.java)
            org.bytedeco.javacpp.Loader.load(org.bytedeco.ffmpeg.global.swresample::class.java)
            // Les avertissements de démultiplexage (« asf_read_pts failed »
            // à chaque seek) n'ont rien à faire dans logcat
            org.bytedeco.ffmpeg.global.avutil.av_log_set_level(
                org.bytedeco.ffmpeg.global.avutil.AV_LOG_FATAL
            )
            true
        } catch (t: Throwable) {
            log("décodeur natif indisponible : ${t::class.java.simpleName} ${t.message}")
            false
        }
    }

    /** Par le nom (extension de l'URI, encodée ou non). */
    fun isWmaName(s: String): Boolean {
        val l = s.lowercase()
        return l.endsWith(".wma") || l.endsWith("%2ewma")
    }

    /** WMA d'après l'URI, puis d'après le type déclaré par le fournisseur
     *  (fichier ouvert depuis une autre appli, sans extension dans l'URI). */
    fun isWma(context: Context, uri: Uri): Boolean {
        if (isWmaName(uri.toString())) return true
        if (uri.scheme != "content") return false
        return try {
            context.contentResolver.getType(uri)?.lowercase()?.let { it in MIME } == true
        } catch (_: Exception) {
            false
        }
    }

    /** Un décodeur ouvert, avec ce qui le tient en vie (descripteur ou
     *  copie temporaire). */
    class Opened(val decoder: WmaDecoder, private val keep: Closeable?, private val temp: File?) : Closeable {
        override fun close() {
            try {
                decoder.close()
            } catch (_: Throwable) {
            }
            try {
                keep?.close()
            } catch (_: Throwable) {
            }
            temp?.delete()
        }
    }

    /**
     * Ouvre [uri] pour le décodage. ffmpeg lit le fichier par
     * « /proc/self/fd/N » (le descripteur SAF ouvert ici) ; si le système
     * le refuse, repli sur une copie temporaire dans le cache.
     */
    fun open(context: Context, uri: Uri): Opened {
        check(available) { "décodeur WMA natif indisponible" }
        if (uri.scheme == "file") {
            return Opened(WmaDecoder(uri.path ?: error("chemin vide")), null, null)
        }
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("descripteur nul")
            return Opened(WmaDecoder("/proc/self/fd/${pfd.fd}"), pfd, null)
        } catch (e: Throwable) {
            try {
                pfd?.close()
            } catch (_: Throwable) {
            }
            log("lecture directe impossible (${e.message}), copie temporaire")
        }
        val dir = File(context.cacheDir, "wma").apply { mkdirs() }
        val tmp = File.createTempFile("lecture", ".wma", dir)
        try {
            context.contentResolver.openInputStream(uri).use { i ->
                requireNotNull(i) { "flux nul" }
                tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) }
            }
            return Opened(WmaDecoder(tmp.absolutePath), null, tmp)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    data class Meta(
        val title: String?,
        val artist: String?,
        val genre: String?,
        val durationMs: Long
    )

    /** Titre, artiste, genre et durée lus par le décodeur natif. */
    fun meta(context: Context, uri: Uri): Meta? = try {
        open(context, uri).use { o ->
            val d = o.decoder
            Meta(
                d.tags["title"],
                d.tags["artist"] ?: d.tags["album_artist"],
                d.tags["genre"],
                d.durationUs / 1000L
            )
        }
    } catch (e: Throwable) {
        log("métadonnées illisibles : ${e.message}")
        null
    }

    internal fun log(message: String) {
        try {
            com.pulsemix.app.player.PlayerCore.engineLog("WMA", message)
        } catch (_: Throwable) {
        }
    }
}
