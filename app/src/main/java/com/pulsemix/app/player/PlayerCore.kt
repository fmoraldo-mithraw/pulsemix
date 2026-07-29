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
import com.pulsemix.app.data.Track
import com.pulsemix.app.mix.MixEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext

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
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (mode.value != PlayerMode.DJ) updateFromExo()
            }
        })

        mixer = DjMixer(appContext, object : DjMixer.Listener {
            override fun onTrackChanged(track: Track, phaseIndex: Int) {
                currentTrack.value = track
                currentPhase.value = phaseIndex
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
    }

    fun startDj(mixPlan: MixEngine.MixPlan) {
        mode.value = PlayerMode.DJ
        plan = mixPlan
        planName.value = mixPlan.name + " (DJ)"
        phaseNames.value = mixPlan.phases.map { it.name }
        currentPhase.value = 0
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

        mixer.start(mixPlan)
    }

    // ------------------------------------------------------------ transport

    fun togglePlayPause() {
        if (exo.isPlaying) exo.pause() else {
            if (exo.mediaItemCount == 0) return
            exo.play()
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
    }

    fun setShuffle(enabled: Boolean) {
        shuffle.value = enabled
        if (mode.value == PlayerMode.NORMAL || mode.value == PlayerMode.DOUCE) {
            exo.shuffleModeEnabled = enabled
        }
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
        try {
            ContextCompat.startForegroundService(
                appContext, Intent(appContext, PlaybackService::class.java)
            )
        } catch (_: Exception) {
        }
    }
}
