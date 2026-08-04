package com.pulsemix.app.library

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Écriture des tags dans le fichier audio lui-même (option des réglages ;
 * par défaut les corrections ne vivent que dans la bibliothèque de l'app).
 *
 * On passe par le ffmpeg déjà embarqué, avec `-c copy` : les métadonnées
 * sont réécrites, l'audio est recopié tel quel — ni réencodage, ni perte
 * de qualité, et ça marche pour tous les formats que le lecteur accepte.
 * Le fichier n'est remplacé qu'une fois la nouvelle version complète et
 * plausible : une écriture interrompue ne doit pas laisser un fichier
 * tronqué à la place de la musique.
 */
object TagWriter {

    /**
     * Réécrit titre et artiste dans le fichier désigné par [uri].
     * @return true si le fichier a bien été remplacé.
     */
    fun write(context: Context, uri: String, title: String, artist: String): Boolean {
        val bin = FfmpegBin.path(context) ?: return false
        val src = Uri.parse(uri)
        val ext = extensionOf(context, src) ?: return false
        val dir = File(context.cacheDir, "tagwrite").apply { mkdirs() }
        val inFile = File(dir, "in.$ext")
        val outFile = File(dir, "out.$ext")
        outFile.delete()
        return try {
            context.contentResolver.openInputStream(src).use { i ->
                if (i == null) return false
                inFile.outputStream().use { o -> i.copyTo(o, 64 * 1024) }
            }
            if (inFile.length() < 1024) return false

            val pb = ProcessBuilder(
                bin, "-hide_banner", "-loglevel", "error", "-y",
                "-i", inFile.absolutePath,
                // Toutes les pistes (l'audio et la pochette embarquée)
                "-map", "0", "-c", "copy",
                "-metadata", "title=$title",
                "-metadata", "artist=$artist",
                outFile.absolutePath
            )
            pb.environment().putAll(FfmpegBin.env())
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val log = proc.inputStream.bufferedReader().use { it.readText() }
            val code = proc.waitFor()
            // Garde-fou : une sortie beaucoup plus petite que l'entrée
            // signale une copie ratée, on ne remplace surtout pas
            if (code != 0 || !outFile.exists() ||
                outFile.length() < inFile.length() / 2
            ) {
                android.util.Log.w("TagWriter", "échec ($code) : ${log.take(400)}")
                return false
            }
            // "wt" tronque l'existant avant d'écrire
            context.contentResolver.openOutputStream(src, "wt").use { o ->
                if (o == null) return false
                outFile.inputStream().use { it.copyTo(o, 64 * 1024) }
            }
            true
        } catch (e: Exception) {
            android.util.Log.w("TagWriter", "écriture impossible", e)
            false
        } finally {
            inFile.delete()
            outFile.delete()
        }
    }

    /** ffmpeg déduit le format du conteneur de l'extension du fichier. */
    private fun extensionOf(context: Context, uri: Uri): String? {
        val name = androidx.documentfile.provider.DocumentFile
            .fromSingleUri(context, uri)?.name
            ?: uri.lastPathSegment
            ?: return null
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.takeIf { it.isNotBlank() && it.length <= 5 }
    }
}
