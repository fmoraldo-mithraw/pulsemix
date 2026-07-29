package com.pulsemix.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pulsemix.app.data.Track
import com.pulsemix.app.library.LibraryScanner
import com.pulsemix.app.mix.MixEngine
import com.pulsemix.app.player.PlayerCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Graph.store

    val tracks = store.tracks
    val folderUri = store.folderUri
    val scanProgress = LibraryScanner.progress

    val mode = PlayerCore.mode
    val currentTrack = PlayerCore.currentTrack
    val isPlaying = PlayerCore.isPlaying
    val progress = PlayerCore.progress
    val shuffle = PlayerCore.shuffle
    val planName = PlayerCore.planName
    val phaseNames = PlayerCore.phaseNames
    val currentPhase = PlayerCore.currentPhase

    fun onFolderPicked(uri: Uri) {
        store.setFolder(uri.toString())
        rescan()
    }

    fun rescan() {
        val folder = folderUri.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            LibraryScanner.scan(getApplication(), Uri.parse(folder), store)
        }
    }

    /** Arrêt propre de l'analyse en cours (reprise possible plus tard). */
    fun stopScan() = LibraryScanner.requestStop()

    /** Efface les données d'analyse puis relance tout depuis le début. */
    fun rescanFromScratch() {
        val folder = folderUri.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            store.resetAnalysis()
            store.save()
            // restoreBackup = false : sans ça, la sauvegarde du dossier de
            // musique réinjecterait les anciennes analyses.
            LibraryScanner.scan(
                getApplication(), Uri.parse(folder), store,
                restoreBackup = false
            )
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

    fun playDouce(bpmCutoff: Float) {
        PlayerCore.playDouce(tracks.value, bpmCutoff)
    }

    /**
     * Calcul lourd (enchaînements O(n²) sur toute la bibliothèque) : toujours
     * hors du thread UI, sinon l'interface gèle (ANR) quand l'analyse tourne.
     */
    suspend fun proposeMixes(): List<MixEngine.MixPlan> =
        withContext(Dispatchers.Default) { MixEngine.proposeMixes(tracks.value) }

    fun startMix(plan: MixEngine.MixPlan, djMode: Boolean) {
        if (djMode) PlayerCore.startDj(plan) else PlayerCore.startMix(plan)
    }

    fun togglePlayPause() = PlayerCore.togglePlayPause()
    fun next() = PlayerCore.next()
    fun previous() = PlayerCore.previous()
    fun setShuffle(enabled: Boolean) = PlayerCore.setShuffle(enabled)
    fun seekTo(fraction: Float) = PlayerCore.seekToFraction(fraction)

    /** Nombre de morceaux « doux » pour un seuil de BPM donné (aperçu du dialogue).
     *  Simple comptage : pas de tri O(n²) sur le thread UI à chaque cran du curseur. */
    fun softCount(bpmCutoff: Float): Int =
        tracks.value.count { it.analyzed && it.bpm > 0f && it.bpm <= bpmCutoff }
}
