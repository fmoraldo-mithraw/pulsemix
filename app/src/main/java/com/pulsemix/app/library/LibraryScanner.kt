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

    /**
     * Un seul scan à la fois. Atomique : le drapeau était testé puis posé en
     * deux temps, et deux lancements rapprochés (ouverture de l'appli +
     * dossier ajouté) pouvaient passer tous les deux.
     */
    private val scanning = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var stopRequested = false

    /** Arrête proprement l'analyse en cours (l'état est sauvegardé ; la
     *  reprise sautera les morceaux déjà analysés). */
    fun requestStop() {
        stopRequested = true
        synchronized(pending) { pending.clear() }
    }

    /** Morceaux demandés un par un, en attente d'analyse (ordre d'appui). */
    private val pending = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    fun enqueue(uris: Collection<String>) {
        pending.addAll(uris)
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    /** Vide la file d'attente et rend son contenu. */
    fun drainPending(): List<String> = synchronized(pending) {
        val out = pending.toList()
        pending.clear()
        out
    }

    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "mp4"
    )

    /**
     * @param restoreBackup false pour un « tout réanalyser » : les sauvegardes
     * présentes dans les dossiers sont supprimées au lieu d'être restaurées,
     * sinon elles réinjecteraient les anciennes analyses.
     * @param analyze false : indexe les nouveaux fichiers sans les analyser
     * (analyse automatique désactivée dans les réglages).
     */
    suspend fun scan(
        context: Context,
        treeUris: List<Uri>,
        store: TrackStore,
        restoreBackup: Boolean = true,
        analyze: Boolean = true
    ) =
        withContext(Dispatchers.Default) {
            if (!scanning.compareAndSet(false, true)) return@withContext
            stopRequested = false
            try {
                // Affichage immédiat : le parcours des dossiers (SAF) peut
                // prendre plusieurs secondes avant le premier fichier analysé.
                _progress.value = Progress(0, 0, "")
                val roots = treeUris.mapNotNull { DocumentFile.fromTreeUri(context, it) }
                if (roots.isEmpty()) return@withContext
                val files = ArrayList<DocumentFile>()
                var emptyRoot = false
                for (root in roots) {
                    val before = files.size
                    walk(root, files, 0)
                    if (files.size == before) emptyRoot = true
                }
                val audioFiles = withoutOldConversions(files.filter { isAudio(it) })

                val uris = audioFiles.map { it.uri.toString() }.toSet()
                // Un dossier qui ne rend AUCUN fichier ressemble bien plus à
                // une carte SD absente ou une permission perdue (le SAF échoue
                // en silence, listFiles() rend simplement vide) qu'à un
                // dossier réellement vidé : purger la bibliothèque là-dessus
                // détruirait analyses, favoris et corrections — et la purge
                // était aussitôt écrite sur disque. Dans le doute, on garde
                // tout : les morceaux vraiment disparus partiront au prochain
                // scan qui, lui, aura pu lire le dossier.
                if (!emptyRoot) store.retainOnly(uris)

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
                    // Premier scan (bibliothèque vide) : tous les morceaux
                    // entrent dans la même seconde, l'heure courante ne dit
                    // rien de leur ordre — la date du fichier, si.
                    val firstScan = existingUris.isEmpty()
                    val now = System.currentTimeMillis()
                    var added = 0
                    var dated = 0
                    for (doc in audioFiles) {
                        if (stopRequested) break
                        val uriStr = doc.uri.toString()
                        val prev = existingUris[uriStr]
                        if (prev != null) {
                            // Morceau indexé avant l'arrivée de la date (0) :
                            // sans rattrapage, toute la bibliothèque est à
                            // égalité et le tri « Ajouté » retombe sur
                            // l'ordre alphabétique — il semblait sans effet.
                            if (prev.addedAt == 0L) {
                                val d = fileDate(doc, now)
                                if (d > 0L) {
                                    store.update(uriStr) {
                                        if (it.addedAt == 0L) it.copy(addedAt = d) else it
                                    }
                                    dated++
                                }
                            }
                            continue
                        }
                        val name = doc.name ?: "?"
                        val meta = readMetadata(context, doc.uri, name)
                        store.put(
                            Track(
                                uri = uriStr,
                                title = meta.first,
                                artist = meta.second,
                                durationMs = meta.third,
                                analyzed = false,
                                // Date d'entrée : le tri « Ajouté » de la
                                // bibliothèque. Conservée ensuite à chaque
                                // réanalyse (cf. plus bas).
                                addedAt = if (firstScan)
                                    fileDate(doc, now).takeIf { it > 0L } ?: now
                                else now
                            )
                        )
                        added++
                        if (added % 25 == 0) store.saveSoon()
                    }
                    if (added > 0 || dated > 0) store.save()
                }

                val known = store.tracks.value.associateBy { it.uri }
                val total = audioFiles.size
                val done = AtomicInteger(0)
                _progress.value = Progress(0, total, "")

                // Analyse en parallèle (2 fichiers à la fois) : premier scan
                // nettement plus court sur les gros dossiers.
                val analyzer = AudioAnalyzer()
                val permits = Semaphore(2)
                // Analyse auto désactivée : indexation seule, l'analyse se
                // lance à la demande (bouton, ou morceau par morceau).
                if (analyze) coroutineScope {
                    for (doc in audioFiles) {
                        if (stopRequested) break
                        val uriStr = doc.uri.toString()
                        val existing = known[uriStr]
                        // Analysé avec un jeu de mesures dépassé : il manque
                        // des caractéristiques qu'on ne peut pas deviner après
                        // coup (montée, respiration, son tenu, bas-médium).
                        // On le refait — les réglages faits à la main sont
                        // conservés plus bas.
                        val upToDate = existing?.takeIf {
                            it.analyzed &&
                                it.featuresVersion >= AudioAnalyzer.FEATURES_VERSION
                        }
                        if (upToDate != null) {
                            // Rattrapage du genre sans réanalyse : tag du
                            // fichier, sinon déduction par titre/signature
                            // acoustique (« - » = vérifié, rien trouvé)
                            if (!upToDate.genreLocked &&
                                (upToDate.genre.isEmpty() || upToDate.genre == "-")
                            ) {
                                var g = if (upToDate.genre.isEmpty())
                                    readGenre(context, doc.uri) else "-"
                                if (g == "-") g = GenreClassifier.infer(upToDate)
                                if (g != upToDate.genre) store.put(upToDate.copy(genre = g))
                            }
                            _progress.value =
                                Progress(done.incrementAndGet(), total, upToDate.title)
                            continue
                        }
                        launch {
                            permits.withPermit {
                                if (stopRequested) return@withPermit
                                val title = analyzeOne(
                                    context, doc.uri, doc.name ?: "?", existing, store, analyzer
                                ) { name -> _progress.value = Progress(done.get(), total, name) }
                                    ?: return@withPermit
                                val d = done.incrementAndGet()
                                _progress.value = Progress(d, total, title)
                                if (d % 5 == 0) store.saveSoon()
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
                scanning.set(false)
            }
        }

    /**
     * Analyse seulement les morceaux [uris] (déjà indexés), sans parcourir
     * les dossiers : réanalyse forcée même s'ils étaient à jour. Les
     * sauvegardes des dossiers [treeUris] sont réécrites à la fin.
     * @return false si un scan tournait déjà : les morceaux sont remis en
     * file, à redemander après lui.
     */
    suspend fun analyzeTracks(
        context: Context,
        uris: List<String>,
        store: TrackStore,
        treeUris: List<Uri>
    ): Boolean =
        withContext(Dispatchers.Default) {
            if (uris.isEmpty()) return@withContext true
            if (!scanning.compareAndSet(false, true)) {
                // Un scan tourne (indexation hors service) : on repasse après lui
                enqueue(uris)
                return@withContext false
            }
            stopRequested = false
            try {
                val total = uris.size
                var done = 0
                _progress.value = Progress(0, total, "")
                val analyzer = AudioAnalyzer()
                for (u in uris) {
                    if (stopRequested) break
                    val uri = Uri.parse(u)
                    val existing = store.get(u)
                    val name = try {
                        DocumentFile.fromSingleUri(context, uri)?.name
                    } catch (_: Exception) {
                        null
                    } ?: existing?.title ?: "?"
                    val title = analyzeOne(context, uri, name, existing, store, analyzer) {
                        _progress.value = Progress(done, total, it)
                    } ?: break
                    done++
                    _progress.value = Progress(done, total, title)
                }
                store.save()
                for (root in treeUris.mapNotNull { DocumentFile.fromTreeUri(context, it) }) {
                    writeFolderBackup(context, root, store)
                }
                true
            } finally {
                _progress.value = null
                scanning.set(false)
            }
        }

    /**
     * Analyse un seul fichier et range le résultat dans [store], en gardant
     * les réglages faits à la main. Partagé par le scan complet et l'analyse
     * morceau par morceau.
     * @return le titre analysé, ou null si interrompu par un stop.
     */
    private suspend fun analyzeOne(
        context: Context,
        uri: Uri,
        name: String,
        existing: Track?,
        store: TrackStore,
        analyzer: AudioAnalyzer,
        onStart: (String) -> Unit
    ): String? {
        val uriStr = uri.toString()
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
        if (stopRequested) return null
        // Priorité minimale : ne jamais voler de
        // cycles à la lecture en cours.
        try {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_BACKGROUND
            )
        } catch (_: Exception) {
        }
        onStart(name)

        // Un SEUL MediaMetadataRetriever pour titre/
        // artiste/durée ET genre : en ouvrir deux sur
        // le même fichier doublait le coût d'ouverture
        // (extracteur natif + I/O SAF) pour chaque
        // morceau analysé.
        val (meta, tagGenre) =
            readMetaAndGenre(context, uri, name)
        // Type choisi à la main : toujours conservé
        val genre =
            if (existing?.genreLocked == true) existing.genre
            else tagGenre
        val features = try {
            analyzer.analyze(context, uri, meta.third) { !stopRequested }
        } catch (_: Exception) {
            null
        }
        // Stop pendant le décodage : ne pas marquer le
        // morceau en échec, la reprise le refera.
        if (stopRequested && features == null) return null
        // État FRAIS du morceau, pas l'instantané pris
        // avant l'analyse : une correction de tag, un
        // favori ou une exclusion appliqués pendant
        // qu'il décodait étaient silencieusement
        // écrasés par la photo d'avant.
        val cur = store.get(uriStr) ?: existing
        // Réglages faits à la main : ils survivent à
        // l'analyse. Sans ça, un BPM corrigé ou un
        // meilleur passage choisi sur un morceau pas
        // encore analysé était écrasé au scan suivant.
        val lockedBpm = cur?.takeIf { it.bpmLocked }
        val lockedSeg = cur?.takeIf { it.segmentLocked }
        // Titre et artiste : la bibliothèque fait foi
        // pour un morceau déjà connu. Les corrections
        // de tags (MusicBrainz, manuelles) n'existent
        // que là — les relire du fichier les perdait
        // à chaque réanalyse.
        val keptTitle = cur?.title?.takeIf { it.isNotBlank() }
            ?: meta.first
        val keptArtist = if (cur != null) cur.artist else meta.second
        val track = if (features != null) {
            Track(
                uri = uriStr,
                title = keptTitle,
                artist = keptArtist,
                durationMs = if (features.durationMs > 0) features.durationMs else meta.third,
                bpm = lockedBpm?.bpm ?: features.bpm,
                keyName = features.keyName,
                camelot = features.camelot,
                energyMean = features.energyMean,
                energyPeak = features.energyPeak,
                centroid = features.centroid,
                onsetRate = features.onsetRate,
                bestStartMs =
                    lockedSeg?.bestStartMs ?: features.bestStartMs,
                segmentMs =
                    lockedSeg?.segmentMs ?: features.segmentMs,
                firstBeatMs =
                    lockedSeg?.firstBeatMs ?: features.firstBeatMs,
                musicStartMs = features.musicStartMs,
                musicEndMs = features.musicEndMs,
                analyzed = lockedBpm != null || features.bpm > 0f,
                genre = genre,
                genreLocked = cur?.genreLocked == true,
                favorite = cur?.favorite == true,
                excluded = cur?.excluded == true,
                notEpic = cur?.notEpic == true,
                bpmLocked = lockedBpm != null,
                segmentLocked = lockedSeg != null,
                energySlope = features.energySlope,
                dynamicSpread = features.dynamicSpread,
                sustainRatio = features.sustainRatio,
                lowMidRatio = features.lowMidRatio,
                featuresVersion = AudioAnalyzer.FEATURES_VERSION,
                // Gain de normalisation MESURÉ (RMS
                // global) : seuls les morceaux qui
                // passent par l'analyse l'obtiennent
                // — pas de bump de FEATURES_VERSION,
                // les autres gardent l'ancienne
                // formule (gainDb = 0).
                gainDb = AudioAnalyzer.gainDbFor(features.loudness),
                // Segmentation en sections : comme le
                // gain mesuré, seuls les morceaux qui
                // passent par l'analyse l'obtiennent —
                // les anciens restent sans structure.
                structure = features.structure,
                phraseProfile = features.phraseProfile,
                passageRefused = cur?.passageRefused ?: "",
                addedAt = cur?.addedAt ?: 0L
            )
        } else {
            Track(
                uri = uriStr,
                title = keptTitle,
                artist = keptArtist,
                durationMs = meta.third,
                bpm = lockedBpm?.bpm ?: 0f,
                bestStartMs = lockedSeg?.bestStartMs ?: 0L,
                segmentMs = lockedSeg?.segmentMs ?: 60_000L,
                firstBeatMs = lockedSeg?.firstBeatMs ?: 0L,
                analyzed = lockedBpm != null,
                genre = genre,
                genreLocked = cur?.genreLocked == true,
                favorite = cur?.favorite == true,
                excluded = cur?.excluded == true,
                notEpic = cur?.notEpic == true,
                bpmLocked = lockedBpm != null,
                segmentLocked = lockedSeg != null,
                passageRefused = cur?.passageRefused ?: "",
                addedAt = cur?.addedAt ?: 0L
            )
        }
        // Pas de tag genre : déduire du titre ou de la
        // signature acoustique
        val lockedGenre = cur?.takeIf { it.genreLocked }?.genre
        val finalTrack = when {
            lockedGenre != null ->
                track.copy(genre = lockedGenre, genreLocked = true)
            track.genre == "-" ->
                track.copy(genre = GenreClassifier.infer(track))
            else -> track
        }
        store.put(finalTrack)
        // Fait (scan complet compris) : plus besoin de le garder en attente
        pending.remove(uriStr)
        return meta.first
    }

    // ------------------------------------------- sauvegarde dans le dossier

    private const val BACKUP_BASENAME = "PulseMix.library"

    private fun findBackup(root: DocumentFile): DocumentFile? =
        root.findFile("$BACKUP_BASENAME.json") ?: root.findFile(BACKUP_BASENAME)
        // Mort entre l'écartement de l'ancienne et la promotion de la
        // nouvelle : le filet est la seule version complète restante
        ?: root.findFile("$BACKUP_BASENAME.bak")

    /** Date de modification du fichier (SAF), bornée à [now] ; 0 si
     *  inconnue. Approximation de son arrivée dans le dossier. */
    private fun fileDate(doc: DocumentFile, now: Long): Long = try {
        doc.lastModified().takeIf { it > 0L }?.coerceAtMost(now) ?: 0L
    } catch (_: Exception) {
        0L
    }

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
                    // Sauvegarde antérieure à la date d'ajout (0) : garder
                    // celle déjà connue plutôt que l'effacer.
                    val addedAt = if (t.addedAt > 0L) t.addedAt
                    else existing?.addedAt ?: 0L
                    store.put(t.copy(uri = targetUri, addedAt = addedAt))
                    restored++
                }
            }
            if (restored > 0) store.save()
        } catch (_: Exception) {
        }
    }

    /**
     * Écrit (ou remplace) la sauvegarde de la bibliothèque dans le dossier.
     *
     * Atomique, comme le fichier interne : le contenu part dans un fichier
     * temporaire, vérifié complet, qui ne prend le nom définitif qu'ensuite.
     * Écrite d'un bloc, cette sauvegarde — celle qui survit à la
     * désinstallation — pouvait rester tronquée si le processus mourait en
     * pleine écriture, et une restauration silencieusement partielle est
     * pire qu'aucune.
     */
    private fun writeFolderBackup(context: Context, root: DocumentFile, store: TrackStore) {
        try {
            if (store.tracks.value.none { it.analyzed }) return
            // Bibliothèque + réglages + historique anti-répétition
            val payload = JSONObject(store.exportJson())
            payload.put("settings", PlayerCore.exportSettings())
            val hist = JSONObject()
            for ((k, v) in PlayHistory.export()) hist.put(k, v)
            payload.put("history", hist)
            val plays = JSONObject()
            for ((k, v) in PlayHistory.exportCounts()) plays.put(k, v)
            payload.put("playCounts", plays)
            val bytes = payload.toString().toByteArray()

            val tmpName = "$BACKUP_BASENAME.tmp"
            // Reliquats d'écritures interrompues : à nettoyer d'abord. Par
            // PRÉFIXE, pas par nom exact — le provider SAF ajoute
            // l'extension du type MIME au nom demandé (« ….tmp » devient
            // « ….tmp.json »), et findFile(nom exact) ne retrouvait jamais
            // rien : un fichier orphelin s'accumulait à chaque interruption.
            try {
                for (f in root.listFiles()) {
                    if (f.name?.startsWith(tmpName) == true) f.delete()
                }
            } catch (_: Exception) {
            }
            val tmp = root.createFile("application/json", tmpName)
            if (tmp == null) {
                // Dossier sans création possible : écriture directe, comme
                // avant — moins sûre mais mieux que pas de sauvegarde.
                val doc = findBackup(root) ?: return
                context.contentResolver.openOutputStream(doc.uri, "wt")?.use {
                    it.write(bytes)
                }
                return
            }
            context.contentResolver.openOutputStream(tmp.uri, "wt")?.use {
                it.write(bytes)
            } ?: run { tmp.delete(); return }
            // Un disque plein tronque sans lever : vérifier avant de promouvoir
            if (tmp.length() != bytes.size.toLong()) {
                tmp.delete()
                return
            }
            // L'ancienne n'est jamais supprimée avant que la nouvelle soit en
            // place : elle devient un filet, effacé seulement à la fin. À
            // aucun instant le dossier n'est sans version complète.
            val bakName = "$BACKUP_BASENAME.bak"
            root.findFile(bakName)?.delete()
            val previous = findBackup(root)
            if (previous != null && !previous.renameTo(bakName)) {
                // Impossible d'écarter l'ancienne : garder le comportement
                // d'avant (écriture directe) plutôt que deux fichiers en vrac
                tmp.delete()
                context.contentResolver.openOutputStream(previous.uri, "wt")?.use {
                    it.write(bytes)
                }
                return
            }
            if (tmp.renameTo("$BACKUP_BASENAME.json")) {
                root.findFile(bakName)?.delete()
            } else {
                // Promotion refusée : remettre le filet en place
                root.findFile(bakName)?.renameTo("$BACKUP_BASENAME.json")
                tmp.delete()
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
     * Les WMA se lisent désormais tels quels (décodeur natif,
     * [com.pulsemix.app.audio.WmaDecoder]). Les copies « (converti).m4a »
     * laissées par l'ancienne conversion à côté de leur original feraient
     * doublon : elles sont écartées de la bibliothèque (pas supprimées).
     */
    private fun withoutOldConversions(files: List<DocumentFile>): List<DocumentFile> {
        val wmaBases = files.mapNotNull { it.name }
            .filter { com.pulsemix.app.audio.WmaFiles.isWmaName(it) }
            .map { it.substringBeforeLast('.') + " (converti).m4a" }
            .toHashSet()
        if (wmaBases.isEmpty()) return files
        return files.filter { it.name !in wmaBases }
    }

    private fun isAudio(doc: DocumentFile): Boolean {
        val type = doc.type
        if (type != null && type.startsWith("audio/")) return true
        val name = doc.name ?: return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in audioExtensions
    }

    /**
     * Métadonnées ET genre en une seule ouverture de fichier : la passe
     * d'analyse a besoin des deux, et deux MediaMetadataRetriever
     * successifs sur le même URI payaient deux fois l'ouverture.
     * @return ((titre, artiste, duréeMs), genre normalisé ou « - »)
     */
    private fun readMetaAndGenre(
        context: Context,
        uri: Uri,
        fallbackName: String
    ): Pair<Triple<String, String, Long>, String> {
        wmaMeta(context, uri)?.let { m ->
            return Triple(
                m.title?.takeIf { it.isNotBlank() } ?: fallbackName.substringBeforeLast('.'),
                m.artist ?: "",
                m.durationMs
            ) to (MixEngine.normalizeGenre(m.genre).ifBlank { "-" })
        }
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
            val genre = MixEngine.normalizeGenre(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            ).ifBlank { "-" }
            Triple(title, artist, duration) to genre
        } catch (_: Exception) {
            Triple(fallbackName.substringBeforeLast('.'), "", 0L) to "-"
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /** Genre normalisé, ou « - » si le fichier n'en déclare pas.
     *  (Encore utilisé seul par le rattrapage de genre des morceaux déjà
     *  analysés, qui n'a pas besoin du reste des métadonnées.) */
    private fun readGenre(context: Context, uri: Uri): String {
        wmaMeta(context, uri)?.let { return MixEngine.normalizeGenre(it.genre).ifBlank { "-" } }
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
        wmaMeta(context, uri)?.let { m ->
            return Triple(
                m.title?.takeIf { it.isNotBlank() } ?: fallbackName.substringBeforeLast('.'),
                m.artist ?: "",
                m.durationMs
            )
        }
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

    /** Tags d'un WMA par le décodeur natif (MediaMetadataRetriever ne lit
     *  pas l'ASF sur la plupart des téléphones). null : pas un WMA. */
    private fun wmaMeta(context: Context, uri: Uri): com.pulsemix.app.audio.WmaFiles.Meta? =
        if (com.pulsemix.app.audio.WmaFiles.isWmaName(uri.toString()) &&
            com.pulsemix.app.audio.WmaFiles.available
        ) com.pulsemix.app.audio.WmaFiles.meta(context, uri) else null
}
