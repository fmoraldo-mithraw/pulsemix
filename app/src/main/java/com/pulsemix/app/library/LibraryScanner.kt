package com.pulsemix.app.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.pulsemix.app.analysis.AudioAnalyzer
import com.pulsemix.app.data.Track
import com.pulsemix.app.data.TrackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Parcourt le dossier choisi (Storage Access Framework), lit les métadonnées
 * et lance l'analyse audio de chaque nouveau fichier.
 */
object LibraryScanner {

    data class Progress(val done: Int, val total: Int, val currentName: String)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    @Volatile
    private var scanning = false

    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "mp4"
    )

    suspend fun scan(context: Context, treeUri: Uri, store: TrackStore) =
        withContext(Dispatchers.Default) {
            if (scanning) return@withContext
            scanning = true
            try {
                val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext
                val files = ArrayList<DocumentFile>()
                walk(root, files, 0)
                val audioFiles = files.filter { isAudio(it) }

                val uris = audioFiles.map { it.uri.toString() }.toSet()
                store.retainOnly(uris)

                val known = store.tracks.value.associateBy { it.uri }
                val total = audioFiles.size
                var done = 0
                _progress.value = Progress(0, total, "")

                val analyzer = AudioAnalyzer()
                for (doc in audioFiles) {
                    val uriStr = doc.uri.toString()
                    val existing = known[uriStr]
                    if (existing != null && existing.analyzed) {
                        done++
                        _progress.value = Progress(done, total, existing.title)
                        continue
                    }
                    val name = doc.name ?: "?"
                    _progress.value = Progress(done, total, name)

                    val meta = readMetadata(context, doc.uri, name)
                    val features = try {
                        analyzer.analyze(context, doc.uri, meta.third)
                    } catch (_: Exception) {
                        null
                    }
                    val track = if (features != null) {
                        Track(
                            uri = uriStr,
                            title = meta.first,
                            artist = meta.second,
                            durationMs = if (features.durationMs > 0) features.durationMs else meta.third,
                            bpm = features.bpm,
                            keyName = features.keyName,
                            camelot = features.camelot,
                            energyMean = features.energyMean,
                            energyPeak = features.energyPeak,
                            centroid = features.centroid,
                            onsetRate = features.onsetRate,
                            bestStartMs = features.bestStartMs,
                            segmentMs = features.segmentMs,
                            firstBeatMs = features.firstBeatMs,
                            analyzed = features.bpm > 0f
                        )
                    } else {
                        Track(
                            uri = uriStr,
                            title = meta.first,
                            artist = meta.second,
                            durationMs = meta.third,
                            analyzed = false
                        )
                    }
                    store.put(track)
                    done++
                    _progress.value = Progress(done, total, meta.first)
                    if (done % 5 == 0) store.save()
                }
                store.save()
            } finally {
                _progress.value = null
                scanning = false
            }
        }

    private fun walk(dir: DocumentFile, out: MutableList<DocumentFile>, depth: Int) {
        if (depth > 8) return
        for (f in dir.listFiles()) {
            if (f.isDirectory) walk(f, out, depth + 1)
            else if (f.isFile) out.add(f)
        }
    }

    private fun isAudio(doc: DocumentFile): Boolean {
        val type = doc.type
        if (type != null && type.startsWith("audio/")) return true
        val name = doc.name ?: return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in audioExtensions
    }

    /** @return (titre, artiste, duréeMs) */
    private fun readMetadata(context: Context, uri: Uri, fallbackName: String): Triple<String, String, Long> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }
                ?: fallbackName.substringBeforeLast('.')
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: ""
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            Triple(title, artist, duration)
        } catch (_: Exception) {
            Triple(fallbackName.substringBeforeLast('.'), "", 0L)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }
}
