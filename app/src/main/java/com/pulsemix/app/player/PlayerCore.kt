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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

    /**
     * Fondu croisé entre morceaux en lecture classique et en mix : les
     * deux morceaux se chevauchent réellement, plus de blanc. Le mode DJ a
     * son propre moteur de transitions et n'est pas concerné.
     */
    val crossfade = MutableStateFlow(true)

    /** Morceau dont le fondu de sortie a déjà été lancé (une seule fois). */
    private var crossfadedFrom: String? = null

    fun setCrossfade(enabled: Boolean) {
        crossfade.value = enabled
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("crossfade", enabled).apply()
    }

    /** Durée du fondu croisé, en secondes (3 à 15). */
    val crossfadeSeconds = MutableStateFlow(10)

    fun setCrossfadeSeconds(seconds: Int) {
        val s = seconds.coerceIn(3, 15)
        crossfadeSeconds.value = s
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putInt("crossfadeSeconds", s).apply()
    }

    /** Égaliseur simple : graves / médiums / aigus en dB (-6..+6). */
    val eqBands = MutableStateFlow(Triple(0f, 0f, 0f))

    /** Minuterie de sommeil : ms restantes, null si inactive. */
    val sleepRemainingMs = MutableStateFlow<Long?>(null)

    /** File en cours (édition type playlist). */
    val queue = MutableStateFlow<List<Track>>(emptyList())

    /** Enregistrement du set DJ en cours. */
    val djRecording = MutableStateFlow(false)

    /**
     * Boosts progressifs (boutons) : basses et vitesse, par crans -3..+3.
     * Tap = +1 cran (retour à 0 après le max) ; appui long + glisser =
     * réglage fin, y compris en négatif (ralentir, couper les basses).
     * Un cran vitesse = ±8 %, un cran basses = ±5 dB.
     */
    val bassLevel = MutableStateFlow(0)
    val speedLevel = MutableStateFlow(0)
    private var bassBoostExtraDb = 0f
    private var exoSpeed = 1f

    fun toggleBassBoost() {
        bassLevel.value = if (bassLevel.value == 0) 2 else 0
    }

    fun toggleSpeedBoost() {
        speedLevel.value = if (speedLevel.value == 0) 2 else 0
    }

    fun setBassLevel(level: Int) {
        bassLevel.value = level.coerceIn(-3, 3)
    }

    fun setSpeedLevel(level: Int) {
        speedLevel.value = level.coerceIn(-3, 3)
    }

    /**
     * Effets live du panneau « Effets ». Aigus et filtre s'appliquent dans
     * tous les modes (via l'égaliseur système) ; écho, auto-pan, gate et
     * boucle live sont rendus par le moteur DJ uniquement.
     */
    val trebleLevel = MutableStateFlow(0)   // aigus : ±2,5 dB par cran
    val filterLevel = MutableStateFlow(0)   // >0 passe-haut, <0 passe-bas
    val echoLevel = MutableStateFlow(0)     // écho calé tempo (DJ), 0..3
    val panLevel = MutableStateFlow(0)      // auto-pan (DJ), ±3 = vitesse/sens
    val gateLevel = MutableStateFlow(0)     // gate rythmique (DJ), 0..3
    val liveLoop = MutableStateFlow(false)  // boucle live (DJ, maintenu)
    val liveLoopBeats = MutableStateFlow(4) // taille : 4 temps, ou 8 (un tap)
    private var trebleExtraDb = 0f

    /** Tap sur le bouton boucle : bascule la taille 4 ↔ 8 temps. */
    fun toggleLiveLoopSize() {
        liveLoopBeats.value = if (liveLoopBeats.value == 4) 8 else 4
    }

    fun setTrebleLevel(level: Int) {
        trebleLevel.value = level.coerceIn(-3, 3)
    }

    fun setFilterLevel(level: Int) {
        filterLevel.value = level.coerceIn(-3, 3)
        applyEqTo(eqExo, includeFilter = true)
    }

    fun setEchoLevel(level: Int) {
        echoLevel.value = level.coerceIn(0, 3)
    }

    fun setPanLevel(level: Int) {
        panLevel.value = level.coerceIn(-3, 3)
    }

    fun setGateLevel(level: Int) {
        gateLevel.value = level.coerceIn(0, 3)
    }

    fun setLiveLoop(active: Boolean) {
        liveLoop.value = active
    }

    /** Remet tous les effets live à zéro. */
    fun resetEffects() {
        bassLevel.value = 0
        speedLevel.value = 0
        trebleLevel.value = 0
        echoLevel.value = 0
        panLevel.value = 0
        gateLevel.value = 0
        liveLoop.value = false
        setFilterLevel(0)
    }

    val mode = MutableStateFlow(PlayerMode.NORMAL)
    val currentTrack = MutableStateFlow<Track?>(null)

    /** Message d'échec de lancement (plan vide...) affiché par le lecteur. */
    val launchMessage = MutableStateFlow<String?>(null)

    /** Morceau qui suivra (file ou plan DJ) — pour la vue waveform. */
    val nextTrack = MutableStateFlow<Track?>(null)
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
            notifyWidgets()
        }
    private var sleepDeadline = 0L
    private var eqExo: android.media.audiofx.Equalizer? = null
    private var eqDj: android.media.audiofx.Equalizer? = null
    private var lastRecordedUri: String? = null
    private var prevDjUri: String? = null

    // Persistance de l'état de lecture (reprise après fermeture/veille/plantage)
    private lateinit var stateStore: PlaybackStateStore
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastSaveMs = 0L
    private var lastWidgetTickMs = 0L

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        stateStore = PlaybackStateStore(appContext)
        val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        skipIntros.value = prefs.getBoolean("skipIntros", false)
        normalizeVolume.value = prefs.getBoolean("normalizeVolume", true)
        crossfade.value = prefs.getBoolean("crossfade", true)
        crossfadeSeconds.value = prefs.getInt("crossfadeSeconds", 10).coerceIn(3, 15)
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
                // La queue du morceau précédent ne doit pas jouer seule —
                // mais SEULEMENT si la lecture est vraiment arrêtée. Un
                // saut met le lecteur en tampon, donc `playing` retombe à
                // faux alors que l'intention de jouer demeure : couper là
                // tuait le fondu à l'instant même où il commençait.
                if (!playing && !exo.playWhenReady) stopTail()
                if (mode.value == PlayerMode.DJ) mixer.setPaused(!playing)
                persistState()
                notifyWidgets()
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (mode.value != PlayerMode.DJ) updateFromExo()
                persistState()
            }

            override fun onPlaybackStateChanged(state: Int) {
                // Fin d'un mix classique : la file est allée à son terme
                if (state == Player.STATE_ENDED &&
                    mode.value == PlayerMode.MIX
                ) startAutoNext()
            }
        })

        mixer = DjMixer(appContext, object : DjMixer.Listener {
            override fun onTrackChanged(track: Track, phaseIndex: Int) {
                prevDjUri = currentTrack.value?.uri
                currentTrack.value = track
                currentPhase.value = phaseIndex
                nextTrack.value = djNextAfter(track.uri)
                recordHistory(track.uri)
                notifyWidgets()
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

            override fun onStopped(natural: Boolean) {
                if (mode.value == PlayerMode.DJ) {
                    exo.stop()
                    isPlaying.value = false
                }
                djRecording.value = false
                try {
                    eqDj?.release()
                } catch (_: Exception) {
                }
                eqDj = null
                if (natural) startAutoNext()
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
            android.media.audiofx.Equalizer(0, exo.audioSessionId)
                .also { applyEqTo(it, includeFilter = true) }
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
                    // Fondu croisé vers le morceau suivant : déclenché assez
                    // tôt pour que les deux se chevauchent vraiment.
                    if (crossfade.value && d > 0 && isPlaying.value &&
                        exo.hasNextMediaItem() && exoTail == null &&
                        currentTrack.value?.uri != crossfadedFrom &&
                        d - exo.currentPosition in 1..(CROSSFADE_MS + CROSSFADE_LEAD_MS)
                    ) {
                        crossfadedFrom = currentTrack.value?.uri
                        crossfadeToNext()
                    }
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
                if (mode.value != PlayerMode.DJ) {
                    applyVolume()
                    // Boosts progressifs côté ExoPlayer (le moteur DJ les
                    // gère en interne, calé sur sa grille de beats)
                    // Rampes rapides : un cran (5 dB / 8 %) s'applique en ~1 s
                    val targetDb = 5f * bassLevel.value
                    if (bassBoostExtraDb != targetDb) {
                        bassBoostExtraDb = if (targetDb > bassBoostExtraDb)
                            (bassBoostExtraDb + 2.5f).coerceAtMost(targetDb)
                        else (bassBoostExtraDb - 2.5f).coerceAtLeast(targetDb)
                        applyEqTo(eqExo, includeFilter = true)
                    }
                    val targetSpeed = 1f + 0.08f * speedLevel.value
                    if (exoSpeed != targetSpeed) {
                        exoSpeed = if (targetSpeed > exoSpeed)
                            (exoSpeed + 0.04f).coerceAtMost(targetSpeed)
                        else (exoSpeed - 0.04f).coerceAtLeast(targetSpeed)
                        exo.setPlaybackSpeed(exoSpeed)
                    }
                }
                // Rampe des aigus : s'applique aux deux sessions EQ (exo + DJ)
                val trebleTarget = 5f * trebleLevel.value
                if (trebleExtraDb != trebleTarget) {
                    trebleExtraDb = if (trebleTarget > trebleExtraDb)
                        (trebleExtraDb + 2.5f).coerceAtMost(trebleTarget)
                    else (trebleExtraDb - 2.5f).coerceAtLeast(trebleTarget)
                    applyEqTo(eqExo, includeFilter = true)
                    applyEqTo(eqDj)
                }
                // Sauvegarde régulière de la position pendant la lecture, pour
                // pouvoir reprendre au même endroit même après un plantage.
                if (isPlaying.value &&
                    System.currentTimeMillis() - lastSaveMs > 5_000
                ) {
                    persistState()
                }
                // Barre de progression des widgets : toutes les 3 s, assez
                // pour qu'elle avance visiblement sans marteler le système
                if (isPlaying.value &&
                    System.currentTimeMillis() - lastWidgetTickMs > 3_000
                ) {
                    lastWidgetTickMs = System.currentTimeMillis()
                    notifyWidgets()
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    // ------------------------------------------------------------ lancements

    fun playNormal(tracks: List<Track>, startIndex: Int = 0) {
        cancelAutoNext()
        stopTail()
        if (tracks.isEmpty()) return
        launchMessage.value = null
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
        cancelAutoNext()
        stopTail()
        val soft = MixEngine.softSelection(all, softness)
        if (soft.isEmpty()) {
            launchMessage.value = "Aucun morceau assez doux pour ce réglage."
            return
        }
        launchMessage.value = null
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
        cancelAutoNext()
        stopTail()
        if (mixPlan.phases.sumOf { it.tracks.size } == 0) {
            launchMessage.value = "Ce plan ne contient aucun morceau : rien à lancer."
            return
        }
        launchMessage.value = null
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

    fun startDj(mixPlan: MixEngine.MixPlan, fromPhase: Int = 0, rehearsal: Boolean = false) {
        cancelAutoNext()
        stopTail()
        if (mixPlan.phases.isEmpty()) return
        // Le moteur DJ ne joue que les morceaux analysés (BPM connu) : un
        // plan sans aucun morceau jouable s'arrêtait en silence, boutons
        // muets. On prévient au lieu de basculer dans un mode mort.
        val playable = mixPlan.phases.sumOf { ph ->
            ph.tracks.count { it.analyzed && it.bpm > 0f }
        }
        if (playable == 0) {
            launchMessage.value =
                "Ce plan DJ ne contient aucun morceau analysé (BPM requis) : " +
                    "lance l'analyse dans Bibliothèque."
            return
        }
        launchMessage.value = null
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

        mixer.start(mixPlan, currentPhase.value, rehearsal)
        persistState()
    }

    /** Répétition des transitions d'un plan : seules les jonctions sont jouées. */
    fun rehearseTransitions(mixPlan: MixEngine.MixPlan) =
        startDj(mixPlan, 0, rehearsal = true)

    /** Démarre/arrête l'enregistrement du set DJ (fichier M4A). */
    fun toggleDjRecording() {
        if (mode.value != PlayerMode.DJ || !mixer.isRunning) return
        if (djRecording.value) {
            mixer.setRecorder(null)
            djRecording.value = false
        } else {
            try {
                val dir = appContext.getExternalFilesDir("Mixes")
                    ?: appContext.filesDir
                val f = java.io.File(
                    dir, "PulseMix-set-${System.currentTimeMillis() / 1000}.m4a"
                )
                mixer.setRecorder(MixRecorder(f))
                djRecording.value = true
            } catch (_: Exception) {
            }
        }
    }

    /** Marque la transition qui vient de se produire comme ratée. */
    fun markBadTransition() {
        val from = prevDjUri ?: return
        val to = currentTrack.value?.uri ?: return
        if (from != to) com.pulsemix.app.data.TransitionFeedback.record(from, to)
    }

    /** Réglages exportés dans la sauvegarde du dossier de musique. */
    fun exportSettings(): org.json.JSONObject {
        val o = org.json.JSONObject()
        o.put("skipIntros", skipIntros.value)
        o.put("normalizeVolume", normalizeVolume.value)
        o.put("eqBass", eqBands.value.first.toDouble())
        o.put("eqMid", eqBands.value.second.toDouble())
        o.put("eqTreble", eqBands.value.third.toDouble())
        o.put("crossfade", crossfade.value)
        o.put("crossfadeSeconds", crossfadeSeconds.value)
        return o
    }

    /** Restaure les réglages depuis une sauvegarde (installation fraîche). */
    fun applySettings(o: org.json.JSONObject) {
        handler.post {
            setSkipIntros(o.optBoolean("skipIntros", skipIntros.value))
            setNormalizeVolume(o.optBoolean("normalizeVolume", normalizeVolume.value))
            setEq(
                o.optDouble("eqBass", 0.0).toFloat(),
                o.optDouble("eqMid", 0.0).toFloat(),
                o.optDouble("eqTreble", 0.0).toFloat()
            )
            setCrossfade(o.optBoolean("crossfade", crossfade.value))
            setCrossfadeSeconds(
                o.optInt("crossfadeSeconds", crossfadeSeconds.value)
            )
        }
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
            if (exo.mediaItemCount == 0) {
                // File perdue mais connue : la reconstruire au lieu d'un
                // bouton play muet.
                if (queueTracks.isEmpty()) return
                exo.setMediaItems(queueTracks.map { mediaItem(it) }, 0, 0)
            }
            // Après une erreur de lecture, ExoPlayer reste inerte tant qu'on
            // ne re-prépare pas : play/next semblaient morts.
            if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
            exo.play()
            startService()
        }
    }

    /**
     * Exécute [go] — un changement de morceau demandé à la main — en fondu
     * croisé, comme l'enchaînement naturel de fin de morceau. Saut direct si
     * le réglage est coupé, s'il n'y a rien à prolonger, ou si [possible] est
     * faux (bout de file : le fondu jouerait contre lui-même).
     */
    private fun crossfadeTo(possible: Boolean, go: () -> Unit) {
        val uri = currentTrack.value?.uri
        if (!possible || !crossfade.value || uri == null || !isPlaying.value) {
            flushPendingSwitch()
            stopTail()
            go()
            return
        }
        // Pas besoin de marquer le morceau comme déjà fondu : la queue reste
        // en place pendant toute la bascule, et le déclencheur de fin exige
        // justement qu'il n'y en ait aucune.
        handOffTail(uri, exo.currentPosition, CROSSFADE_MS, go)
    }

    fun next() {
        if (mode.value == PlayerMode.DJ) {
            stopTail()
            mixer.nextPhase()
            return
        }
        crossfadeTo(exo.hasNextMediaItem()) {
            if (mode.value == PlayerMode.MIX) jumpToPhase(currentPhase.value + 1)
            else exo.seekToNextMediaItem()
        }
    }

    fun previous() {
        // On peut revenir sur un morceau dont la fin a déjà été fondue : sa
        // fin doit pouvoir l'être à nouveau.
        crossfadedFrom = null
        if (mode.value == PlayerMode.DJ) {
            stopTail()
            mixer.prevPhase()
            return
        }
        crossfadeTo(exo.currentPosition > 3000 || exo.hasPreviousMediaItem()) {
            when (mode.value) {
                PlayerMode.MIX -> jumpToPhase(currentPhase.value - 1)
                else -> {
                    if (exo.currentPosition > 3000) exo.seekTo(0)
                    else exo.seekToPreviousMediaItem()
                }
            }
        }
    }

    fun seekToFraction(f: Float) {
        if (mode.value == PlayerMode.DJ) return
        // Repositionnement explicite : le fondu de fin redevient possible
        crossfadedFrom = null
        val d = exo.duration
        if (d > 0) exo.seekTo((d * f.coerceIn(0f, 1f)).toLong())
        persistState()
    }

    private var seekJob: Job? = null

    // ------------------------------------------------------- vrais fondus

    /**
     * Fondus croisés hors DJ (lecture classique et mix).
     *
     * Un lecteur ne peut pas se superposer à lui-même : le son en cours est
     * donc confié à un SECOND lecteur, qui le prolonge en s'effaçant,
     * pendant que le lecteur principal part ailleurs — au morceau suivant,
     * ou à l'endroit visé sur la barre. Deux sources sonnent réellement
     * ensemble, comme entre deux platines.
     *
     * Le second lecteur ne prend pas le focus audio et n'a ni file ni
     * session : il ne fait que tenir la queue du son sortant.
     */
    private var exoTail: ExoPlayer? = null
    private var tailJob: Job? = null

    /**
     * Changement de morceau demandé mais pas encore appliqué : il n'a lieu
     * qu'une fois la queue prête à prolonger le son.
     */
    private var pendingSwitch: (() -> Unit)? = null

    /** Applique tout de suite une bascule restée en attente. */
    private fun flushPendingSwitch() {
        val go = pendingSwitch ?: return
        pendingSwitch = null
        go()
    }

    /** Gain du fondu d'entrée du lecteur principal (multiplie le volume). */
    @Volatile private var fadeGain = 1f

    /** Durée d'un fondu croisé entre deux morceaux (réglage utilisateur). */
    private val CROSSFADE_MS: Long get() = crossfadeSeconds.value * 1_000L

    /** Durée du fondu croisé lors d'un déplacement sur la barre. */
    private val SEEK_CROSSFADE_MS: Long get() = crossfadeSeconds.value * 1_000L

    /**
     * Marge de déclenchement : ouvrir le fichier et remplir son tampon
     * prend un instant, et le fondu ne commence qu'après. Sans cette
     * avance, il déborderait de la fin du morceau et serait tronqué.
     */
    private const val CROSSFADE_LEAD_MS = 2_000L

    /** Pas des rampes de volume : 25 ms, inaudible et peu coûteux. */
    private const val FADE_STEP_MS = 25L

    /**
     * Confie [uri] à partir de [fromMs] au second lecteur, qui le prolonge
     * puis s'efface sur [fadeMs]. Le lecteur principal est libre de partir
     * ailleurs immédiatement.
     */
    private fun handOffTail(uri: String, fromMs: Long, fadeMs: Long, onSwitch: () -> Unit) {
        // Une bascule encore en attente appartient à un geste précédent : la
        // congédier sans l'appliquer perdrait cet appui. Deux « suivant »
        // rapprochés doivent bien avancer de deux morceaux.
        flushPendingSwitch()
        stopTail()
        val player = try {
            ExoPlayer.Builder(appContext).build().apply {
                // Surtout pas de focus audio : il est déjà tenu par le
                // lecteur principal, le redemander couperait le son.
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ false
                )
                setMediaItem(MediaItem.fromUri(uri))
                seekTo(fromMs)
                // Muet et à l'arrêt tant qu'il n'a pas de quoi jouer :
                // ouvrir le fichier et remplir son tampon prend un instant,
                // et c'est précisément ce délai qui coupait le son.
                volume = 0f
                playWhenReady = false
                prepare()
            }
        } catch (_: Exception) {
            onSwitch()
            return
        }
        exoTail = player
        pendingSwitch = onSwitch
        var switched = false

        /** Bascule : les deux sources jouent, puis se croisent. */
        fun switchNow(withTail: Boolean) {
            if (switched) return
            switched = true
            // Un geste plus récent a repris la main : cette bascule ne nous
            // appartient plus. Ni le son ni la queue, désormais à lui, ne
            // doivent être touchés — le garde-fou des 2,5 s passe ici quand
            // la demande a déjà été remplacée.
            if (pendingSwitch !== onSwitch) return
            val v0 = exo.volume
            if (!withTail) {
                releaseTail()
                pendingSwitch = null
                fadeGain = 0f
                applyVolume()
                onSwitch()
                fadeInMain(fadeMs)
                return
            }
            autoScope.launch(Dispatchers.Main) {
                var eff = fadeMs
                try {
                    player.volume = 0f
                    // Recaler la queue sur le direct. Elle a été préparée à la
                    // position qu'occupait le lecteur principal au moment de la
                    // demande, mais l'ouverture du fichier prend un instant et
                    // pendant ce temps le principal a continué d'avancer. La
                    // laisser là où elle était la ferait rembobiner d'une à
                    // deux secondes : on réentendrait le passage qu'on vient
                    // de quitter, remonté à plein volume, avant même que le
                    // fondu commence. Le fichier est déjà ouvert et son tampon
                    // couvre largement ce petit saut en avant : le recalage ne
                    // coûte pratiquement rien.
                    val live = exo.currentPosition
                    if (live - player.currentPosition > 120L) player.seekTo(live)
                    // « Prêt » ne veut pas dire « audible » : entre play() et
                    // la première goutte de son, la sortie audio met quelques
                    // dizaines de millisecondes à s'amorcer. Couper le lecteur
                    // principal tout de suite ouvrait un petit trou. On lance
                    // donc la queue EN MUET et on attend qu'elle avance
                    // vraiment — preuve qu'elle sort du son — avant de
                    // basculer. Pendant ce temps le morceau en cours continue
                    // normalement, et comme la queue est muette on n'entend
                    // pas les deux en même temps.
                    player.play()
                    val start = player.currentPosition
                    var waited = 0L
                    while (waited < 800L && player.currentPosition <= start) {
                        delay(20L)
                        waited += 20L
                        // Un geste de l'utilisateur a pu congédier la queue
                        // entre-temps : ne pas toucher un lecteur libéré.
                        if (exoTail !== player) return@launch
                    }
                    player.volume = v0
                    // Le fondu ne doit pas déborder de la fin du fichier :
                    // le son s'arrêterait net avant d'avoir fini de sortir.
                    val remain = player.duration - player.currentPosition
                    if (remain > 0) {
                        eff = fadeMs.coerceAtMost((remain - 250L).coerceAtLeast(600L))
                    }
                } catch (_: Exception) {
                    releaseTail()
                }
                // Un geste plus récent a repris la main pendant l'attente : il
                // a déjà appliqué cette bascule, ne pas la rejouer.
                if (pendingSwitch !== onSwitch) return@launch
                pendingSwitch = null
                // Le volume tombe AVANT le saut : sinon le morceau d'arrivée se
                // ferait entendre à plein volume le temps d'un souffle.
                fadeGain = 0f
                applyVolume()
                onSwitch()
                fadeInMain(eff)
                if (exoTail != null) fadeOutTail(player, v0, eff)
            }
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) switchNow(withTail = true)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Pas de fondu possible : basculer quand même, sèchement
                switchNow(withTail = false)
            }
        })

        // Garde-fou : un fichier qui met trop longtemps à s'ouvrir ne doit
        // pas retarder indéfiniment le geste de l'utilisateur.
        autoScope.launch(Dispatchers.Main) {
            delay(2_500)
            switchNow(withTail = false)
        }
    }

    /** Éteint progressivement la source sortante (equal-power). */
    private fun fadeOutTail(player: ExoPlayer, v0: Float, fadeMs: Long) {
        tailJob?.cancel()
        tailJob = autoScope.launch(Dispatchers.Main) {
            val steps = (fadeMs / FADE_STEP_MS).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                // La somme des deux sources garde un niveau constant, là où
                // un fondu linéaire creuse au milieu
                val x = i.toFloat() / steps
                player.volume = v0 * kotlin.math.cos(x * (Math.PI / 2).toFloat())
                delay(FADE_STEP_MS)
            }
            releaseTail()
        }
    }

    private fun releaseTail() {
        val p = exoTail ?: return
        exoTail = null
        try {
            p.stop()
            p.release()
        } catch (_: Exception) {
        }
    }

    /** Monte le lecteur principal depuis le silence, en equal-power. */
    private fun fadeInMain(fadeMs: Long) {
        seekJob?.cancel()
        seekJob = autoScope.launch(Dispatchers.Main) {
            val steps = (fadeMs / FADE_STEP_MS).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                val x = i.toFloat() / steps
                fadeGain = kotlin.math.sin(x * (Math.PI / 2).toFloat())
                applyVolume()
                delay(FADE_STEP_MS)
            }
            fadeGain = 1f
            applyVolume()
        }
    }

    /**
     * Déplacement demandé à la barre de progression, une fois le doigt
     * relâché. On n'y saute pas sèchement : le son descend, se replace, et
     * remonte. En DJ, c'est le moteur qui s'en charge — il rouvre le
     * morceau à l'endroit visé et y fait une vraie transition.
     */
    fun seekToFractionSmooth(f: Float) {
        val frac = f.coerceIn(0f, 1f)
        if (mode.value == PlayerMode.DJ) {
            mixer.requestSeek(frac)
            return
        }
        // Repositionnement explicite : le fondu de fin redevient possible
        crossfadedFrom = null
        val d = exo.duration
        if (d <= 0) return
        val target = (d * frac).toLong()
        // Vrai fondu croisé : le passage qu'on quitte continue sur le second
        // lecteur pendant que le principal se replace et remonte. Le
        // déplacement lui-même n'a lieu qu'une fois ce second lecteur prêt à
        // prendre le relais, sinon il y aurait un blanc.
        val uri = currentTrack.value?.uri
        if (uri == null || !crossfade.value) {
            exo.seekTo(target)
            persistState()
            return
        }
        handOffTail(uri, exo.currentPosition, SEEK_CROSSFADE_MS) {
            exo.seekTo(target)
            persistState()
        }
    }

    /**
     * Fondu croisé vers le morceau suivant : la fin du morceau en cours est
     * confiée au second lecteur, et le principal démarre le suivant dès que
     * ce relais est en place. Les deux se croisent réellement.
     */
    private fun crossfadeToNext() {
        val uri = currentTrack.value?.uri ?: return
        handOffTail(uri, exo.currentPosition, CROSSFADE_MS) {
            exo.seekToNextMediaItem()
        }
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
        // Fondu d'entrée en cours : il s'applique par-dessus tout le reste
        v *= fadeGain
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
        applyEqTo(eqExo, includeFilter = true)
        applyEqTo(eqDj)
    }

    /**
     * Répartit graves/médiums/aigus sur les bandes de l'égaliseur système.
     * includeFilter : approxime le filtre DJ (passe-haut/passe-bas par crans)
     * en creusant les bandes — utilisé pour la session ExoPlayer seulement,
     * le moteur DJ ayant son vrai filtre balayé.
     */
    private fun applyEqTo(
        eq: android.media.audiofx.Equalizer?,
        includeFilter: Boolean = false
    ) {
        eq ?: return
        try {
            val (bass, mid, treble) = eqBands.value
            val fl = if (includeFilter) filterLevel.value else 0
            var bassAdj = 0f
            var midAdj = 0f
            var trebAdj = 0f
            if (fl > 0) { // passe-haut : couper les graves, puis les médiums
                bassAdj = -10f * fl
                midAdj = -6f * (fl - 1).coerceAtLeast(0)
            } else if (fl < 0) { // passe-bas : couper les aigus, puis les médiums
                trebAdj = -10f * -fl
                midAdj = -6f * (-fl - 1).coerceAtLeast(0)
            }
            eq.enabled = bass != 0f || mid != 0f || treble != 0f ||
                bassBoostExtraDb != 0f || trebleExtraDb != 0f || fl != 0
            val range = eq.bandLevelRange
            for (i in 0 until eq.numberOfBands) {
                val centerHz = eq.getCenterFreq(i.toShort()) / 1000
                val db = when {
                    centerHz < 250 -> bass + bassBoostExtraDb + bassAdj
                    centerHz < 4000 -> mid + midAdj
                    else -> treble + trebleExtraDb + trebAdj
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
        cancelAutoNext()
        stopTail()
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
                nextTrack.value = currentTrack.value?.let { djNextAfter(it.uri) }
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

    /** Morceau qui suivra `uri` dans le plan DJ (mêmes filtres que le moteur). */
    private fun djNextAfter(uri: String): Track? {
        val flat = plan?.phases?.flatMap { ph ->
            ph.tracks.filter { it.analyzed && it.bpm > 0f }
        } ?: return null
        val i = flat.indexOfFirst { it.uri == uri }
        return if (i >= 0) flat.getOrNull(i + 1) else null
    }

    private fun updateFromExo() {
        val idx = exo.currentMediaItemIndex
        currentTrack.value = queueTracks.getOrNull(idx)
        // Suivant selon l'ordre réel de lecture (shuffle compris)
        nextTrack.value = queueTracks.getOrNull(exo.nextMediaItemIndex)
        if (mode.value == PlayerMode.MIX) {
            var phase = 0
            for ((i, start) in phaseStartIndices.withIndex()) {
                if (idx >= start) phase = i
            }
            currentPhase.value = phase
        }
        applyVolume()
        recordHistory(currentTrack.value?.uri)
        notifyWidgets()
    }

    /** Redessine les widgets d'écran d'accueil (morceau, file, play/pause). */
    private fun notifyWidgets() {
        if (!initialized) return
        try {
            com.pulsemix.app.widget.PulseWidgets.refresh(appContext)
        } catch (_: Exception) {
        }
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

    // ------------------------------------------------- enchaînement des mix

    /**
     * Ce qui a produit le mix en cours : de quoi en régénérer un autre du
     * même genre quand celui-ci se termine. Les plans sont tirés au sort
     * dans la bibliothèque, donc le suivant aura les mêmes caractéristiques
     * sans être le même.
     */
    data class MixSpec(
        val planId: String,
        val dj: Boolean,
        val targetMinutes: Int?,
        val genre: String?
    )

    private var mixSpec: MixSpec? = null

    /** Décompte affiché avant l'enchaînement (3, 2, 1), null sinon. */
    val autoNextIn = MutableStateFlow<Int?>(null)

    private val autoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var autoJob: Job? = null

    /** Mémorise de quoi enchaîner (appelé au lancement d'un mix). */
    fun setMixSpec(spec: MixSpec?) {
        mixSpec = spec
    }

    /**
     * Coupe net la queue du morceau précédent et rétablit le volume : à
     * utiliser dès que l'utilisateur reprend la main (nouvelle lecture,
     * morceau suivant demandé), un fondu ne doit jamais survivre au geste
     * qui le rend caduc.
     */
    fun stopTail() {
        // Une bascule en attente devient caduque : l'appelant (nouveau mix,
        // nouvelle lecture…) refait la file lui-même, l'appliquer sauterait
        // dans une file qui n'existe déjà plus.
        pendingSwitch = null
        tailJob?.cancel()
        tailJob = null
        releaseTail()
        seekJob?.cancel()
        seekJob = null
        if (fadeGain != 1f) {
            fadeGain = 1f
            if (initialized) applyVolume()
        }
    }

    /** Annule un enchaînement en cours (l'utilisateur reprend la main). */
    fun cancelAutoNext() {
        autoJob?.cancel()
        autoJob = null
        autoNextIn.value = null
    }

    /**
     * Fin d'un mix : décompte 3-2-1 à l'écran, puis un nouveau mix du même
     * type. Le plan se construit pendant le décompte, pour enchaîner sans
     * blanc. Si rien ne peut être construit, on s'arrête simplement.
     */
    private fun startAutoNext() {
        val spec = mixSpec ?: return
        if (autoJob?.isActive == true) return
        autoJob = autoScope.launch {
            val store = try {
                com.pulsemix.app.Graph.store
            } catch (_: Exception) {
                autoNextIn.value = null
                return@launch
            }
            // Le plan se prépare pendant que le décompte tourne
            val building = async {
                val all = store.tracks.value
                MixEngine.proposeMixes(
                    all, spec.dj, spec.targetMinutes, spec.genre
                ).firstOrNull { it.id == spec.planId }
            }
            for (n in 3 downTo 1) {
                autoNextIn.value = n
                delay(1_000)
            }
            val next = try {
                building.await()
            } catch (_: Exception) {
                null
            }
            autoNextIn.value = null
            // Lancer la lecture appelle cancelAutoNext() : on se retire
            // d'abord, sinon la coroutine s'annulerait elle-même.
            autoJob = null
            withContext(Dispatchers.Main) {
                if (next != null) {
                    if (spec.dj) startDj(next) else startMix(next)
                } else {
                    launchMessage.value =
                        "Impossible d'enchaîner : plus assez de morceaux " +
                        "pour un nouveau « ${spec.planId} »."
                }
            }
        }
    }

    /** Coupe la lecture en cours (réveil arrêté / répété). */
    fun stopPlayback() {
        if (!initialized) return
        stopDjIfNeeded()
        try {
            exo.stop()
        } catch (_: Exception) {
        }
        isPlaying.value = false
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

    /** Journal partagé avec PlaybackService : service_log.txt (interne +
     *  externe, comme crash_log.txt). */
    private fun diagLog(message: String) {
        try {
            for (dir in listOfNotNull(
                appContext.filesDir, appContext.getExternalFilesDir(null)
            )) {
                val f = java.io.File(dir, "service_log.txt")
                if (f.length() > 64_000) f.delete()
                f.appendText("${java.util.Date()}: [PlayerCore] $message\n")
            }
        } catch (_: Exception) {
        }
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
            diagLog("startService demandé")
        } catch (e: Exception) {
            diagLog("startService refusé : ${e::class.java.simpleName} ${e.message}")
            // App en arrière-plan (rare, les commandes viennent de l'UI) :
            // là, la version foreground est permise et la lecture qui démarre
            // fera poster la notification immédiatement.
            try {
                ContextCompat.startForegroundService(
                    appContext, Intent(appContext, PlaybackService::class.java)
                )
                diagLog("startForegroundService demandé")
            } catch (e2: Exception) {
                diagLog(
                    "startForegroundService refusé : " +
                        "${e2::class.java.simpleName} ${e2.message}"
                )
            }
        }
    }
}
