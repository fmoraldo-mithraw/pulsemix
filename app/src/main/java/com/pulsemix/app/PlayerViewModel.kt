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
        val idx = list.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0)
        PlayerCore.playNormal(list, idx)
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
            val plan = MixEngine.similarPlan(tracks.value, seed) ?: return@launch
            withContext(Dispatchers.Main) {
                if (djMode) PlayerCore.startDj(plan) else PlayerCore.startMix(plan)
            }
        }
    }

    /** Pré-écoute du meilleur passage. */
    fun preview(track: Track) = PlayerCore.playPreview(track)

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

    fun startMix(plan: MixEngine.MixPlan, djMode: Boolean) {
        if (djMode) PlayerCore.startDj(plan) else PlayerCore.startMix(plan)
    }

    fun setSkipIntros(enabled: Boolean) = PlayerCore.setSkipIntros(enabled)

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

    /** Nombre de morceaux « doux » pour un seuil de douceur donné (aperçu du dialogue). */
    fun softCount(softness: Float): Int =
        MixEngine.softSelection(tracks.value, softness).size
}
