package com.pulsemix.app.player

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.pulsemix.app.R
import com.pulsemix.app.data.PlaybackState
import com.pulsemix.app.data.PlaybackStateStore
import com.pulsemix.app.data.Track
import com.pulsemix.app.data.TrackStore
import com.pulsemix.app.mix.MixEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class PlayerMode { NORMAL, DOUCE, MIX, DJ }

/**
 * Contrôleur central de la lecture.
 *
 * - NORMAL / DOUCE / MIX : ExoPlayer (playlist), contrôle Bluetooth via la
 *   MediaSession du service.
 * - DJ : moteur [DjMixer] (AudioTrack). ExoPlayer boucle alors sur une piste
 *   silencieuse à volume nul : il conserve le focus audio et maintient la
 *   session active, si bien que play/pause/next/prev Bluetooth continuent de
 *   fonctionner et sont routés vers le moteur DJ.
 *
 * En MIX et DJ, next/previous naviguent entre les PHASES du mix.
 */
object PlayerCore {

    lateinit var exo: ExoPlayer
        private set
    private lateinit var mixer: DjMixer
    private lateinit var appContext: Context
    private var initialized = false
    private val handler = Handler(Looper.getMainLooper())

    val mode = MutableStateFlow(PlayerMode.NORMAL)
    val currentTrack = MutableStateFlow<Track?>(null)
    val isPlaying = MutableStateFlow(false)
    val progress = MutableStateFlow(0f)
    val shuffle = MutableStateFlow(false)
    val planName = MutableStateFlow<String?>(null)
    val phaseNames = MutableStateFlow<List<String>>(emptyList())
    val currentPhase = MutableStateFlow(-1)

    private var plan: MixEngine.MixPlan? = null
    private var phaseStartIndices: List<Int> = emptyList()
    private var queueTracks: List<Track> = emptyList()

    // Persistance de l'état de lecture (reprise après fermeture/veille/plantage)
    private lateinit var stateStore: PlaybackStateStore
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastSaveMs = 0L

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        stateStore = PlaybackStateStore(appContext)

        exo = ExoPlayer.Builder(appContext)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true
        )
        exo.setHandleAudioBecomingNoisy(true)

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying.value = playing
                if (mode.value == PlayerMode.DJ) mixer.setPaused(!playing)
                persistState()
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (mode.value != PlayerMode.DJ) updateFromExo()
                persistState()
            }
        })

        mixer = DjMixer(appContext, object : DjMixer.Listener {
            override fun onTrackChanged(track: Track, phaseIndex: Int) {
                currentTrack.value = track
                currentPhase.value = phaseIndex
                persistState()
            }

            override fun onProgress(p: Float) {
                progress.value = p
            }

            override fun onStopped() {
                if (mode.value == PlayerMode.DJ) {
                    exo.stop()
                    isPlaying.value = false
                }
            }
        })

        // Ticker de progression pour les modes ExoPlayer
        handler.post(object : Runnable {
            override fun run() {
                if (mode.value != PlayerMode.DJ) {
                    val d = exo.duration
                    if (d > 0) progress.value =
                        (exo.currentPosition.toFloat() / d).coerceIn(0f, 1f)
                }
                // Sauvegarde régulière de la position pendant la lecture, pour
                // pouvoir reprendre au même endroit même après un plantage.
                if (isPlaying.value &&
                    System.currentTimeMillis() - lastSaveMs > 5_000
                ) {
                    persistState()
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    // ------------------------------------------------------------ lancements

    fun playNormal(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        stopDjIfNeeded()
        mode.value = PlayerMode.NORMAL
        clearPlanState()
        queueTracks = tracks
        exo.setMediaItems(tracks.map { mediaItem(it) }, startIndex.coerceIn(0, tracks.size - 1), 0)
        exo.shuffleModeEnabled = shuffle.value
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.volume = 1f
        exo.prepare()
        exo.play()
        startService()
        updateFromExo()
        persistState()
    }

    fun playDouce(all: List<Track>, bpmCutoff: Float) {
        val soft = MixEngine.softSelection(all, bpmCutoff)
        if (soft.isEmpty()) return
        stopDjIfNeeded()
        mode.value = PlayerMode.DOUCE
        clearPlanState()
        queueTracks = soft
        exo.setMediaItems(soft.map { mediaItem(it) }, 0, 0)
        exo.shuffleModeEnabled = false
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.volume = 1f
        exo.prepare()
        exo.play()
        startService()
        updateFromExo()
        persistState()
    }

    fun startMix(mixPlan: MixEngine.MixPlan) {
        stopDjIfNeeded()
        mode.value = PlayerMode.MIX
        plan = mixPlan
        planName.value = mixPlan.name
        phaseNames.value = mixPlan.phases.map { it.name }

        val flat = ArrayList<Track>()
        val starts = ArrayList<Int>()
        for (phase in mixPlan.phases) {
            starts.add(flat.size)
            flat.addAll(phase.tracks)
        }
        phaseStartIndices = starts
        queueTracks = flat

        exo.setMediaItems(flat.map { mediaItem(it) }, 0, 0)
        exo.shuffleModeEnabled = false
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.volume = 1f
        exo.prepare()
        exo.play()
        startService()
        updateFromExo()
        persistState()
    }

    fun startDj(mixPlan: MixEngine.MixPlan, fromPhase: Int = 0) {
        if (mixPlan.phases.isEmpty()) return
        mode.value = PlayerMode.DJ
        plan = mixPlan
        planName.value = mixPlan.name + " (DJ)"
        phaseNames.value = mixPlan.phases.map { it.name }
        currentPhase.value = fromPhase.coerceIn(0, mixPlan.phases.size - 1)
        queueTracks = emptyList()

        // Piste silencieuse en boucle : garde le focus audio et la session
        // active pour que les commandes Bluetooth restent routées vers l'app.
        val silence = MediaItem.fromUri(
            "android.resource://${appContext.packageName}/${R.raw.silence}"
        )
        exo.setMediaItem(silence)
        exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.volume = 0f
        exo.prepare()
        exo.play()
        startService()

        mixer.start(mixPlan, currentPhase.value)
        persistState()
    }

    // ------------------------------------------------------------ transport

    fun togglePlayPause() {
        // Mode DJ restauré (ou terminé) : le moteur n'est pas lancé, on
        // redémarre au début de la phase courante.
        if (mode.value == PlayerMode.DJ && !mixer.isRunning) {
            val p = plan ?: return
            startDj(p, currentPhase.value.coerceAtLeast(0))
            return
        }
        if (exo.isPlaying) exo.pause() else {
            if (exo.mediaItemCount == 0) return
            exo.play()
            startService()
        }
    }

    fun next() {
        when (mode.value) {
            PlayerMode.NORMAL, PlayerMode.DOUCE -> exo.seekToNextMediaItem()
            PlayerMode.MIX -> jumpToPhase(currentPhase.value + 1)
            PlayerMode.DJ -> mixer.nextPhase()
        }
    }

    fun previous() {
        when (mode.value) {
            PlayerMode.NORMAL, PlayerMode.DOUCE -> {
                if (exo.currentPosition > 3000) exo.seekTo(0)
                else exo.seekToPreviousMediaItem()
            }
            PlayerMode.MIX -> jumpToPhase(currentPhase.value - 1)
            PlayerMode.DJ -> mixer.prevPhase()
        }
    }

    fun seekToFraction(f: Float) {
        if (mode.value == PlayerMode.DJ) return
        val d = exo.duration
        if (d > 0) exo.seekTo((d * f.coerceIn(0f, 1f)).toLong())
        persistState()
    }

    fun setShuffle(enabled: Boolean) {
        shuffle.value = enabled
        if (mode.value == PlayerMode.NORMAL || mode.value == PlayerMode.DOUCE) {
            exo.shuffleModeEnabled = enabled
        }
        persistState()
    }

    private fun jumpToPhase(target: Int) {
        val p = plan ?: return
        when {
            target < 0 -> {
                val idx = phaseStartIndices.getOrNull(currentPhase.value) ?: 0
                exo.seekTo(idx, 0)
            }
            target >= p.phases.size -> exo.seekToNextMediaItem()
            else -> {
                val idx = phaseStartIndices.getOrNull(target) ?: return
                exo.seekTo(idx, 0)
            }
        }
    }

    // ------------------------------------------------- reprise après arrêt

    /**
     * Photographie l'état courant (sur le thread principal, ExoPlayer oblige)
     * puis l'écrit sur disque en arrière-plan.
     */
    private fun persistState() {
        if (!initialized) return
        lastSaveMs = System.currentTimeMillis()
        val m = mode.value
        val p = plan
        val state: PlaybackState? = when (m) {
            PlayerMode.NORMAL, PlayerMode.DOUCE -> {
                if (queueTracks.isEmpty()) null
                else PlaybackState(
                    mode = m.name,
                    queueUris = queueTracks.map { it.uri },
                    currentIndex = exo.currentMediaItemIndex,
                    positionMs = exo.currentPosition.coerceAtLeast(0L),
                    shuffle = shuffle.value
                )
            }
            PlayerMode.MIX -> p?.let {
                PlaybackState(
                    mode = m.name,
                    planId = it.id,
                    planName = it.name,
                    planDescription = it.description,
                    phaseNames = it.phases.map { ph -> ph.name },
                    phaseUris = it.phases.map { ph -> ph.tracks.map { t -> t.uri } },
                    currentIndex = exo.currentMediaItemIndex,
                    positionMs = exo.currentPosition.coerceAtLeast(0L),
                    currentPhase = currentPhase.value.coerceAtLeast(0),
                    shuffle = shuffle.value
                )
            }
            PlayerMode.DJ -> p?.let {
                PlaybackState(
                    mode = m.name,
                    planId = it.id,
                    planName = it.name,
                    planDescription = it.description,
                    phaseNames = it.phases.map { ph -> ph.name },
                    phaseUris = it.phases.map { ph -> ph.tracks.map { t -> t.uri } },
                    currentPhase = currentPhase.value.coerceAtLeast(0)
                )
            }
        }
        ioScope.launch {
            if (state != null) stateStore.save(state) else stateStore.clear()
        }
    }

    /** Restaure la dernière session dès que library.json est chargé. */
    fun scheduleRestore(store: TrackStore) {
        ioScope.launch {
            store.loaded.first { it }
            val saved = stateStore.load() ?: return@launch
            handler.post { restore(saved, store) }
        }
    }

    private fun restore(saved: PlaybackState, store: TrackStore) {
        if (!initialized) return
        // L'utilisateur a déjà lancé quelque chose : ne rien écraser.
        if (exo.mediaItemCount > 0 || mixer.isRunning) return
        val byUri = store.tracks.value.associateBy { it.uri }
        when (saved.mode) {
            PlayerMode.NORMAL.name, PlayerMode.DOUCE.name -> {
                val list = saved.queueUris.mapNotNull { byUri[it] }
                if (list.isEmpty()) return
                mode.value = PlayerMode.valueOf(saved.mode)
                clearPlanState()
                queueTracks = list
                shuffle.value = saved.shuffle
                val idx = saved.currentIndex.coerceIn(0, list.size - 1)
                exo.setMediaItems(
                    list.map { mediaItem(it) }, idx, saved.positionMs.coerceAtLeast(0L)
                )
                exo.shuffleModeEnabled = saved.shuffle
                exo.repeatMode = Player.REPEAT_MODE_OFF
                exo.volume = 1f
                exo.playWhenReady = false
                exo.prepare()
                updateFromExo()
            }
            PlayerMode.MIX.name -> {
                val restored = rebuildPlan(saved, byUri) ?: return
                mode.value = PlayerMode.MIX
                plan = restored
                planName.value = restored.name
                phaseNames.value = restored.phases.map { it.name }

                val flat = ArrayList<Track>()
                val starts = ArrayList<Int>()
                for (phase in restored.phases) {
                    starts.add(flat.size)
                    flat.addAll(phase.tracks)
                }
                phaseStartIndices = starts
                queueTracks = flat
                if (flat.isEmpty()) return
                val idx = saved.currentIndex.coerceIn(0, flat.size - 1)
                exo.setMediaItems(
                    flat.map { mediaItem(it) }, idx, saved.positionMs.coerceAtLeast(0L)
                )
                exo.shuffleModeEnabled = false
                exo.repeatMode = Player.REPEAT_MODE_OFF
                exo.volume = 1f
                exo.playWhenReady = false
                exo.prepare()
                updateFromExo()
            }
            PlayerMode.DJ.name -> {
                val restored = rebuildPlan(saved, byUri) ?: return
                mode.value = PlayerMode.DJ
                plan = restored
                planName.value = restored.name + " (DJ)"
                phaseNames.value = restored.phases.map { it.name }
                val phase = saved.currentPhase.coerceIn(0, restored.phases.size - 1)
                currentPhase.value = phase
                currentTrack.value = restored.phases[phase].tracks.firstOrNull()
                // Le moteur DJ repartira au début de cette phase au prochain play
                // (voir togglePlayPause) : pas de lecture surprise au démarrage.
            }
        }
    }

    private fun rebuildPlan(
        saved: PlaybackState,
        byUri: Map<String, Track>
    ): MixEngine.MixPlan? {
        val phases = ArrayList<MixEngine.Phase>()
        for (i in saved.phaseNames.indices) {
            val tracks = saved.phaseUris.getOrElse(i) { emptyList() }
                .mapNotNull { byUri[it] }
            if (tracks.isNotEmpty()) phases.add(MixEngine.Phase(saved.phaseNames[i], tracks))
        }
        if (phases.isEmpty()) return null
        return MixEngine.MixPlan(
            saved.planId ?: "restored",
            saved.planName ?: "Mix",
            saved.planDescription ?: "",
            phases
        )
    }

    // -------------------------------------------------------------- interne

    private fun updateFromExo() {
        val idx = exo.currentMediaItemIndex
        currentTrack.value = queueTracks.getOrNull(idx)
        if (mode.value == PlayerMode.MIX) {
            var phase = 0
            for ((i, start) in phaseStartIndices.withIndex()) {
                if (idx >= start) phase = i
            }
            currentPhase.value = phase
        }
    }

    private fun clearPlanState() {
        plan = null
        planName.value = null
        phaseNames.value = emptyList()
        currentPhase.value = -1
    }

    private fun stopDjIfNeeded() {
        if (mixer.isRunning) mixer.stop()
        exo.volume = 1f
        exo.repeatMode = Player.REPEAT_MODE_OFF
    }

    fun releaseAll() {
        if (!initialized) return
        mixer.stop()
        exo.release()
        initialized = false
    }

    private fun mediaItem(t: Track): MediaItem =
        MediaItem.Builder()
            .setUri(t.uri)
            .setMediaId(t.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist.ifBlank { null })
                    .build()
            )
            .build()

    private fun startService() {
        // Surtout pas startForegroundService : c'est une promesse que le
        // service appellera startForeground() sous ~10 s, or MediaSessionService
        // ne le fait que quand la lecture est réellement active. Une pause
        // rapide après le lancement rompait la promesse et Android tuait
        // l'appli 10-30 s plus tard (ForegroundServiceDidNotStartInTimeException,
        // cf. crash_log). Un simple startService suffit : Media3 se met
        // lui-même en avant-plan quand la lecture démarre.
        try {
            appContext.startService(Intent(appContext, PlaybackService::class.java))
        } catch (_: Exception) {
            // App en arrière-plan (rare, les commandes viennent de l'UI) :
            // là, la version foreground est permise et la lecture qui démarre
            // fera poster la notification immédiatement.
            try {
                ContextCompat.startForegroundService(
                    appContext, Intent(appContext, PlaybackService::class.java)
                )
            } catch (_: Exception) {
            }
        }
    }
}
