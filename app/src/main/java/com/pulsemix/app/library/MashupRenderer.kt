package com.pulsemix.app.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.pulsemix.app.mix.MashupEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale

/**
 * Rendu d'un mashup ([MashupEngine.Plan]) en fichier M4A avec le ffmpeg
 * embarqué, puis copie dans le dossier de la bibliothèque (SAF) pour qu'il
 * soit scanné, analysé et jouable comme n'importe quel morceau. Les
 * originaux ne sont jamais modifiés.
 *
 * Même contrainte que [SegmentExporter] : ffmpeg ne lit pas une URI SAF,
 * les deux sources sont d'abord copiées en cache. La progression vient de
 * `-progress pipe:1` (temps rendu / durée du plan).
 */
object MashupRenderer {

    sealed class State {
        object Idle : State()
        data class Working(val message: String, val pct: Int) : State()
        data class Done(val message: String) : State()
        data class Error(val message: String) : State()
    }

    val state: StateFlow<State> get() = _state
    private val _state = MutableStateFlow<State>(State.Idle)

    @Volatile private var proc: Process? = null
    @Volatile private var stopRequested = false

    fun requestStop() {
        stopRequested = true
        proc?.destroy()
    }

    fun reset() {
        if (_state.value !is State.Working) _state.value = State.Idle
    }

    /**
     * Rend [plan] et range le résultat dans [folderUri] (dossier scanné) —
     * ou, sans dossier, dans le dossier Extraits de l'appli.
     * @return vrai si un fichier est arrivé dans la bibliothèque (à rescanner).
     */
    @Synchronized
    fun render(context: Context, plan: MashupEngine.Plan, folderUri: String?): Boolean {
        stopRequested = false
        val label = "${plan.base.title} × ${plan.partner.title}"
        _state.value = State.Working("Préparation du mashup « $label »…", 0)
        log(
            "rendu demandé : « ${plan.base.title} » (${plan.base.bpm} BPM, ${plan.base.camelot}) × " +
                "« ${plan.partner.title} » (${plan.partner.bpm} BPM, ${plan.partner.camelot}) ; " +
                "tempo commun ${"%.1f".format(Locale.US, plan.tempo.target)}, " +
                "moitiés de ${plan.partBars} mesures, ${plan.totalBars} mesures au total"
        )
        val cmd = FfmpegBin.command(context)
        if (cmd == null) {
            fail("Mashup impossible : ffmpeg indisponible.")
            return false
        }
        val cacheDir = File(context.cacheDir, "mashup").apply { mkdirs() }
        val inA = File(cacheDir, "a-${System.nanoTime()}.${ext(plan.base.uri)}")
        val inB = File(cacheDir, "b-${System.nanoTime()}.${ext(plan.partner.uri)}")
        val outFile = File(cacheDir, MashupEngine.fileBaseName(plan) + ".m4a")
        try {
            _state.value = State.Working("Copie des deux morceaux…", 2)
            if (!copyToCache(context, plan.base.uri, inA) ||
                !copyToCache(context, plan.partner.uri, inB)
            ) {
                fail("Mashup impossible : un des deux fichiers est illisible.")
                return false
            }
            if (stopRequested) {
                _state.value = State.Done("Mashup annulé.")
                return false
            }
            val graph = MashupEngine.filterGraph(plan)
            fun sec(ms: Long) = "%.3f".format(Locale.US, ms / 1000.0)
            fun sec(s: Double) = "%.3f".format(Locale.US, s)
            val args = cmd + listOf(
                "-hide_banner", "-loglevel", "error", "-nostats",
                "-progress", "pipe:1", "-y",
                "-ss", sec(plan.anchorAMs), "-t", sec(plan.sourceSecondsA),
                "-i", inA.absolutePath,
                "-ss", sec(plan.anchorBMs), "-t", sec(plan.sourceSecondsB),
                "-i", inB.absolutePath,
                "-filter_complex", graph, "-map", "[out]",
                "-t", sec(plan.durationSeconds),
                "-metadata", "title=$label (mashup)",
                "-metadata", "artist=" + listOf(plan.base.artist, plan.partner.artist)
                    .filter { it.isNotBlank() }.distinct().joinToString(" & ")
                    .ifBlank { "PulseMix" },
                "-c:a", "aac", "-b:a", "192k",
                outFile.absolutePath
            )
            val pb = ProcessBuilder(args)
            pb.environment().putAll(FfmpegBin.env())
            val p = pb.start()
            proc = p
            // stderr lu à part (erreurs ffmpeg), stdout = progression
            val errBuf = StringBuilder()
            val errThread = Thread({
                try {
                    p.errorStream.bufferedReader().forEachLine { if (errBuf.length < 4000) errBuf.append(it).append('\n') }
                } catch (_: Exception) {
                }
            }, "MashupErr").apply { isDaemon = true; start() }
            val totalUs = (plan.durationSeconds * 1_000_000.0).toLong().coerceAtLeast(1L)
            p.inputStream.bufferedReader().forEachLine { line ->
                val v = when {
                    line.startsWith("out_time_us=") -> line.substringAfter('=').toLongOrNull()
                    line.startsWith("out_time_ms=") -> line.substringAfter('=').toLongOrNull()
                    else -> null
                }
                if (v != null && v >= 0L) {
                    val pct = (v * 100L / totalUs).toInt().coerceIn(0, 99)
                    _state.value = State.Working("Rendu du mashup… $pct %", pct)
                }
            }
            val code = p.waitFor()
            errThread.join(500L)
            proc = null
            if (stopRequested) {
                _state.value = State.Done("Mashup annulé.")
                log("annulé")
                return false
            }
            if (code != 0 || !outFile.exists() || outFile.length() < 4096) {
                log("échec ffmpeg ($code) : ${errBuf.take(600)}")
                fail("Rendu échoué (ffmpeg $code) : ${errBuf.lineSequence().firstOrNull { it.isNotBlank() }?.take(160) ?: "erreur inconnue"}")
                return false
            }
            log("rendu terminé : ${outFile.name} (${outFile.length() / 1000} ko)")
            // Rangement : dossier scanné (SAF) si possible, sinon Extraits
            val root = folderUri?.let {
                try {
                    DocumentFile.fromTreeUri(context, Uri.parse(it))
                } catch (_: Exception) {
                    null
                }
            }
            if (root != null && root.canWrite()) {
                _state.value = State.Working("Copie dans la bibliothèque…", 99)
                val doc = UrlImporter.copyIntoLibrary(context, root, outFile)
                if (doc == null) {
                    fail("Mashup rendu mais impossible à copier dans le dossier de la bibliothèque.")
                    return false
                }
                _state.value = State.Done(
                    "Mashup enregistré dans la bibliothèque : ${doc.name}. Analyse en cours…"
                )
                return true
            }
            val outDir = context.getExternalFilesDir("Extraits")
            if (outDir == null) {
                fail("Mashup rendu mais aucun dossier de destination.")
                return false
            }
            outDir.mkdirs()
            var dest = File(outDir, outFile.name)
            var n = 2
            while (dest.exists()) {
                dest = File(outDir, MashupEngine.fileBaseName(plan) + " ($n).m4a")
                n++
            }
            outFile.copyTo(dest, overwrite = false)
            _state.value = State.Done("Mashup enregistré : ${dest.absolutePath}")
            return false
        } catch (e: Exception) {
            log("exception : ${e::class.java.simpleName} ${e.message}")
            fail(
                if (stopRequested) "Mashup annulé."
                else "Mashup impossible : ${e.message ?: e::class.java.simpleName}"
            )
            return false
        } finally {
            proc = null
            inA.delete()
            inB.delete()
            outFile.delete()
        }
    }

    private fun fail(message: String) {
        _state.value = State.Error(message)
    }

    private fun copyToCache(context: Context, uri: String, dest: File): Boolean = try {
        context.contentResolver.openInputStream(Uri.parse(uri)).use { i ->
            if (i == null) false
            else {
                dest.outputStream().use { o -> i.copyTo(o, 64 * 1024) }
                true
            }
        }
    } catch (_: Exception) {
        false
    }

    /** Extension d'origine (aide ffmpeg à choisir le démuxeur). */
    private fun ext(uri: String): String =
        Uri.parse(uri).lastPathSegment?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it.length in 2..4 } ?: "bin"

    private fun log(message: String) {
        try {
            com.pulsemix.app.player.PlayerCore.engineLog("Mashup", message)
        } catch (_: Exception) {
        }
    }
}
