package com.pulsemix.app.library

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.yausername.ffmpeg.FFmpeg
import java.io.File

/**
 * Lecture des fichiers WMA.
 *
 * Android ne sait pas les lire : ni le conteneur ASF ni le codec Windows
 * Media ne font partie d'AOSP (quelques appareils ajoutent le codec, aucun
 * ne peut être supposé le faire). Plutôt que de les afficher pour rien, on
 * les convertit une fois pour toutes en M4A à côté de l'original, avec le
 * ffmpeg déjà embarqué pour l'import depuis les plateformes. Le fichier
 * converti est ensuite un morceau comme un autre : lisible, analysable,
 * mixable.
 *
 * L'original n'est pas touché.
 */
object WmaConverter {

    /** Suffixe des fichiers produits : sert aussi à les reconnaître. */
    private const val SUFFIX = " (converti).m4a"

    @Volatile private var ffmpegBin: String? = null
    @Volatile private var ffmpegEnv: Map<String, String> = emptyMap()

    fun isWma(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() == "wma"

    /** Nom du fichier converti correspondant à [wmaName]. */
    fun convertedName(wmaName: String): String =
        wmaName.substringBeforeLast('.') + SUFFIX

    /**
     * Un morceau que le téléphone sait décoder ? On interroge le système
     * plutôt que de deviner : certains appareils lisent bien le WMA, et il
     * serait absurde de les faire convertir pour rien.
     */
    fun playable(context: Context, uri: Uri): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            var ok = false
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                ok = try {
                    android.media.MediaCodec.createDecoderByType(mime)
                        .also { it.release() }
                    true
                } catch (_: Exception) {
                    false
                }
                if (ok) break
            }
            ok
        } catch (_: Exception) {
            false
        } finally {
            try {
                ex.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Convertit [src] en M4A déposé dans [parent], sous le nom rendu par
     * [convertedName]. @return le fichier créé, ou null en cas d'échec.
     */
    fun convert(
        context: Context,
        src: DocumentFile,
        parent: DocumentFile
    ): DocumentFile? {
        val name = src.name ?: return null
        val outName = convertedName(name)
        parent.findFile(outName)?.let { if (it.length() > 0) return it }

        val bin = ensureFfmpeg(context) ?: return null
        val cacheDir = File(context.cacheDir, "wma").apply { mkdirs() }
        // ffmpeg ne sait pas lire une URI SAF : on lui donne des fichiers
        val inFile = File(cacheDir, "in.wma")
        val outFile = File(cacheDir, "out.m4a")
        outFile.delete()
        return try {
            context.contentResolver.openInputStream(src.uri).use { i ->
                if (i == null) return null
                inFile.outputStream().use { o -> i.copyTo(o, 64 * 1024) }
            }
            val pb = ProcessBuilder(
                bin, "-hide_banner", "-loglevel", "error", "-y",
                "-i", inFile.absolutePath,
                "-vn", "-c:a", "aac", "-b:a", "192k",
                outFile.absolutePath
            )
            pb.environment().putAll(ffmpegEnv)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val log = proc.inputStream.bufferedReader().use { it.readText() }
            val code = proc.waitFor()
            if (code != 0 || !outFile.exists() || outFile.length() < 1024) {
                android.util.Log.w("WmaConverter", "échec ($code) : ${log.take(400)}")
                return null
            }
            val doc = parent.createFile("audio/mp4", outName) ?: return null
            context.contentResolver.openOutputStream(doc.uri).use { o ->
                if (o == null) return null
                outFile.inputStream().use { it.copyTo(o, 64 * 1024) }
            }
            doc
        } catch (e: Exception) {
            android.util.Log.w("WmaConverter", "conversion impossible", e)
            null
        } finally {
            inFile.delete()
            outFile.delete()
        }
    }

    /**
     * Localise le binaire ffmpeg extrait par youtubedl-android et prépare
     * son environnement (ses bibliothèques ne sont pas dans les chemins
     * système). Le chemin exact dépend de la version de la bibliothèque :
     * on le cherche plutôt que de le coder en dur.
     */
    @Synchronized
    private fun ensureFfmpeg(context: Context): String? {
        ffmpegBin?.let { return it }
        val app = context.applicationContext
        try {
            FFmpeg.getInstance().init(app)
        } catch (e: Exception) {
            android.util.Log.w("WmaConverter", "ffmpeg indisponible", e)
            return null
        }
        val roots = listOfNotNull(
            app.noBackupFilesDir, app.filesDir
        )
        var bin: File? = null
        val libDirs = ArrayList<String>()
        for (root in roots) {
            root.walkTopDown().maxDepth(8).forEach { f ->
                if (f.isFile && f.name == "ffmpeg" && f.canExecute()) {
                    if (bin == null) bin = f
                } else if (f.isDirectory && f.name == "lib") {
                    libDirs.add(f.absolutePath)
                }
            }
            if (bin != null) break
        }
        val found = bin ?: return null
        ffmpegEnv = buildMap {
            if (libDirs.isNotEmpty()) {
                put("LD_LIBRARY_PATH", libDirs.joinToString(":"))
            }
            put("PATH", found.parentFile?.absolutePath + ":/system/bin")
        }
        ffmpegBin = found.absolutePath
        return ffmpegBin
    }
}
