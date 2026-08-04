package com.pulsemix.app.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.pulsemix.app.analysis.AudioAnalyzer
import com.pulsemix.app.data.PlayHistory
import com.pulsemix.app.data.Track
import com.pulsemix.app.data.TrackStore
import com.pulsemix.app.mix.GenreClassifier
import com.pulsemix.app.mix.MixEngine
import com.pulsemix.app.player.PlayerCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

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
     * @param restoreBackup false pour un « tout réanalyser » : les sauvegardes
     * présentes dans les dossiers sont supprimées au lieu d'être restaurées,
     * sinon elles réinjecteraient les anciennes analyses.
     */
    suspend fun scan(
        context: Context,
        treeUris: List<Uri>,
        store: TrackStore,
        restoreBackup: Boolean = true
    ) =
        withContext(Dispatchers.Default) {
            if (scanning) return@withContext
            scanning = true
            stopRequested = false
            try {
                // Affichage immédiat : le parcours des dossiers (SAF) peut
                // prendre plusieurs secondes avant le premier fichier analysé.
                _progress.value = Progress(0, 0, "")
                val roots = treeUris.mapNotNull { DocumentFile.fromTreeUri(context, it) }
                if (roots.isEmpty()) return@withContext
                val files = ArrayList<DocumentFile>()
                for (root in roots) walk(root, files, 0)
                val audioFiles = convertUnplayable(context, files.filter { isAudio(it) })

                val uris = audioFiles.map { it.uri.toString() }.toSet()
                store.retainOnly(uris)

                // Restaurer les sauvegardes stockées dans les dossiers :
                // après une désinstallation (ou sur un autre appareil), les
                // analyses reviennent sans refaire le travail.
                for (root in roots) {
                    if (restoreBackup) {
                        restoreFromFolderBackup(context, root, audioFiles, store)
                    } else {
                        try {
                            findBackup(root)?.delete()
                        } catch (_: Exception) {
                        }
                    }
                }

                // Passe d'indexation : tous les fichiers apparaissent tout de
                // suite dans la bibliothèque (jouables en mode Normal sans
                // attendre l'analyse), l'analyse suit derrière.
                run {
                    val existingUris = store.tracks.value.associateBy { it.uri }
                    var added = 0
                    for (doc in audioFiles) {
                        if (stopRequested) break
                        val uriStr = doc.uri.toString()
                        if (existingUris[uriStr] != null) continue
                        val name = doc.name ?: "?"
                        val meta = readMetadata(context, doc.uri, name)
                        store.put(
                            Track(
                                uri = uriStr,
                                title = meta.first,
                                artist = meta.second,
                                durationMs = meta.third,
                                analyzed = false
                            )
                        )
                        added++
                        if (added % 25 == 0) store.save()
                    }
                    if (added > 0) store.save()
                }

                val known = store.tracks.value.associateBy { it.uri }
                val total = audioFiles.size
                val done = AtomicInteger(0)
                _progress.value = Progress(0, total, "")

                // Analyse en parallèle (2 fichiers à la fois) : premier scan
                // nettement plus court sur les gros dossiers.
                val analyzer = AudioAnalyzer()
                val permits = Semaphore(2)
                coroutineScope {
                    for (doc in audioFiles) {
                        if (stopRequested) break
                        val uriStr = doc.uri.toString()
                        val existing = known[uriStr]
                        if (existing != null && existing.analyzed) {
                            // Rattrapage du genre sans réanalyse : tag du
                            // fichier, sinon déduction par titre/signature
                            // acoustique (« - » = vérifié, rien trouvé)
                            if (!existing.genreLocked &&
                                (existing.genre.isEmpty() || existing.genre == "-")
                            ) {
                                var g = if (existing.genre.isEmpty())
                                    readGenre(context, doc.uri) else "-"
                                if (g == "-") g = GenreClassifier.infer(existing)
                                if (g != existing.genre) store.put(existing.copy(genre = g))
                            }
                            _progress.value =
                                Progress(done.incrementAndGet(), total, existing.title)
                            continue
                        }
                        launch {
                            permits.withPermit {
                                if (stopRequested) return@withPermit
                                // Le moteur DJ décode déjà 2 morceaux en temps
                                // réel : analyser en même temps fait saccader
                                // le son (surtout écran verrouillé, CPU
                                // ralenti). On attend la fin du set.
                                while (!stopRequested &&
                                    PlayerCore.mode.value ==
                                    com.pulsemix.app.player.PlayerMode.DJ &&
                                    PlayerCore.isPlaying.value
                                ) {
                                    kotlinx.coroutines.delay(3_000)
                                }
                                if (stopRequested) return@withPermit
                                // Priorité minimale : ne jamais voler de
                                // cycles à la lecture en cours.
                                try {
                                    android.os.Process.setThreadPriority(
                                        android.os.Process.THREAD_PRIORITY_BACKGROUND
                                    )
                                } catch (_: Exception) {
                                }
                                val name = doc.name ?: "?"
                                _progress.value = Progress(done.get(), total, name)

                                val meta = readMetadata(context, doc.uri, name)
                                // Type choisi à la main : toujours conservé
                                val genre =
                                    if (existing?.genreLocked == true) existing.genre
                                    else readGenre(context, doc.uri)
                                val features = try {
                                    analyzer.analyze(context, doc.uri, meta.third) { !stopRequested }
                                } catch (_: Exception) {
                                    null
                                }
                                // Stop pendant le décodage : ne pas marquer le
                                // morceau en échec, la reprise le refera.
                                if (stopRequested && features == null) return@withPermit
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
                                        analyzed = features.bpm > 0f,
                                        genre = genre,
                                        genreLocked = existing?.genreLocked == true,
                                        favorite = existing?.favorite == true,
                                        excluded = existing?.excluded == true
                                    )
                                } else {
                                    Track(
                                        uri = uriStr,
                                        title = meta.first,
                                        artist = meta.second,
                                        durationMs = meta.third,
                                        analyzed = false,
                                        genre = genre,
                                        genreLocked = existing?.genreLocked == true,
                                        favorite = existing?.favorite == true,
                                        excluded = existing?.excluded == true
                                    )
                                }
                                // Pas de tag genre : déduire du titre ou de la
                                // signature acoustique
                                val finalTrack =
                                    if (track.genre == "-")
                                        track.copy(genre = GenreClassifier.infer(track))
                                    else track
                                store.put(finalTrack)
                                val d = done.incrementAndGet()
                                _progress.value = Progress(d, total, meta.first)
                                if (d % 5 == 0) store.save()
                            }
                        }
                    }
                }
                store.save()
                // Sauvegarde dans chaque dossier de musique : survit à la
                // désinstallation de l'app et suit les dossiers.
                for (root in roots) writeFolderBackup(context, root, store)
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
            val rootObj = JSONObject(text)
            // Installation fraîche : restaurer aussi réglages et historique
            val fresh = store.tracks.value.none { it.analyzed }
            if (fresh) {
                rootObj.optJSONObject("settings")?.let { PlayerCore.applySettings(it) }
                rootObj.optJSONObject("history")?.let { h ->
                    val map = HashMap<String, Long>()
                    for (k in h.keys()) map[k] = h.optLong(k)
                    PlayHistory.import(map)
                }
                rootObj.optJSONObject("playCounts")?.let { h ->
                    val map = HashMap<String, Int>()
                    for (k in h.keys()) map[k] = h.optInt(k)
                    PlayHistory.importCounts(map)
                }
            }
            val arr = rootObj.optJSONArray("tracks") ?: return

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
            // Bibliothèque + réglages + historique anti-répétition
            val payload = JSONObject(store.exportJson())
            payload.put("settings", PlayerCore.exportSettings())
            val hist = JSONObject()
            for ((k, v) in PlayHistory.export()) hist.put(k, v)
            payload.put("history", hist)
            val plays = JSONObject()
            for ((k, v) in PlayHistory.exportCounts()) plays.put(k, v)
            payload.put("playCounts", plays)
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use {
                it.write(payload.toString().toByteArray())
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

    /**
     * Les WMA ne sont lisibles par aucun Android standard (ni le conteneur
     * ASF ni le codec ne sont fournis). Ceux que ce téléphone ne sait pas
     * décoder sont convertis une fois en M4A à côté de l'original, et c'est
     * la conversion qui entre dans la bibliothèque — sinon ils s'y
     * ajouteraient pour rester muets.
     */
    private fun convertUnplayable(
        context: Context,
        files: List<DocumentFile>
    ): List<DocumentFile> {
        val wma = files.filter { WmaConverter.isWma(it.name ?: "") }
        if (wma.isEmpty()) return files
        val byName = files.mapNotNull { it.name }.toHashSet()
        val out = files.toMutableList()
        for (f in wma) {
            if (stopRequested) break
            val name = f.name ?: continue
            // Converti lors d'un passage précédent : ne garder que la copie
            if (WmaConverter.convertedName(name) in byName) {
                out.remove(f)
                continue
            }
            // Certains appareils savent lire le WMA : ne pas convertir pour rien
            if (WmaConverter.playable(context, f.uri)) continue
            _progress.value = Progress(0, 0, "Conversion de $name…")
            val parent = f.parentFile ?: continue
            val converted = WmaConverter.convert(context, f, parent)
            if (converted != null) {
                out.remove(f)
                out.add(converted)
            }
        }
        return out
    }

    private fun isAudio(doc: DocumentFile): Boolean {
        val type = doc.type
        if (type != null && type.startsWith("audio/")) return true
        val name = doc.name ?: return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in audioExtensions
    }

    /** Genre normalisé, ou « - » si le fichier n'en déclare pas. */
    private fun readGenre(context: Context, uri: Uri): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            MixEngine.normalizeGenre(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            ).ifBlank { "-" }
        } catch (_: Exception) {
            "-"
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
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
