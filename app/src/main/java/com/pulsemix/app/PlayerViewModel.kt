package com.pulsemix.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pulsemix.app.data.Track
import com.pulsemix.app.library.AnalysisService
import com.pulsemix.app.library.LibraryScanner
import com.pulsemix.app.mix.MixEngine
import com.pulsemix.app.player.PlayerCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Graph.store

    val tracks = store.tracks
    val folders = store.folders
    val scanProgress = LibraryScanner.progress

    val mode = PlayerCore.mode
    val currentTrack = PlayerCore.currentTrack
    val nextTrack = PlayerCore.nextTrack
    val launchMessage = PlayerCore.launchMessage
    val isPlaying = PlayerCore.isPlaying
    val progress = PlayerCore.progress
    val shuffle = PlayerCore.shuffle
    val planName = PlayerCore.planName
    val phaseNames = PlayerCore.phaseNames
    val currentPhase = PlayerCore.currentPhase
    val skipIntros = PlayerCore.skipIntros
    val normalizeVolume = PlayerCore.normalizeVolume
    val eqBands = PlayerCore.eqBands
    val sleepRemainingMs = PlayerCore.sleepRemainingMs
    val queue = PlayerCore.queue

    // ------------------------------------------------------- réveil matin
    val alarmEnabled = com.pulsemix.app.player.AlarmClock.enabled
    val alarmHour = com.pulsemix.app.player.AlarmClock.hour
    val alarmMinute = com.pulsemix.app.player.AlarmClock.minute
    val alarmMixId = com.pulsemix.app.player.AlarmClock.mixId
    val alarmRamp = com.pulsemix.app.player.AlarmClock.rampMinutes

    fun setAlarm(enabled: Boolean, hour: Int, minute: Int, mixId: String, ramp: Int) =
        com.pulsemix.app.player.AlarmClock.configure(
            getApplication(), enabled, hour, minute, mixId, ramp
        )

    init {
        // Scan automatique au démarrage : rafraîchit la bibliothèque, restaure
        // les sauvegardes des dossiers si besoin et les maintient à jour.
        viewModelScope.launch {
            store.loaded.first { it }
            if (folders.value.isNotEmpty()) rescan()
        }
    }

    fun onFolderPicked(uri: Uri) {
        store.addFolder(uri.toString())
        viewModelScope.launch(Dispatchers.IO) { store.save() }
        rescan()
    }

    fun removeFolder(uri: String) {
        store.removeFolder(uri)
        viewModelScope.launch(Dispatchers.IO) { store.save() }
        rescan()
    }

    fun rescan() {
        if (folders.value.isEmpty()) return
        // Service en avant-plan : l'analyse continue appli quittée/écran éteint
        AnalysisService.start(getApplication())
    }

    /** Arrêt propre de l'analyse en cours (reprise possible plus tard). */
    fun stopScan() = LibraryScanner.requestStop()

    /** Efface les données d'analyse puis relance tout depuis le début. */
    fun rescanFromScratch() {
        if (folders.value.isEmpty()) return
        AnalysisService.start(getApplication(), fromScratch = true)
    }

    /** Lit un fichier audio ouvert depuis une autre appli (lecteur par défaut). */
    fun playExternal(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val mmr = android.media.MediaMetadataRetriever()
            val track = try {
                mmr.setDataSource(getApplication<Application>(), uri)
                Track(
                    uri = uri.toString(),
                    title = mmr.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_TITLE
                    )?.takeIf { it.isNotBlank() }
                        ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "Titre"),
                    artist = mmr.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST
                    ) ?: "",
                    durationMs = mmr.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                )
            } catch (_: Exception) {
                Track(
                    uri = uri.toString(),
                    title = uri.lastPathSegment?.substringAfterLast('/') ?: "Titre",
                    artist = "",
                    durationMs = 0L
                )
            } finally {
                try {
                    mmr.release()
                } catch (_: Exception) {
                }
            }
            withContext(Dispatchers.Main) {
                PlayerCore.playNormal(listOf(track), 0)
            }
        }
    }

    fun playTrack(track: Track) {
        val list = tracks.value
        val idx = list.indexOfFirst { it.uri == track.uri }
        // Absent de la bibliothèque (fichier externe, morceau supprimé,
        // liste filtrée) : le jouer seul. Retomber sur l'index 0 lançait
        // le premier morceau de la bibliothèque à la place du bon.
        if (idx < 0) PlayerCore.playNormal(listOf(track), 0)
        else PlayerCore.playNormal(list, idx)
    }

    fun playAll() {
        PlayerCore.playNormal(tracks.value, 0)
    }

    fun playDouce(softness: Float) {
        PlayerCore.playDouce(tracks.value, softness)
    }

    /**
     * Calcul lourd (enchaînements O(n²) sur toute la bibliothèque) : toujours
     * hors du thread UI, sinon l'interface gèle (ANR) quand l'analyse tourne.
     */
    suspend fun proposeMixes(
        dj: Boolean,
        targetMinutes: Int?,
        genre: String? = null
    ): List<MixEngine.MixPlan> =
        withContext(Dispatchers.Default) {
            MixEngine.proposeMixes(tracks.value, dj, targetMinutes, genre)
        }

    /** Lance un mix « comme ce morceau » (même style/énergie). */
    fun startSimilar(seed: Track, djMode: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            val plan = MixEngine.similarPlan(tracks.value, seed)
            withContext(Dispatchers.Main) {
                when {
                    // Rien ne se passait en silence : dire pourquoi.
                    plan == null && seed.bpm <= 0f ->
                        PlayerCore.launchMessage.value =
                            "« ${seed.title} » n'est pas encore analysé : " +
                                "lance l'analyse dans Bibliothèque, puis réessaie."
                    plan == null ->
                        PlayerCore.launchMessage.value =
                            "Aucun autre morceau analysé : impossible de " +
                                "construire un mix similaire."
                    djMode -> PlayerCore.startDj(plan)
                    else -> PlayerCore.startMix(plan)
                }
            }
        }
    }

    /** Pré-écoute du meilleur passage. */
    fun preview(track: Track) = PlayerCore.playPreview(track)

    /** Nombre de lectures du morceau (compteur persistant). */
    fun playCount(track: Track): Int =
        com.pulsemix.app.data.PlayHistory.count(track.uri)

    /** Résultat du dernier export de la liste des titres. */
    val exportMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /**
     * Exporte la liste « Artiste - Titre » de toute la bibliothèque en
     * fichier texte vers la destination choisie par l'utilisateur
     * (sélecteur système « Enregistrer sous »).
     */
    fun exportTitleList(dest: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            exportMessage.value = try {
                val lines = tracks.value
                    .map { t ->
                        if (t.artist.isBlank()) t.title
                        else "${t.artist} - ${t.title}"
                    }
                    .sortedBy { it.lowercase() }
                val out = getApplication<android.app.Application>().contentResolver
                    .openOutputStream(dest, "wt")
                if (out == null) {
                    "Export impossible : destination inaccessible."
                } else {
                    out.use { it.write(lines.joinToString("\n").toByteArray()) }
                    "${lines.size} titres exportés."
                }
            } catch (e: Exception) {
                "Export impossible : ${e.message ?: e::class.java.simpleName}"
            }
        }
    }

    fun toggleFavorite(track: Track) = updateTrack(track.uri) {
        it.copy(favorite = !it.favorite)
    }

    fun toggleExcluded(track: Track) = updateTrack(track.uri) {
        it.copy(excluded = !it.excluded)
    }

    /** Corrige le BPM à la main (verrouillé contre la réanalyse). */
    fun setManualBpm(track: Track, bpm: Float) = updateTrack(track.uri) {
        it.copy(bpm = bpm, bpmLocked = true, analyzed = true)
    }

    fun unlockBpm(track: Track) = updateTrack(track.uri) { it.copy(bpmLocked = false) }

    /** Change le type de musique à la main (le fichier audio n'est pas modifié). */
    fun setManualGenre(track: Track, genre: String) {
        val g = MixEngine.normalizeGenre(genre)
        if (g.isBlank()) return
        updateTrack(track.uri) { it.copy(genre = g, genreLocked = true) }
    }

    fun unlockGenre(track: Track) = updateTrack(track.uri) {
        it.copy(genreLocked = false, genre = "")
    }

    private fun updateTrack(uri: String, transform: (Track) -> Track) {
        store.update(uri, transform)
        viewModelScope.launch(Dispatchers.IO) { store.save() }
    }

    fun setNormalizeVolume(enabled: Boolean) = PlayerCore.setNormalizeVolume(enabled)
    fun setEq(bass: Float, mid: Float, treble: Float) = PlayerCore.setEq(bass, mid, treble)
    fun setSleepTimer(minutes: Int?) = PlayerCore.setSleepTimer(minutes)
    fun removeFromQueue(index: Int) = PlayerCore.removeFromQueue(index)
    fun playQueueItem(index: Int) = PlayerCore.playQueueItem(index)
    fun moveQueueItem(from: Int, to: Int) = PlayerCore.moveQueueItem(from, to)

    // ------------------------------------------------------------ v1.3
    val djRecording = PlayerCore.djRecording
    val playlists = com.pulsemix.app.data.PlaylistStore.playlists

    fun toggleDjRecording() = PlayerCore.toggleDjRecording()
    fun markBadTransition() = PlayerCore.markBadTransition()
    val bassLevel = PlayerCore.bassLevel
    val speedLevel = PlayerCore.speedLevel
    fun toggleBassBoost() = PlayerCore.toggleBassBoost()
    fun toggleSpeedBoost() = PlayerCore.toggleSpeedBoost()
    fun setBassLevel(level: Int) = PlayerCore.setBassLevel(level)
    fun setSpeedLevel(level: Int) = PlayerCore.setSpeedLevel(level)

    // Panneau « Effets »
    val trebleLevel = PlayerCore.trebleLevel
    val filterLevel = PlayerCore.filterLevel
    val echoLevel = PlayerCore.echoLevel
    val panLevel = PlayerCore.panLevel
    val gateLevel = PlayerCore.gateLevel
    val liveLoop = PlayerCore.liveLoop
    val liveLoopBeats = PlayerCore.liveLoopBeats
    fun toggleLiveLoopSize() = PlayerCore.toggleLiveLoopSize()
    fun setTrebleLevel(level: Int) = PlayerCore.setTrebleLevel(level)
    fun setFilterLevel(level: Int) = PlayerCore.setFilterLevel(level)
    fun setEchoLevel(level: Int) = PlayerCore.setEchoLevel(level)
    fun setPanLevel(level: Int) = PlayerCore.setPanLevel(level)
    fun setGateLevel(level: Int) = PlayerCore.setGateLevel(level)
    fun setLiveLoop(active: Boolean) = PlayerCore.setLiveLoop(active)
    fun resetEffects() = PlayerCore.resetEffects()
    fun rehearseTransitions(plan: MixEngine.MixPlan) =
        PlayerCore.rehearseTransitions(plan)

    fun genres(): List<Pair<String, Int>> = MixEngine.genresOf(tracks.value)

    fun savePlaylistFromQueue(name: String) =
        com.pulsemix.app.data.PlaylistStore.save(name, queue.value.map { it.uri })

    /** Lit une liste arbitraire (résultat de recherche) comme file de lecture. */
    fun playTracks(list: List<Track>) {
        if (list.isNotEmpty()) PlayerCore.playNormal(list, 0)
    }

    /** Sauvegarde une liste arbitraire comme playlist (interface, sans fichier). */
    fun saveTracksAsPlaylist(name: String, list: List<Track>) =
        com.pulsemix.app.data.PlaylistStore.save(name, list.map { it.uri })

    // -------------------------------------------------- tags en ligne
    val tagPending = com.pulsemix.app.library.TagFixer.pending
    val tagProgress = com.pulsemix.app.library.TagFixer.progress
    val tagError = com.pulsemix.app.library.TagFixer.lastError
    val tagApplied = com.pulsemix.app.library.TagFixer.applied

    fun clearTagApplied() = com.pulsemix.app.library.TagFixer.clearApplied()

    // Option : reporter les corrections dans les fichiers audio eux-mêmes
    val writeTagsToFiles = com.pulsemix.app.library.TagFixer.writeToFiles

    fun setWriteTagsToFiles(enabled: Boolean) =
        com.pulsemix.app.library.TagFixer.setWriteToFiles(enabled)

    /** Résultat de la dernière écriture en masse (message à afficher). */
    val tagWriteMessage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Reporte dans les fichiers toutes les corrections déjà appliquées. */
    fun writeAllTagsToFiles() {
        viewModelScope.launch {
            tagWriteMessage.value = "Écriture des tags en cours…"
            val n = com.pulsemix.app.library.TagFixer.writeAllApplied()
            tagWriteMessage.value =
                if (n > 0) "$n fichiers mis à jour."
                else "Aucun fichier n'a pu être mis à jour."
        }
    }

    /** Nombre de morceaux déjà examinés par la recherche de tags. */
    val tagChecked = com.pulsemix.app.library.TagFixer.checkedCount

    /** Cherche les tags corrects de toute la bibliothèque (MusicBrainz). */
    fun fetchTagsAll() {
        viewModelScope.launch { com.pulsemix.app.library.TagFixer.fixAll(store) }
    }

    /** Reprend la vérification depuis zéro, y compris les morceaux déjà vus. */
    fun recheckAllTags() {
        viewModelScope.launch {
            com.pulsemix.app.library.TagFixer.fixAll(store, force = true)
        }
    }

    fun fetchTagsFor(track: Track) {
        viewModelScope.launch { com.pulsemix.app.library.TagFixer.fixOne(store, track) }
    }

    fun stopTagFetch() = com.pulsemix.app.library.TagFixer.requestStop()

    fun acceptTag(s: com.pulsemix.app.library.TagFixer.Suggestion) {
        com.pulsemix.app.library.TagFixer.accept(store, s)
        viewModelScope.launch(Dispatchers.IO) { store.save() }
    }

    fun rejectTag(s: com.pulsemix.app.library.TagFixer.Suggestion) =
        com.pulsemix.app.library.TagFixer.reject(s)

    // ------------------------------------------- recherche manuelle de tags
    val manualTagResults =
        kotlinx.coroutines.flow.MutableStateFlow<
            List<com.pulsemix.app.library.TagFixer.Candidate>>(emptyList())
    val manualTagSearching = kotlinx.coroutines.flow.MutableStateFlow(false)
    val manualTagError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Pré-remplissage (titre nettoyé, artiste déduit) du formulaire. */
    fun manualTagPrefill(track: Track): Pair<String, String> =
        com.pulsemix.app.library.TagFixer.prefill(track)

    /** Recherche MusicBrainz avec les infos saisies par l'utilisateur. */
    fun manualTagSearch(title: String, artist: String) {
        if (title.isBlank() || manualTagSearching.value) return
        viewModelScope.launch(Dispatchers.IO) {
            manualTagSearching.value = true
            manualTagError.value = null
            manualTagResults.value = emptyList()
            val res = com.pulsemix.app.library.TagFixer.searchCandidates(title, artist)
            manualTagResults.value = res
            if (res.isEmpty()) {
                manualTagError.value =
                    com.pulsemix.app.library.TagFixer.lastError.value
                        ?: "Aucun résultat. Essaie d'autres mots (sans remix, " +
                        "feat., etc.)."
            }
            manualTagSearching.value = false
        }
    }

    fun resetManualTagSearch() {
        manualTagResults.value = emptyList()
        manualTagError.value = null
    }

    /** Identification par empreinte sonore (AcoustID) du morceau. */
    fun manualTagIdentify(track: Track) {
        if (manualTagSearching.value) return
        viewModelScope.launch(Dispatchers.IO) {
            manualTagSearching.value = true
            manualTagError.value = null
            manualTagResults.value = emptyList()
            val res =
                com.pulsemix.app.library.TagFixer.searchCandidatesBySound(track)
            manualTagResults.value = res
            if (res.isEmpty()) {
                manualTagError.value =
                    com.pulsemix.app.library.TagFixer.lastError.value
                        ?: "Aucune correspondance sonore : ce morceau n'est " +
                        "sans doute pas dans la base AcoustID."
            }
            manualTagSearching.value = false
        }
    }

    /** Applique le candidat choisi par l'utilisateur. */
    fun applyManualTag(track: Track, c: com.pulsemix.app.library.TagFixer.Candidate) {
        com.pulsemix.app.library.TagFixer.applyManual(store, track, c)
        viewModelScope.launch(Dispatchers.IO) { store.save() }
    }

    fun saveTagsDirect(track: Track, title: String, artist: String) {
        com.pulsemix.app.library.TagFixer.saveDirect(store, track, title, artist)
        viewModelScope.launch(Dispatchers.IO) { store.save() }
    }

    fun revertTag(s: com.pulsemix.app.library.TagFixer.Suggestion) {
        com.pulsemix.app.library.TagFixer.revert(store, s)
        viewModelScope.launch(Dispatchers.IO) { store.save() }
    }

    // -------------------------------------------------- import depuis URL
    val importState = com.pulsemix.app.library.UrlImporter.state

    /** Importe l'audio d'une URL dans le premier dossier scanné, puis analyse. */
    fun importFromUrl(url: String) {
        val folder = folders.value.firstOrNull() ?: return
        viewModelScope.launch {
            com.pulsemix.app.library.UrlImporter.import(getApplication(), url, folder)
            // Nouveau(x) fichier(s) dans le dossier : relancer le scan
            rescan()
        }
    }

    fun stopImport() = com.pulsemix.app.library.UrlImporter.requestStop()
    fun resetImport() = com.pulsemix.app.library.UrlImporter.reset()

    /** Met à jour le binaire yt-dlp embarqué (extracteurs YouTube & co). */
    fun updateImportEngine() {
        viewModelScope.launch {
            com.pulsemix.app.library.UrlImporter.updateEngine(getApplication())
        }
    }

    // ------------------------------------------------- recherche YouTube
    val ytResults =
        kotlinx.coroutines.flow.MutableStateFlow<
            List<com.pulsemix.app.library.StreamImporter.SearchResult>>(emptyList())
    val ytSearching = kotlinx.coroutines.flow.MutableStateFlow(false)
    val ytSearchError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Recherche sur YouTube (via yt-dlp, sans clé API). */
    fun searchYoutube(query: String) {
        if (query.isBlank() || ytSearching.value) return
        viewModelScope.launch(Dispatchers.IO) {
            ytSearching.value = true
            ytSearchError.value = null
            try {
                ytResults.value =
                    com.pulsemix.app.library.StreamImporter.search(
                        getApplication(), query
                    )
                if (ytResults.value.isEmpty()) {
                    ytSearchError.value = "Aucun résultat."
                }
            } catch (e: Exception) {
                ytResults.value = emptyList()
                ytSearchError.value = "Recherche impossible : " +
                    (e.message ?: e::class.java.simpleName).take(160)
            } finally {
                ytSearching.value = false
            }
        }
    }

    fun cancelYoutubeSearch() =
        com.pulsemix.app.library.StreamImporter.cancelSearch()
    fun hasFolder(): Boolean = folders.value.isNotEmpty()

    fun playPlaylist(p: com.pulsemix.app.data.Playlist) {
        val byUri = tracks.value.associateBy { it.uri }
        val list = p.uris.mapNotNull { byUri[it] }
        if (list.isNotEmpty()) PlayerCore.playNormal(list, 0)
    }

    fun deletePlaylist(name: String) = com.pulsemix.app.data.PlaylistStore.delete(name)

    fun exportPlaylist(p: com.pulsemix.app.data.Playlist) =
        com.pulsemix.app.data.PlaylistStore.exportM3u(
            getApplication(), p, tracks.value.associate { it.uri to it.title }
        )

    /** Définit le meilleur passage à la main (verrouillé contre la réanalyse). */
    fun setManualSegment(track: Track, startMs: Long, durMs: Long) =
        updateTrack(track.uri) {
            it.copy(
                bestStartMs = startMs,
                segmentMs = durMs,
                firstBeatMs = startMs,
                segmentLocked = true
            )
        }

    fun unlockSegment(track: Track) = updateTrack(track.uri) {
        it.copy(segmentLocked = false)
    }

    /** Pré-écoute d'un passage arbitraire (réglage du segment manuel). */
    fun previewSegment(track: Track, startMs: Long, durMs: Long) =
        PlayerCore.playPreview(track.copy(bestStartMs = startMs, segmentMs = durMs))

    /** Décompte avant l'enchaînement automatique en fin de mix (3, 2, 1). */
    val autoNextIn = PlayerCore.autoNextIn

    fun cancelAutoNext() = PlayerCore.cancelAutoNext()

    /**
     * @param targetMinutes et [genre] : les critères qui ont produit ce
     * plan. Mémorisés pour pouvoir en régénérer un semblable quand celui-ci
     * arrive au bout.
     */
    fun startMix(
        plan: MixEngine.MixPlan,
        djMode: Boolean,
        targetMinutes: Int? = null,
        genre: String? = null
    ) {
        if (djMode) PlayerCore.startDj(plan) else PlayerCore.startMix(plan)
        PlayerCore.setMixSpec(
            PlayerCore.MixSpec(plan.id, djMode, targetMinutes, genre)
        )
    }

    fun setSkipIntros(enabled: Boolean) = PlayerCore.setSkipIntros(enabled)

    /** Fondu croisé entre morceaux (hors DJ). */
    val crossfade = PlayerCore.crossfade

    fun setCrossfade(enabled: Boolean) = PlayerCore.setCrossfade(enabled)

    /** Durée du fondu croisé, en secondes. */
    val crossfadeSeconds = PlayerCore.crossfadeSeconds

    fun setCrossfadeSeconds(seconds: Int) = PlayerCore.setCrossfadeSeconds(seconds)

    /** Supprime définitivement un morceau du disque et de la bibliothèque. */
    fun deleteTrack(track: Track) {
        PlayerCore.onTrackDeleted(track.uri)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                android.provider.DocumentsContract.deleteDocument(
                    getApplication<Application>().contentResolver,
                    Uri.parse(track.uri)
                )
            } catch (_: Exception) {
                // permission en écriture absente (ancien choix de dossier) ou
                // fichier déjà disparu : on retire quand même de la
                // bibliothèque ; re-choisir le dossier accordera l'écriture.
            }
            store.remove(track.uri)
            store.save()
        }
    }

    fun togglePlayPause() = PlayerCore.togglePlayPause()
    fun next() = PlayerCore.next()
    fun previous() = PlayerCore.previous()
    fun setShuffle(enabled: Boolean) = PlayerCore.setShuffle(enabled)
    fun seekTo(fraction: Float) = PlayerCore.seekToFraction(fraction)

    /** Déplacement validé (doigt relâché) : rejoint le point en fondu. */
    fun seekToSmooth(fraction: Float) = PlayerCore.seekToFractionSmooth(fraction)

    /** Nombre de morceaux « doux » pour un seuil de douceur donné (aperçu du dialogue). */
    fun softCount(softness: Float): Int =
        MixEngine.softSelection(tracks.value, softness).size
}
