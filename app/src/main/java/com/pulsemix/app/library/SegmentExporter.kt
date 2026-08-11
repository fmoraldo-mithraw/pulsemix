package com.pulsemix.app.library

import android.content.Context
import android.net.Uri
import com.pulsemix.app.data.Track
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale

/**
 * Export du « meilleur passage » d'un morceau en fichier M4A autonome.
 *
 * Même recette que [WmaConverter] : ffmpeg ne sait pas lire une URI SAF,
 * donc l'original est d'abord copié en cache, puis coupé
 * (bestStartMs → bestStartMs + segmentMs) et réencodé en AAC 192k dans le
 * dossier Extraits de l'appli. L'original n'est jamais modifié.
 */
object SegmentExporter {

    /** Message d'état du dernier export (chemin du fichier créé, ou erreur). */
    val message = MutableStateFlow<String?>(null)

    /**
     * Sérialisé : deux exports simultanés partageraient les fichiers de
     * travail et s'entre-détruiraient (le finally de l'un supprimant
     * l'entrée de l'autre).
     */
    @Synchronized
    fun export(context: Context, track: Track) {
        message.value = "Extraction de « ${track.title} »…"
        val cmd = FfmpegBin.command(context)
        if (cmd == null) {
            message.value = "Export impossible : ffmpeg indisponible."
            return
        }
        val outDir = context.getExternalFilesDir("Extraits")
        if (outDir == null) {
            message.value = "Export impossible : stockage indisponible."
            return
        }
        outDir.mkdirs()
        val cacheDir = File(context.cacheDir, "extrait").apply { mkdirs() }
        // Extension d'origine conservée sur la copie : elle aide ffmpeg à
        // choisir le bon démuxeur (le contenu est sondé de toute façon).
        val ext = Uri.parse(track.uri).lastPathSegment
            ?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it.length in 2..4 } ?: "bin"
        val inFile = File(cacheDir, "in-${System.nanoTime()}.$ext")
        val base = buildString {
            if (track.artist.isNotBlank()) append(track.artist).append(" - ")
            append(track.title)
        }.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        // Nom unique : deux morceaux au même artiste/titre (les doublons,
        // justement) ou un ré-export n'écrasent pas l'extrait précédent.
        var outFile = File(outDir, "$base (extrait).m4a")
        var n = 2
        while (outFile.exists()) {
            outFile = File(outDir, "$base (extrait $n).m4a")
            n++
        }
        try {
            context.contentResolver.openInputStream(Uri.parse(track.uri)).use { i ->
                if (i == null) {
                    message.value = "Export impossible : fichier illisible."
                    return
                }
                inFile.outputStream().use { o -> i.copyTo(o, 64 * 1024) }
            }
            // Locale.US impératif : en locale française, « %.3f » écrirait
            // « 12,500 » et ffmpeg refuserait la virgule.
            val startS = "%.3f".format(Locale.US, track.bestStartMs / 1000.0)
            val durS = "%.3f".format(
                Locale.US, track.segmentMs.coerceAtLeast(1_000L) / 1000.0
            )
            val pb = ProcessBuilder(
                cmd + listOf(
                    "-hide_banner", "-loglevel", "error", "-y",
                    // -ss/-t AVANT -i : positionnement à la lecture, découpe
                    // rapide sans décoder tout ce qui précède
                    "-ss", startS, "-t", durS,
                    "-i", inFile.absolutePath,
                    "-vn", "-c:a", "aac", "-b:a", "192k",
                    outFile.absolutePath
                )
            )
            pb.environment().putAll(FfmpegBin.env())
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val log = proc.inputStream.bufferedReader().use { it.readText() }
            val code = proc.waitFor()
            if (code != 0 || !outFile.exists() || outFile.length() < 1024) {
                android.util.Log.w(
                    "SegmentExporter", "échec ($code) : ${log.take(400)}"
                )
                outFile.delete()
                message.value =
                    "Export échoué : passage non découpable dans ce fichier."
            } else {
                message.value = "Extrait enregistré : ${outFile.absolutePath}"
            }
        } catch (e: Exception) {
            android.util.Log.w("SegmentExporter", "export impossible", e)
            message.value =
                "Export impossible : ${e.message ?: e::class.java.simpleName}"
        } finally {
            inFile.delete()
        }
    }
}
