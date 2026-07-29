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
import org.json.JSONObject

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

    @Volatile
    private var stopRequested = false

    /** Arrête proprement l'analyse en cours (l'état est sauvegardé ; la
     *  reprise sautera les morceaux déjà analysés). */
    fun requestStop() {
        stopRequested = true
    }

    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "mp4"
    )

    /**
     * @param restoreBackup false pour un « tout réanalyser » : la sauvegarde
     * présente dans le dossier est supprimée au lieu d'être restaurée, sinon
     * elle réinjecterait les anciennes analyses et annulerait la réanalyse.
     */
    suspend fun scan(
        context: Context,
        treeUri: Uri,
        store: TrackStore,
        restoreBackup: Boolean = true
    ) =
        withContext(Dispatchers.Default) {
            if (scanning) return@withContext
            scanning = true
            stopRequested = false
            try {
                // Affichage immédiat : le parcours du dossier (SAF) peut
                // prendre plusieurs secondes avant le premier fichier analysé.
                _progress.value = Progress(0, 0, "")
                val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext
                val files = ArrayList<DocumentFile>()
                walk(root, files, 0)
                val audioFiles = files.filter { isAudio(it) }

                val uris = audioFiles.map { it.uri.toString() }.toSet()
                store.retainOnly(uris)

                // Restaurer la sauvegarde stockée dans le dossier de musique :
                // après une désinstallation (ou sur un autre appareil), les
                // analyses reviennent sans refaire le travail.
                if (restoreBackup) {
                    restoreFromFolderBackup(context, root, audioFiles, store)
                } else {
                    try {
                        findBackup(root)?.delete()
                    } catch (_: Exception) {
                    }
                }

                val known = store.tracks.value.associateBy { it.uri }
                val total = audioFiles.size
                var done = 0
                _progress.value = Progress(0, total, "")

                val analyzer = AudioAnalyzer()
                for (doc in audioFiles) {
                    if (stopRequested) break
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
                        analyzer.analyze(context, doc.uri, meta.third) { !stopRequested }
                    } catch (_: Exception) {
                        null
                    }
                    // Stop demandé pendant le décodage : ne pas enregistrer ce
                    // morceau comme « analysé en échec », la reprise le refera.
                    if (stopRequested && features == null) break
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
                            musicStartMs = features.musicStartMs,
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
                // Sauvegarde dans le dossier de musique : survit à la
                // désinstallation de l'app et suit le dossier.
                writeFolderBackup(context, root, store)
            } finally {
                _progress.value = null
                scanning = false
            }
        }

    // ------------------------------------------- sauvegarde dans le dossier

    private const val BACKUP_BASENAME = "PulseMix.library"

    private fun findBackup(root: DocumentFile): DocumentFile? =
        root.findFile("$BACKUP_BASENAME.json") ?: root.findFile(BACKUP_BASENAME)

    /**
     * Importe les analyses depuis le fichier de sauvegarde présent dans le
     * dossier de musique. Correspondance par URI exacte, sinon par nom de
     * fichier (le dossier peut avoir bougé ou changer d'appareil).
     */
    private suspend fun restoreFromFolderBackup(
        context: Context,
        root: DocumentFile,
        audioFiles: List<DocumentFile>,
        store: TrackStore
    ) {
        try {
            val doc = findBackup(root) ?: return
            val text = context.contentResolver.openInputStream(doc.uri)
                ?.bufferedReader()?.use { it.readText() } ?: return
            val arr = JSONObject(text).optJSONArray("tracks") ?: return

            val byUri = audioFiles.associateBy { it.uri.toString() }
            val byName = HashMap<String, DocumentFile>()
            for (f in audioFiles) {
                val n = f.name ?: continue
                if (n !in byName) byName[n] = f
            }

            var restored = 0
            for (i in 0 until arr.length()) {
                val t = try {
                    TrackStore.trackFromJson(arr.getJSONObject(i))
                } catch (_: Exception) {
                    continue
                }
                if (!t.analyzed) continue
                val storedName = Uri.parse(t.uri).lastPathSegment
                    ?.substringAfterLast('/')
                val target = byUri[t.uri]
                    ?: storedName?.let { byName[it] }
                    ?: continue
                val targetUri = target.uri.toString()
                val existing = store.get(targetUri)
                if (existing == null || !existing.analyzed) {
                    store.put(t.copy(uri = targetUri))
                    restored++
                }
            }
            if (restored > 0) store.save()
        } catch (_: Exception) {
        }
    }

    /** Écrit (ou remplace) la sauvegarde de la bibliothèque dans le dossier. */
    private fun writeFolderBackup(context: Context, root: DocumentFile, store: TrackStore) {
        try {
            if (store.tracks.value.none { it.analyzed }) return
            val doc = findBackup(root)
                ?: root.createFile("application/json", BACKUP_BASENAME)
                ?: return
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use {
                it.write(store.exportJson().toByteArray())
            }
        } catch (_: Exception) {
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
