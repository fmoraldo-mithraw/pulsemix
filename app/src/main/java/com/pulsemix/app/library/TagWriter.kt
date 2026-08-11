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
 *
 * Le SAF ne permet pas de remplacer un document par renommage : il faut
 * réécrire l'original en place, en le tronquant d'abord. Trois ceintures
 * pour qu'aucune musique ne meure dans cette fenêtre :
 *  - une COPIE COMPLÈTE de l'original est gardée dans les fichiers de
 *    l'appli, avec un marqueur portant son URI, AVANT de toucher au
 *    fichier ;
 *  - après l'écriture, le document est relu et sa taille comparée ; tout
 *    écart (comme toute exception) déclenche la restauration immédiate de
 *    la copie ;
 *  - si le processus meurt en pleine écriture, [recoverPending] retrouve
 *    le marqueur au démarrage suivant et restaure la copie.
 *
 * Une écriture à la fois : les fichiers de travail sont uniques par appel,
 * mais sérialiser évite aussi de tenir deux copies intégrales en même
 * temps et de saturer le stockage.
 */
object TagWriter {

    private fun workDir(context: Context): File =
        File(context.filesDir, "tagwrite").apply { mkdirs() }

    /**
     * Restaure les écritures interrompues par une mort du processus :
     * chaque marqueur laissé désigne un original copié mais peut-être
     * tronqué — la copie est réécrite dans le fichier. À appeler une fois
     * au démarrage, avant toute nouvelle écriture.
     */
    @Synchronized
    fun recoverPending(context: Context) {
        val dir = workDir(context)
        val markers = dir.listFiles { f -> f.name.endsWith(".uri") } ?: return
        for (marker in markers) {
            try {
                val uri = marker.readText()
                val backup = File(dir, marker.name.removeSuffix(".uri"))
                if (uri.isNotBlank() && backup.exists() && backup.length() > 0) {
                    context.contentResolver
                        .openOutputStream(Uri.parse(uri), "wt")?.use { o ->
                            backup.inputStream().use { it.copyTo(o, 64 * 1024) }
                        }
                    android.util.Log.w(
                        "TagWriter", "écriture interrompue restaurée : $uri"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("TagWriter", "restauration impossible", e)
            } finally {
                File(dir, marker.name.removeSuffix(".uri")).delete()
                marker.delete()
            }
        }
    }

    /**
     * Réécrit titre et artiste dans le fichier désigné par [uri].
     * @return true si le fichier a bien été remplacé (et vérifié).
     */
    @Synchronized
    fun write(context: Context, uri: String, title: String, artist: String): Boolean {
        val cmd = FfmpegBin.command(context) ?: return false
        val src = Uri.parse(uri)
        val ext = extensionOf(context, src) ?: return false
        val dir = workDir(context)
        val stamp = "w${System.nanoTime()}"
        val inFile = File(dir, "$stamp.$ext")
        val marker = File(dir, "$stamp.$ext.uri")
        val outFile = File(dir, "$stamp-out.$ext")
        var touchedOriginal = false
        return try {
            context.contentResolver.openInputStream(src).use { i ->
                if (i == null) return false
                inFile.outputStream().use { o -> i.copyTo(o, 64 * 1024) }
            }
            if (inFile.length() < 1024) return false

            val pb = ProcessBuilder(
                cmd + listOf(
                    "-hide_banner", "-loglevel", "error", "-y",
                    "-i", inFile.absolutePath,
                    // Toutes les pistes (l'audio et la pochette embarquée)
                    "-map", "0", "-c", "copy",
                    "-metadata", "title=$title",
                    "-metadata", "artist=$artist",
                    outFile.absolutePath
                )
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
            // Le marqueur AVANT de toucher à l'original : à partir d'ici,
            // la copie est restaurable même si le processus meurt.
            marker.writeText(uri)
            touchedOriginal = true
            // "wt" tronque l'existant avant d'écrire
            context.contentResolver.openOutputStream(src, "wt").use { o ->
                if (o == null) return false
                outFile.inputStream().use { it.copyTo(o, 64 * 1024) }
            }
            // Relire pour VÉRIFIER : la taille du document doit être celle
            // de la version écrite, sinon l'écriture s'est perdue en route.
            val written = context.contentResolver.openInputStream(src)?.use { s ->
                var n = 0L
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = s.read(buf)
                    if (r < 0) break
                    n += r
                }
                n
            } ?: -1L
            if (written != outFile.length()) {
                android.util.Log.w(
                    "TagWriter", "vérification en échec ($written/${outFile.length()})"
                )
                restore(context, src, inFile)
                return false
            }
            touchedOriginal = false
            true
        } catch (e: Exception) {
            android.util.Log.w("TagWriter", "écriture impossible", e)
            if (touchedOriginal) restore(context, src, inFile)
            false
        } finally {
            marker.delete()
            inFile.delete()
            outFile.delete()
        }
    }

    /** Remet la copie de l'original dans le document (écriture ratée). */
    private fun restore(context: Context, src: Uri, backup: File) {
        try {
            context.contentResolver.openOutputStream(src, "wt")?.use { o ->
                backup.inputStream().use { it.copyTo(o, 64 * 1024) }
            }
            android.util.Log.w("TagWriter", "original restauré après échec")
        } catch (e: Exception) {
            android.util.Log.w("TagWriter", "RESTAURATION IMPOSSIBLE", e)
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
