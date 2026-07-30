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

    /** Option opt-in : sauter les intros parlées (sketchs) au lancement. */
    val skipIntros = MutableStateFlow(false)

    /** Normalisation du volume entre morceaux (activée par défaut). */
    val normalizeVolume = MutableStateFlow(true)

    /** Égaliseur simple : graves / médiums / aigus en dB (-6..+6). */
    val eqBands = MutableStateFlow(Triple(0f, 0f, 0f))

    /** Minuterie de sommeil : ms restantes, null si inactive. */
    val sleepRemainingMs = MutableStateFlow<Long?>(null)

    /** File en cours (édition type playlist). */
    val queue = MutableStateFlow<List<Track>>(emptyList())

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
        set(value) {
            field = value
            queue.value = value
        }
    private var sleepDeadline = 0L
    private var eqExo: android.media.audiofx.Equalizer? = null
    private var eqDj: android.media.audiofx.Equalizer? = null
    private var lastRecordedUri: String? = null

    // Persistance de l'état de lecture (reprise après fermeture/veille/plantage)
    private lateinit var stateStore: PlaybackStateStore
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastSaveMs = 0L

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        stateStore = PlaybackStateStore(appContext)
        val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        skipIntros.value = prefs.getBoolean("skipIntros", false)
        normalizeVolume.value = prefs.getBoolean("normalizeVolume", true)
        eqBands.value = Triple(
            prefs.getFloat("eqBass", 0f),
            prefs.getFloat("eqMid", 0f),
            prefs.getFloat("eqTreble", 0f)
        )

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
                recordHistory(track.uri)
                // La notification média suit ExoPlayer, qui joue la piste
                // silencieuse en DJ : recopier le morceau réel dans ses
                // métadonnées pour que notification, Bluetooth et voiture
                // affichent le bon titre.
                if (mode.value == PlayerMode.DJ && exo.mediaItemCount > 0) {
                    val cur = exo.getMediaItemAt(0)
                    exo.replaceMediaItem(
                        0,
                        cur.buildUpon()
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(track.title)
                                    .setArtist(track.artist.ifBlank { null })
                                    .build()
                            )
                            .build()
                    )
                }
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
                try {
                    eqDj?.release()
                } catch (_: Exception) {
                }
                eqDj = null
            }

            override fun onSessionReady(sessionId: Int) {
                try {
                    eqDj?.release()
                } catch (_: Exception) {
                }
                eqDj = try {
                    android.media.audiofx.Equalizer(0, sessionId).also { applyEqTo(it) }
                } catch (_: Exception) {
                    null
                }
            }
        })

        // Égaliseur sur la session ExoPlayer
        eqExo = try {
            android.media.audiofx.Equalizer(0, exo.audioSessionId).also { applyEqTo(it) }
        } catch (_: Exception) {
            null
        }

        // Ticker de progression pour les modes ExoPlayer
        handler.post(object : Runnable {
            override fun run() {
                if (mode.value != PlayerMode.DJ) {
                    val d = exo.duration
                    if (d > 0) progress.value =
                        (exo.currentPosition.toFloat() / d).coerceIn(0f, 1f)
                }
                // Minuterie de sommeil : fondu sur les 30 dernières secondes,
                // puis pause (tous modes : la pause est routée vers le DJ).
                if (sleepDeadline > 0) {
                    val rem = sleepDeadline - System.currentTimeMillis()
                    sleepRemainingMs.value = rem.coerceAtLeast(0L)
                    if (rem <= 0) {
                        exo.pause()
                        sleepDeadline = 0L
                        sleepRemainingMs.value = null
                    }
                }
                if (mode.value != PlayerMode.DJ) applyVolume()
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

    fun playDouce(all: List<Track>, softness: Float) {
        val soft = MixEngine.softSelection(all, softness)
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

    // -------------------------------------------------- volume / EQ / sommeil

    /** Gain de normalisation (atténue les morceaux masterisés fort). */
    private fun normGain(t: Track?): Float {
        if (!normalizeVolume.value) return 1f
        val e = t?.energyMean ?: return 1f
        if (e <= 0.01f) return 1f
        return (0.18f / e).coerceIn(0.45f, 1f)
    }

    private fun applyVolume() {
        if (mode.value == PlayerMode.DJ) return // piste silencieuse à 0
        var v = normGain(currentTrack.value)
        val rem = sleepRemainingMs.value
        if (rem != null && rem < 30_000L) v *= (rem / 30_000f).coerceIn(0f, 1f)
        exo.volume = v.coerceIn(0f, 1f)
    }

    fun setNormalizeVolume(enabled: Boolean) {
        normalizeVolume.value = enabled
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("normalizeVolume", enabled).apply()
        if (mode.value != PlayerMode.DJ) applyVolume()
    }

    /** minutes = null pour annuler. */
    fun setSleepTimer(minutes: Int?) {
        if (minutes == null) {
            sleepDeadline = 0L
            sleepRemainingMs.value = null
        } else {
            sleepDeadline = System.currentTimeMillis() + minutes * 60_000L
            sleepRemainingMs.value = minutes * 60_000L
        }
        if (mode.value != PlayerMode.DJ) applyVolume()
    }

    fun setEq(bass: Float, mid: Float, treble: Float) {
        eqBands.value = Triple(bass, mid, treble)
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putFloat("eqBass", bass)
            .putFloat("eqMid", mid)
            .putFloat("eqTreble", treble)
            .apply()
        applyEqTo(eqExo)
        applyEqTo(eqDj)
    }

    /** Répartit graves/médiums/aigus sur les bandes de l'égaliseur système. */
    private fun applyEqTo(eq: android.media.audiofx.Equalizer?) {
        eq ?: return
        try {
            val (bass, mid, treble) = eqBands.value
            eq.enabled = bass != 0f || mid != 0f || treble != 0f
            val range = eq.bandLevelRange
            for (i in 0 until eq.numberOfBands) {
                val centerHz = eq.getCenterFreq(i.toShort()) / 1000
                val db = when {
                    centerHz < 250 -> bass
                    centerHz < 4000 -> mid
                    else -> treble
                }
                val level = (db * 100).toInt()
                    .coerceIn(range[0].toInt(), range[1].toInt())
                eq.setBandLevel(i.toShort(), level.toShort())
            }
        } catch (_: Exception) {
        }
    }

    // ----------------------------------------------------- file & pré-écoute

    /** Pré-écoute du « meilleur passage » d'un morceau. */
    fun playPreview(t: Track) {
        stopDjIfNeeded()
        mode.value = PlayerMode.NORMAL
        clearPlanState()
        queueTracks = listOf(t)
        val end = if (t.durationMs > 0) minOf(t.durationMs, t.bestStartMs + t.segmentMs)
        else t.bestStartMs + t.segmentMs
        val item = MediaItem.Builder()
            .setUri(t.uri)
            .setMediaId(t.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("${t.title} (meilleur passage)")
                    .setArtist(t.artist.ifBlank { null })
                    .build()
            )
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(t.bestStartMs)
                    .setEndPositionMs(end)
                    .build()
            )
            .build()
        exo.setMediaItem(item)
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.prepare()
        exo.play()
        startService()
        updateFromExo()
    }

    /** Retire un élément de la file en cours (édition type playlist). */
    fun removeFromQueue(index: Int) {
        if (mode.value == PlayerMode.DJ) return
        if (index < 0 || index >= queueTracks.size) return
        queueTracks = queueTracks.toMutableList().also { it.removeAt(index) }
        if (index < exo.mediaItemCount) exo.removeMediaItem(index)
        phaseStartIndices = phaseStartIndices.map { if (it > index) it - 1 else it }
        updateFromExo()
        persistState()
    }

    /** Déplace un élément de la file (édition type playlist). */
    fun moveQueueItem(from: Int, to: Int) {
        if (mode.value == PlayerMode.DJ) return
        if (from == to || from !in queueTracks.indices || to !in queueTracks.indices) return
        queueTracks = queueTracks.toMutableList().also {
            val t = it.removeAt(from)
            it.add(to, t)
        }
        if (from < exo.mediaItemCount && to < exo.mediaItemCount) {
            exo.moveMediaItem(from, to)
        }
        updateFromExo()
        persistState()
    }

    /** Saute directement à un élément de la file. */
    fun playQueueItem(index: Int) {
        if (mode.value == PlayerMode.DJ) return
        if (index < 0 || index >= exo.mediaItemCount) return
        exo.seekTo(index, 0)
        exo.play()
    }

    /** Retire un morceau supprimé de la file en cours (hors mode DJ). */
    fun onTrackDeleted(uri: String) {
        if (mode.value == PlayerMode.DJ) return // le moteur sautera le fichier
        val idx = queueTracks.indexOfFirst { it.uri == uri }
        if (idx < 0) return
        queueTracks = queueTracks.toMutableList().also { it.removeAt(idx) }
        if (idx < exo.mediaItemCount) exo.removeMediaItem(idx)
        phaseStartIndices = phaseStartIndices.map { if (it > idx) it - 1 else it }
        updateFromExo()
        persistState()
    }

    fun setSkipIntros(enabled: Boolean) {
        skipIntros.value = enabled
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("skipIntros", enabled).apply()
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
        applyVolume()
        recordHistory(currentTrack.value?.uri)
    }

    private fun recordHistory(uri: String?) {
        if (uri == null || uri == lastRecordedUri) return
        lastRecordedUri = uri
        com.pulsemix.app.data.PlayHistory.record(uri)
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

    private fun mediaItem(t: Track): MediaItem {
        val b = MediaItem.Builder()
            .setUri(t.uri)
            .setMediaId(t.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist.ifBlank { null })
                    .build()
            )
        // Option « sauter les intros parlées » : démarrer au début détecté de
        // la musique (sketchs, préambules parlés)
        if (skipIntros.value && t.musicStartMs > 1_500L) {
            b.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(t.musicStartMs)
                    .build()
            )
        }
        return b.build()
    }

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
