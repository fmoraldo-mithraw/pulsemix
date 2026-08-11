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
import kotlinx.coroutines.asCoroutineDispatcher
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
 * « Suivant » et « précédent » naviguent de MORCEAU en morceau, quel que
 * soit le mode.
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
        if (!enabled) releasePrepared()
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("crossfade", enabled).apply()
    }

    /** Durée du fondu croisé, en secondes (3 à 15). */
    val crossfadeSeconds = MutableStateFlow(10)

    fun setCrossfadeSeconds(seconds: Int) {
        val s = seconds.coerceIn(3, 15)
        // Le curseur rappelle à chaque image : sans ce garde-fou, on
        // écrivait dans les préférences soixante fois par seconde.
        if (s == crossfadeSeconds.value) return
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
        ensureTicker()
    }

    fun toggleSpeedBoost() {
        speedLevel.value = if (speedLevel.value == 0) 2 else 0
        ensureTicker()
    }

    fun setBassLevel(level: Int) {
        bassLevel.value = level.coerceIn(-3, 3)
        ensureTicker()
    }

    fun setSpeedLevel(level: Int) {
        speedLevel.value = level.coerceIn(-3, 3)
        ensureTicker()
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
        ensureTicker()
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

    /**
     * Rang du morceau en cours dans la file affichée, -1 si inconnu. Le
     * chercher par URI se trompe quand la même chanson y figure deux fois.
     */
    val currentIndex = MutableStateFlow(-1)
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

    /**
     * Un SEUL fil pour écrire l'état : deux sauvegardes simultanées (tick
     * de position + travaux ménagers différés) écrivaient le même fichier
     * temporaire — état de reprise corrompu ou instantané ancien qui
     * écrase le récent.
     */
    private val stateWriter = java.util.concurrent.Executors
        .newSingleThreadExecutor { r -> Thread(r, "state-writer") }
        .asCoroutineDispatcher()
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
        // La latence d'amorçage est propre à l'appareil : la retenir entre
        // les sessions rend le PREMIER fondu aussi propre que les suivants,
        // au lieu de repartir d'une valeur typique à recalibrer.
        tailStartupLagMs = prefs.getLong("tailStartupLag", 120L).coerceIn(0L, 400L)
        repeatMode.value = prefs.getInt("repeatMode", 0).coerceIn(0, 2)
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
                // La boucle d'entretien s'endort à l'arrêt : tout retour de
                // la lecture doit la réveiller.
                if (playing) ensureTicker()
                // La queue du morceau précédent ne doit pas jouer seule —
                // mais SEULEMENT si la lecture est vraiment arrêtée. Un
                // saut met le lecteur en tampon, donc `playing` retombe à
                // faux alors que l'intention de jouer demeure : couper là
                // tuait le fondu à l'instant même où il commençait.
                if (!playing && !exo.playWhenReady) stopTail()
                if (mode.value == PlayerMode.DJ) mixer.setPaused(!playing)
                scheduleHousekeeping()
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                // Le lecteur a enchaîné TOUT SEUL (fin de morceau atteinte)
                // alors qu'une bascule attendait encore que sa queue soit
                // prête. La queue, qui porte le morceau tout juste terminé,
                // n'a plus rien à prolonger : on s'en sépare. Le sort de la
                // bascule dépend de son origine : automatique, l'enchaînement
                // qui vient d'avoir lieu EST son intention — on la jette (la
                // rejouer faisait rejouer la fin du morceau et sauter un
                // morceau). De geste, ses cibles sont absolues, arrêtées à
                // l'appui : l'enchaînement ne la périme pas, on l'applique
                // tout de suite — sans elle, un « précédent » ou un saut de
                // phase pressé juste avant la fin était silencieusement
                // avalé. Nos propres bascules passent par un seek : elles
                // arrivent ici avec REASON_SEEK, jamais AUTO.
                // REPEAT : la fin naturelle qui REBOUCLE sur le même morceau
                // (répétition du morceau, ou liste d'un seul) est le même
                // événement qu'un enchaînement — la ceinture d'index de la
                // bascule, elle, ne voit rien : l'index n'a pas changé.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                ) {
                    val pending = pendingSwitch
                    if (pending != null) {
                        pendingSwitch = null
                        releaseTail()
                        if (pending.fromGesture) quickSwitch(pending.go)
                    }
                }
                // La queue pré-armée visait le morceau qui vient de sortir
                // (une bascule qui la consomme l'a déjà retirée avant d'en
                // arriver ici) : celle du morceau suivant se ré-armera au
                // prochain passage du tick.
                releasePrepared()
                // La marque « fin déjà fondue » protège UNE lecture du
                // morceau : la transition faite, elle a rempli son office.
                // La garder collait en répétition (liste d'un seul morceau,
                // morceau en boucle) : le même URI revient, et tous les
                // tours suivants enchaînaient à sec.
                crossfadedFrom = null
                if (mode.value != PlayerMode.DJ) updateFromExo()
                scheduleHousekeeping()
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
                // Annonce d'un set déjà arrêté (elle voyage ~1 s en différé) :
                // ne pas écraser l'état d'un autre mode lancé entre-temps.
                if (mode.value != PlayerMode.DJ || !mixer.isRunning) return
                prevDjUri = currentTrack.value?.uri
                currentTrack.value = track
                currentPhase.value = phaseIndex
                // Le plan ne contient jamais deux fois la même chanson
                // (dedupePlan) : l'URI suffit à situer le morceau.
                currentIndex.value = queue.value.indexOfFirst { it.uri == track.uri }
                nextTrack.value = djNextAfter(track.uri)
                recordHistory(track.uri)
                // Différé : le changement de piste DJ tombe en pleine
                // transition battue, pas le moment d'aller peindre des widgets.
                scheduleHousekeeping()
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
                scheduleHousekeeping()
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
        ticker = object : Runnable {
            override fun run() {
                if (mode.value != PlayerMode.DJ) {
                    val d = exo.duration
                    if (d > 0) progress.value =
                        (exo.currentPosition.toFloat() / d).coerceIn(0f, 1f)
                    // Fondu croisé vers le morceau suivant : déclenché assez
                    // tôt pour que les deux se chevauchent vraiment.
                    //
                    // Plancher de la fenêtre : ce tick vit sur un Handler que
                    // l'écran éteint retarde parfois de plusieurs secondes. Se
                    // réveiller à deux secondes de la fin et lancer quand même
                    // un fondu garantissait le pire : le morceau se terminait
                    // naturellement pendant la préparation, puis la bascule
                    // tardive rejouait sa fin et sautait un morceau. Trop
                    // tard, c'est trop tard : on laisse l'enchaînement direct.
                    if (crossfade.value && d > 0 && isPlaying.value &&
                        exo.hasNextMediaItem() && exoTail == null &&
                        currentTrack.value?.uri != crossfadedFrom &&
                        // Le morceau doit être PLUS LONG que la fenêtre :
                        // sinon un titre de 12 s sous un fondu de 10 s
                        // partait en fondu dès sa première seconde et
                        // n'était entendu que comme queue du suivant.
                        d > CROSSFADE_MS + CROSSFADE_LEAD_MS + 3_000L &&
                        d - exo.currentPosition in
                        MIN_AUTO_CROSSFADE_REMAIN_MS..(CROSSFADE_MS + CROSSFADE_LEAD_MS)
                    ) {
                        crossfadedFrom = currentTrack.value?.uri
                        crossfadeToNext()
                    }
                    // Pré-armement : la queue s'ouvre et se met en tampon
                    // dès que la fin approche, une douzaine de secondes
                    // avant le déclenchement du fondu. La bascule devient
                    // immédiate — c'est l'attente d'ouverture qui laissait
                    // la fenêtre aux fins naturelles et aux garde-fous.
                    if (crossfade.value && d > 0 && isPlaying.value &&
                        exo.hasNextMediaItem() && exoTail == null &&
                        preparedTail == null &&
                        currentTrack.value?.uri != crossfadedFrom &&
                        d - exo.currentPosition in
                        (CROSSFADE_MS + CROSSFADE_LEAD_MS + 1)..
                        (CROSSFADE_MS + CROSSFADE_LEAD_MS + PREARM_AHEAD_MS)
                    ) {
                        prepareTailAhead()
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
                // Position SEULE : la structure de la file n'a pas bougé
                // entre deux ticks, resérialiser toute la file toutes les
                // 5 s pendant la lecture usait la flash (~70 Mo/h) pour
                // deux nombres. L'instantané complet, lui, n'est écrit que
                // quand la structure change (persistState).
                // Jamais pendant qu'une bascule de fondu est en vol : ce
                // travail sur le thread principal tomberait pile sur
                // l'instant le plus sensible à l'oreille, il attendra le
                // prochain tour.
                if (isPlaying.value && pendingSwitch == null &&
                    System.currentTimeMillis() - lastSaveMs > 5_000
                ) {
                    persistPositionOnly()
                }
                // Barre de progression des widgets : toutes les 3 s, assez
                // pour qu'elle avance visiblement sans marteler le système.
                // Chemin LÉGER : seule la progression a changé entre deux
                // ticks — reconstruire tout le widget (25 PendingIntent,
                // jaquette relue sur un thread) toutes les 3 s coûtait cher
                // pour une barre qui avance.
                if (isPlaying.value && pendingSwitch == null &&
                    System.currentTimeMillis() - lastWidgetTickMs > 3_000
                ) {
                    lastWidgetTickMs = System.currentTimeMillis()
                    try {
                        com.pulsemix.app.widget.PulseWidgets.refreshProgress(appContext)
                    } catch (_: Exception) {
                    }
                }
                // À l'arrêt complet — rien ne joue, aucune rampe ni minuterie
                // en cours — ce réveil deux fois par seconde ne servait plus
                // qu'à user la batterie. Il s'interrompt et repart au premier
                // signe de vie (lecture, minuterie, réglage d'effet).
                if (exo.playWhenReady || sleepDeadline > 0 || fadeGain != 1f ||
                    trebleExtraDb != 5f * trebleLevel.value ||
                    bassBoostExtraDb != 5f * bassLevel.value ||
                    exoSpeed != 1f + 0.08f * speedLevel.value ||
                    // DJ : seulement tant qu'un set tourne vraiment — mode
                    // DJ au repos, ce réveil 2×/s ne servait qu'à la batterie
                    (mode.value == PlayerMode.DJ && mixer.isRunning)
                ) {
                    handler.postDelayed(this, 500)
                } else {
                    tickerRunning = false
                }
            }
        }
        tickerRunning = true
        handler.post(ticker)
    }

    /** Boucle d'entretien (progression, fondu de fin, minuteries). */
    private lateinit var ticker: Runnable
    private var tickerRunning = false

    /**
     * Relance la boucle d'entretien si elle s'était endormie. À appeler à
     * chaque événement qui redonne du travail au tick : lecture,
     * minuterie de sommeil, changement d'effet.
     */
    private fun ensureTicker() {
        if (!initialized || tickerRunning) return
        tickerRunning = true
        handler.post(ticker)
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
        applyRepeat()
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
        applyRepeat()
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
        // L'ancien set d'abord : son arrêt différé (posté sur le main
        // looper) arrivait APRÈS le lancement du nouveau et le mettait en
        // pause — coupé ici, ses callbacks périmés sont jetés par le mixer.
        stopDjIfNeeded()
        mode.value = PlayerMode.DJ
        plan = mixPlan
        planName.value = mixPlan.name + " (DJ)"
        phaseNames.value = mixPlan.phases.map { it.name }
        currentPhase.value = fromPhase.coerceIn(0, mixPlan.phases.size - 1)
        // Le déroulé du set, dans l'ordre : ce qui a été joué et ce qui
        // reste. La file n'est pas éditable en DJ — les fonctions qui la
        // modifient s'en gardent déjà — mais elle a tout lieu d'être
        // consultable, comme dans les autres modes.
        queueTracks = mixPlan.phases.flatMap { it.tracks }
        // Le moteur annoncera le morceau exact dans un instant ; en attendant,
        // se placer au début de la phase demandée plutôt qu'en tête du set.
        currentIndex.value = mixPlan.phases.take(currentPhase.value)
            .sumOf { it.tracks.size }
            .takeIf { it < queueTracks.size } ?: -1

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

    /** Micro-fondu de pause/reprise en cours (annulé par le geste suivant). */
    private var pauseFadeJob: Job? = null

    fun togglePlayPause() {
        // Mode DJ restauré (ou terminé) : le moteur n'est pas lancé, on
        // redémarre au début de la phase courante.
        if (mode.value == PlayerMode.DJ && !mixer.isRunning) {
            val p = plan ?: return
            startDj(p, currentPhase.value.coerceAtLeast(0))
            return
        }
        // En DJ, la pause est routée vers le moteur (via le listener) : les
        // micro-fondus ci-dessous ne concernent que le lecteur ExoPlayer.
        if (mode.value == PlayerMode.DJ) {
            if (exo.isPlaying) exo.pause() else exo.play()
            return
        }
        pauseFadeJob?.cancel()
        if (exo.isPlaying) {
            // Couper net fait un clic : le son tombe en ~120 ms d'abord.
            pauseFadeJob = autoScope.launch(Dispatchers.Main) {
                for (i in 5 downTo 0) {
                    fadeGain = i / 5f
                    applyVolume()
                    if (i > 0) delay(20L)
                }
                exo.pause()
                // Prêt pour la reprise : plein volume dès que voulu
                fadeGain = 1f
            }
        } else {
            if (exo.mediaItemCount == 0) {
                // File perdue mais connue : la reconstruire au lieu d'un
                // bouton play muet.
                if (queueTracks.isEmpty()) return
                exo.setMediaItems(queueTracks.map { mediaItem(it) }, 0, 0)
            }
            // Après une erreur de lecture, ExoPlayer reste inerte tant qu'on
            // ne re-prépare pas : play/next semblaient morts.
            if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
            // Reprise en douceur, symétrique de la pause
            fadeGain = 0f
            applyVolume()
            exo.play()
            startService()
            fadeInMain(150L)
        }
    }

    /**
     * Exécute [go] — un changement de morceau demandé à la main — en fondu
     * croisé, comme l'enchaînement naturel de fin de morceau. Saut direct si
     * le réglage est coupé, s'il n'y a rien à prolonger, ou si [possible] est
     * faux (bout de file : le fondu jouerait contre lui-même).
     */
    private fun crossfadeTo(possible: Boolean, go: () -> Unit) {
        val track = currentTrack.value
        if (!possible || !crossfade.value || track == null || !isPlaying.value) {
            consumePendingBeforeGesture()
            go()
            return
        }
        // Pas besoin de marquer le morceau comme déjà fondu : la queue reste
        // en place pendant toute la bascule, et le déclencheur de fin exige
        // justement qu'il n'y en ait aucune.
        handOffTail(
            track, exo.currentPosition, CROSSFADE_MS, GESTURE_WATCHDOG_MS,
            fromGesture = true, go
        )
    }

    fun next() {
        // Tout changement voulu rend caduque la marque « fin déjà fondue » :
        // si on revient plus tard sur ce morceau, sa fin doit l'être encore.
        crossfadedFrom = null
        if (mode.value == PlayerMode.DJ) {
            stopTail()
            // Morceau suivant, pas phase suivante : « suivant » avance d'un
            // morceau quel que soit le mode. Le moteur y fait une vraie
            // transition battue, comme pour un enchaînement naturel.
            mixer.nextTrack()
            return
        }
        // Un geste précédent encore en attente est appliqué d'abord : les
        // cibles du nouveau s'arrêtent sur l'état qui en résulte — deux
        // « suivant » rapprochés avancent bien de deux morceaux.
        consumePendingBeforeGesture()
        // Cibles ABSOLUES, arrêtées au moment de l'appui : la bascule ne
        // s'applique qu'une fois la queue prête, et la file a pu enchaîner
        // toute seule entre-temps. Une cible relative (seekToNextMediaItem)
        // avancerait alors d'un morceau de trop ; l'index, lui, reste juste
        // — shuffle compris, nextMediaItemIndex suit l'ordre de lecture.
        // En MIX aussi : « suivant » avance d'un morceau dans la file, le
        // saut de phase n'est plus son affaire.
        // En répétition du morceau, nextMediaItemIndex désigne… le morceau
        // courant : l'appui relançait le même titre. Le bouton, lui, veut
        // dire « avance quand même » — cible calculée à la main.
        val target = if (exo.repeatMode == Player.REPEAT_MODE_ONE) {
            val n = exo.mediaItemCount
            if (n > 0) (exo.currentMediaItemIndex + 1) % n else C.INDEX_UNSET
        } else {
            exo.nextMediaItemIndex
        }
        crossfadeTo(exo.hasNextMediaItem()) {
            when {
                target != C.INDEX_UNSET -> exo.seekTo(target, 0)
                else -> exo.seekToNextMediaItem()
            }
        }
    }

    fun previous() {
        // On peut revenir sur un morceau dont la fin a déjà été fondue : sa
        // fin doit pouvoir l'être à nouveau.
        crossfadedFrom = null
        if (mode.value == PlayerMode.DJ) {
            stopTail()
            // Morceau précédent, pas phase précédente : même règle que
            // « suivant », la navigation se fait morceau par morceau.
            mixer.prevTrack()
            return
        }
        // Un geste précédent encore en attente est appliqué d'abord, puis
        // les cibles sont arrêtées en ABSOLU — mêmes raisons que next().
        // « Reviens au début de CE morceau » doit rester ce morceau-là,
        // même si la file enchaîne toute seule pendant la préparation.
        // En MIX aussi : retour au début du morceau (ou au précédent),
        // le saut de phase n'est plus son affaire.
        consumePendingBeforeGesture()
        val restart = exo.currentPosition > 3000
        val restartIndex = exo.currentMediaItemIndex
        // Même règle que next() : en répétition du morceau, l'index
        // « précédent » du lecteur désigne le morceau courant.
        val prevIndex = if (exo.repeatMode == Player.REPEAT_MODE_ONE) {
            val n = exo.mediaItemCount
            if (n > 0) (exo.currentMediaItemIndex - 1 + n) % n else C.INDEX_UNSET
        } else {
            exo.previousMediaItemIndex
        }
        crossfadeTo(restart || exo.hasPreviousMediaItem()) {
            when {
                restart -> exo.seekTo(restartIndex, 0)
                prevIndex != C.INDEX_UNSET -> exo.seekTo(prevIndex, 0)
                else -> exo.seekToPreviousMediaItem()
            }
        }
    }

    fun seekToFraction(f: Float) {
        if (mode.value == PlayerMode.DJ) return
        // Repositionnement explicite : le fondu de fin redevient possible,
        // et tout ce qui attendait est réglé d'abord (même règle que les
        // autres gestes).
        crossfadedFrom = null
        consumePendingBeforeGesture()
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
     * Égaliseur de la queue, copie de celui du principal. Sans lui, un
     * morceau écouté avec les basses boostées ou l'égaliseur réglé perdait
     * d'un coup tout son caractère en passant au second lecteur — un saut
     * de timbre entendu comme une saccade à l'instant de la bascule.
     */
    private var eqTail: android.media.audiofx.Equalizer? = null

    /**
     * Changement de morceau demandé mais pas encore appliqué : il n'a lieu
     * qu'une fois la queue prête à prolonger le son.
     *
     * L'origine compte. Une bascule née d'un GESTE qu'un nouveau geste
     * remplace doit être appliquée d'abord — deux « suivant » rapprochés
     * avancent de deux morceaux. Une bascule née de l'ENCHAÎNEMENT
     * AUTOMATIQUE de fin de morceau, elle, doit être jetée : l'utilisateur
     * ne l'a pas encore entendue, la rejouer sous son geste faisait sauter
     * un morceau de trop — et « précédent » atterrissait au début du
     * morceau… suivant.
     */
    private class PendingSwitch(val go: () -> Unit, val fromGesture: Boolean)

    private var pendingSwitch: PendingSwitch? = null

    /**
     * Retire la bascule en attente et l'applique si c'était un geste.
     * Toujours AVANT stopTail (qui l'oublierait) ; l'application vient
     * après, car stopTail remet les fondus à plat.
     */
    private fun consumePendingBeforeGesture() {
        val pending = pendingSwitch
        pendingSwitch = null
        stopTail()
        if (pending != null && pending.fromGesture) quickSwitch(pending.go)
    }

    /**
     * Saut immédiat, sans queue pour prolonger le son : on remonte du
     * silence en un quart de seconde. Assez court pour rester vif, assez
     * long pour couvrir la mise en tampon du morceau d'arrivée — à plein
     * volume, elle s'entendait comme un claquement suivi d'un blanc.
     */
    private fun quickSwitch(go: () -> Unit) {
        fadeGain = 0f
        applyVolume()
        go()
        fadeInMain(NO_TAIL_FADE_MS)
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
     * Pont de bascule entre la queue et le principal : 5 pas de 12 ms,
     * soit ~60 ms de tuilage. Assez long pour noyer l'accroc de phase d'un
     * échange sec entre deux flux jamais alignés à l'échantillon près,
     * assez court pour que le dédoublement des deux sources — décalées de
     * quelques dizaines de millisecondes au plus — reste fondu en un seul
     * son à l'oreille.
     */
    private const val BRIDGE_STEPS = 5
    private const val BRIDGE_STEP_MS = 12L

    /**
     * Fondu d'entrée quand il n'y a PAS de queue pour prolonger le son.
     *
     * Un fondu croisé n'a de sens que si les deux sources sonnent ensemble.
     * Sans queue — fichier trop lent à ouvrir, erreur de lecture — remonter
     * depuis le silence sur dix secondes laisse un vrai trou : le morceau
     * sortant s'arrête net et le suivant reste inaudible plusieurs
     * secondes. Mieux vaut alors arriver franchement.
     */
    private const val NO_TAIL_FADE_MS = 250L

    /**
     * Temps laissé à la queue pour sortir sa première goutte de son. Un
     * fichier froid, ouvert par l'explorateur de documents et déplacé d'un
     * cran, peut demander plus que quelques dizaines de millisecondes.
     */
    private const val TAIL_START_TIMEOUT_MS = 1_500L

    /** Attente maximale sur un geste : l'utilisateur ne doit pas patienter. */
    private const val GESTURE_WATCHDOG_MS = 2_500L

    /**
     * Attente maximale sur l'enchaînement automatique de fin de morceau.
     * Personne n'attend, et le fondu est déclenché douze secondes avant la
     * fin : mieux vaut laisser au fichier le temps de s'ouvrir que de
     * renoncer au fondu et couper le son.
     */
    private const val AUTO_WATCHDOG_MS = 6_000L

    /**
     * Avance du pré-armement de la queue sur le déclenchement du fondu :
     * l'ouverture du fichier se fait pendant cette marge, et la bascule
     * n'a plus rien à attendre.
     */
    private const val PREARM_AHEAD_MS = 13_000L

    /**
     * En deçà de ce reste, on ne lance plus de fondu de fin : le temps de
     * préparer la queue, le morceau serait déjà fini. L'enchaînement direct
     * d'ExoPlayer, quasi sans blanc, vaut mieux qu'une bascule après la
     * bataille.
     *
     * Borne ADAPTATIVE : figée à 4 s, elle écrasait la fenêtre de
     * déclenchement aux petits réglages — à 3 s de fondu, la fenêtre
     * [4 s, 5 s] ne faisait qu'une seconde, et le premier tick un peu
     * retardé l'enjambait : plus aucun fondu de fin, silencieusement. La
     * fenêtre garde donc au moins trois secondes de large ; descendre la
     * borne est sans danger, les verrous de bascule tardive (transition
     * AUTO, jeton d'index) couvrent déjà le cas où c'était trop juste.
     */
    private val MIN_AUTO_CROSSFADE_REMAIN_MS: Long
        get() = 4_000L.coerceAtMost(CROSSFADE_MS + CROSSFADE_LEAD_MS - 3_000L)

    /**
     * Écart résiduel (rejoue ou élision) au-delà duquel le fondu croisé est
     * abandonné au profit d'une arrivée franche. Grâce à la compensation
     * d'amorçage ([tailStartupLagMs]), le résidu typique est de quelques
     * dizaines de millisecondes — sous le seuil de fusion de l'oreille ;
     * ce garde-fou ne joue plus que si l'appareil se comporte bizarrement.
     */
    private const val MAX_TAIL_DRIFT_MS = 150L

    /**
     * Latence d'amorçage de la sortie audio du second lecteur, mesurée sur
     * CET appareil : le temps entre play() et la première goutte de son.
     * La queue est recalée en avance d'autant, pour que son raccord avec le
     * direct tombe à quelques millisecondes près — c'est ce qui rend la
     * bascule imperceptible, là où tolérer le retard laissait un bégaiement
     * et où l'interdire aurait coupé sec.
     *
     * Affinée à chaque fondu par moyenne glissante ; la valeur de départ
     * est l'amorçage typique d'un AudioTrack Android.
     */
    @Volatile private var tailStartupLagMs = 120L

    /**
     * Second lecteur muet et sans focus audio, préparé sur le même item que
     * le principal (rognage d'intro compris : avec « sauter les intros »,
     * les positions du principal sont relatives au point de rognage — une
     * queue construite sur le fichier entier rejouait un passage décalé de
     * toute l'intro).
     */
    private fun newTailPlayer(track: Track): ExoPlayer =
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
            setMediaItem(mediaItem(track))
            seekTo(exo.currentPosition)
            // Muet et à l'arrêt tant qu'il n'a pas de quoi jouer : ouvrir le
            // fichier et remplir son tampon prend un instant, et c'est
            // précisément ce délai qui coupait le son.
            volume = 0f
            playWhenReady = false
            prepare()
        }

    /**
     * Queue pré-armée : ouverte et mise en tampon ~25 s avant la fin du
     * morceau, pour que la bascule du fondu soit immédiate le moment venu.
     */
    private var preparedTail: ExoPlayer? = null
    private var preparedUri: String? = null

    /** Ouvre la queue d'avance pour le morceau en cours (fin approchante). */
    private fun prepareTailAhead() {
        val track = currentTrack.value ?: return
        preparedTail = try {
            newTailPlayer(track)
        } catch (_: Exception) {
            null
        }
        preparedUri = if (preparedTail != null) track.uri else null
    }

    private fun releasePrepared() {
        val p = preparedTail ?: return
        preparedTail = null
        preparedUri = null
        try {
            p.release()
        } catch (_: Exception) {
        }
    }

    /**
     * Confie [track] à partir de [fromMs] au second lecteur, qui le prolonge
     * puis s'efface sur [fadeMs]. Le lecteur principal est libre de partir
     * ailleurs immédiatement.
     */
    private fun handOffTail(
        track: Track,
        fromMs: Long,
        fadeMs: Long,
        watchdogMs: Long,
        fromGesture: Boolean,
        onSwitch: () -> Unit
    ) {
        // Une bascule de geste encore en attente est appliquée d'abord (deux
        // « suivant » rapprochés avancent de deux morceaux) ; une bascule
        // automatique est jetée (voir PendingSwitch).
        consumePendingBeforeGesture()
        // Une queue pré-armée pour ce morceau (voir prepareTailAhead) est
        // déjà ouverte, mise en tampon et prête : la bascule est immédiate
        // au lieu d'attendre l'ouverture du fichier — c'est cette attente
        // qui laissait la fenêtre aux fins naturelles et aux garde-fous.
        val reused = preparedTail?.takeIf { preparedUri == track.uri }
        if (reused != null) {
            preparedTail = null
            preparedUri = null
        } else {
            releasePrepared()
        }
        val player = reused ?: try {
            newTailPlayer(track).apply { seekTo(fromMs) }
        } catch (_: Exception) {
            // Pas de second lecteur : basculer sans fondu croisé, mais sans
            // claquer non plus.
            quickSwitch(onSwitch)
            return
        }
        exoTail = player
        val token = PendingSwitch(onSwitch, fromGesture)
        pendingSwitch = token
        // Le morceau que cette bascule doit quitter. Si la file a avancé
        // entre-temps — fin de morceau atteinte, le lecteur a enchaîné tout
        // seul — la bascule est caduque : l'appliquer rejouerait la fin du
        // morceau terminé et sauterait un morceau. Le listener
        // onMediaItemTransition l'annule dès la transition ; ce jeton est la
        // ceinture qui couvre l'ordre d'arrivée des messages.
        val expectedIndex = exo.currentMediaItemIndex
        var switched = false

        /** Bascule : les deux sources jouent, puis se croisent. */
        fun switchNow(withTail: Boolean) {
            if (switched) return
            switched = true
            // Un geste plus récent a repris la main : cette bascule ne nous
            // appartient plus. Ni le son ni la queue, désormais à lui, ne
            // doivent être touchés — le garde-fou des 2,5 s passe ici quand
            // la demande a déjà été remplacée.
            if (pendingSwitch !== token) return
            // Ceinture pour les bascules AUTOMATIQUES seulement : si la file
            // a avancé toute seule, le fondu de fin n'a plus d'objet. Les
            // bascules de geste, elles, visent des cibles absolues qui
            // restent justes même si la file a bougé — les abandonner ici
            // avalerait l'appui.
            if (!fromGesture && exo.currentMediaItemIndex != expectedIndex) {
                pendingSwitch = null
                releaseTail()
                return
            }
            val v0 = exo.volume
            if (!withTail) {
                releaseTail()
                pendingSwitch = null
                fadeGain = 0f
                applyVolume()
                onSwitch()
                // Pas de queue pour tenir le son : un fondu de dix secondes
                // depuis le silence serait un trou, pas une transition.
                fadeInMain(NO_TAIL_FADE_MS)
                return
            }
            autoScope.launch(Dispatchers.Main) {
                var eff = fadeMs
                // La queue a-t-elle vraiment sorti du son ? Tant qu'on n'en
                // est pas sûr, couper le lecteur principal ouvrirait un blanc.
                var tailAudible = false
                try {
                    player.volume = 0f
                    // Recaler la queue sur le direct — EN AVANCE de la
                    // latence d'amorçage mesurée sur cet appareil. Elle a été
                    // préparée à la position qu'occupait le lecteur principal
                    // au moment de la demande ; entre le seek et la première
                    // goutte de son, la sortie audio met quelques dizaines de
                    // millisecondes à s'amorcer, pendant lesquelles le direct
                    // continue. Sans cette avance, la queue reprenait avec ce
                    // retard tout entier : un bout déjà entendu rejouait à la
                    // bascule. Le tampon couvre largement le saut en avant.
                    val live = exo.currentPosition
                    val target = live + tailStartupLagMs
                    val compensated = target - player.currentPosition > 40L
                    if (compensated) player.seekTo(target)
                    // « Prêt » ne veut pas dire « audible » : on lance la
                    // queue EN MUET et on attend qu'elle avance vraiment —
                    // preuve qu'elle sort du son — avant de basculer. Pendant
                    // ce temps le morceau en cours continue normalement.
                    player.play()
                    val start = player.currentPosition
                    var waited = 0L
                    while (waited < TAIL_START_TIMEOUT_MS &&
                        player.currentPosition <= start
                    ) {
                        delay(20L)
                        waited += 20L
                        // Un geste de l'utilisateur a pu congédier la queue
                        // entre-temps : ne pas toucher un lecteur libéré.
                        if (exoTail !== player) return@launch
                    }
                    tailAudible = player.currentPosition > start
                    // Écart résiduel entre le direct et la queue à l'instant
                    // de la bascule. Positif : un bout rejoue ; négatif : un
                    // bout est élidé. Compensé, il tombe à quelques dizaines
                    // de millisecondes — sous le seuil où l'oreille fusionne
                    // les deux en un seul son.
                    val residual = exo.currentPosition - player.currentPosition
                    if (compensated) {
                        // La latence réelle de CE fondu affine l'estimation
                        // pour les suivants (moyenne glissante, bornée), et
                        // la valeur suit l'appareil d'une session à l'autre.
                        val actual = (residual + tailStartupLagMs).coerceIn(0L, 400L)
                        tailStartupLagMs = (tailStartupLagMs * 7 + actual * 3) / 10
                        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
                            .edit().putLong("tailStartupLag", tailStartupLagMs).apply()
                    }
                    // Résidu trop grand malgré tout — rejoue comme élision
                    // s'entendraient : arrivée franche plutôt que raccord sale.
                    if (tailAudible && kotlin.math.abs(residual) > MAX_TAIL_DRIFT_MS) {
                        tailAudible = false
                    }
                    if (tailAudible) {
                        // Même timbre que le principal : l'égaliseur et les
                        // boosts suivent le morceau sur le second lecteur.
                        // La session audio n'existe qu'une fois la lecture
                        // partie — d'où la création ici et pas à la
                        // préparation.
                        eqTail = try {
                            android.media.audiofx.Equalizer(0, player.audioSessionId)
                                .also { applyEqTo(it, includeFilter = true) }
                        } catch (_: Exception) {
                            null
                        }
                        // La queue reste MUETTE ici : c'est le pont ci-dessous
                        // qui la fait monter pendant que le principal descend.
                        // Le fondu ne doit pas déborder de la fin du fichier :
                        // le son s'arrêterait net au milieu du croisement.
                        // La durée annoncée par le lecteur n'est pas toujours
                        // connue juste après un déplacement — repli sur celle
                        // de la bibliothèque, intro rognée déduite pour rester
                        // dans le même repère que les positions du lecteur.
                        val clipped = if (skipIntros.value && track.musicStartMs > 1_500L)
                            track.musicStartMs else 0L
                        val known = player.duration.takeIf { it > 0 }
                            ?: (track.durationMs - clipped).takeIf { it > 0 }
                        if (known != null) {
                            val remain = known - player.currentPosition
                            if (remain > 0) {
                                eff = fadeMs.coerceAtMost(
                                    (remain - 250L).coerceAtLeast(600L)
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                    releaseTail()
                    // La queue vient d'être libérée : la déclarer encore
                    // audible enverrait le pont jouer avec un lecteur mort,
                    // puis avorter en emportant le jeton — geste perdu et
                    // bascule jamais appliquée. Le chemin « sans queue »
                    // ci-dessous fait les deux proprement.
                    tailAudible = false
                }
                // Un geste plus récent a repris la main pendant l'attente,
                // ou le lecteur a enchaîné tout seul : cette bascule ne nous
                // appartient plus.
                if (pendingSwitch !== token) return@launch
                // La queue n'a jamais sorti de son — fichier trop lent à
                // ouvrir, position introuvable. Basculer quand même en la
                // gardant reviendrait à couper le morceau en cours pour du
                // silence : c'est exactement la microcoupure qu'on chasse.
                // On s'en sépare et on arrive franchement.
                if (!tailAudible) {
                    pendingSwitch = null
                    releaseTail()
                    fadeGain = 0f
                    applyVolume()
                    onSwitch()
                    fadeInMain(NO_TAIL_FADE_MS)
                    return@launch
                }
                // Pont de bascule : même recalées, les deux sources ne sont
                // jamais alignées à l'échantillon près — un échange sec
                // (queue muette → pleine, principal plein → muet au même
                // instant) laissait entendre un accroc de phase, la saccade
                // résiduelle. À la place, elles se croisent sur ~60 ms : la
                // queue monte pendant que le principal descend, à puissance
                // constante, et la discontinuité disparaît sous le tuilage.
                // Un fondu d'entrée hérité (geste tout juste appliqué) rendrait
                // la main au pont de toute façon : on la lui donne proprement.
                seekJob?.cancel()
                val g0 = fadeGain
                for (k in 1..BRIDGE_STEPS) {
                    val x = k.toFloat() / BRIDGE_STEPS
                    player.volume = v0 * kotlin.math.sin(x * (Math.PI / 2).toFloat())
                    fadeGain = g0 * kotlin.math.cos(x * (Math.PI / 2).toFloat())
                    applyVolume()
                    delay(BRIDGE_STEP_MS)
                    // Le pont tient le jeton pendant qu'il joue : un geste ou
                    // un enchaînement naturel survenu là a déjà réglé le sort
                    // de la queue et du volume (stopTail, quickSwitch) — sauf
                    // l'enchaînement automatique, qui laisse le gain à
                    // mi-course : on le remonte, sinon il y resterait.
                    if (pendingSwitch !== token) {
                        if (fadeGain != 1f && seekJob?.isActive != true) {
                            fadeInMain(NO_TAIL_FADE_MS)
                        }
                        return@launch
                    }
                    // Jeton encore à nous mais queue disparue : personne
                    // d'autre n'appliquera cette bascule. Sèchement plutôt
                    // que perdue — et le jeton est rendu, sinon il bloquerait
                    // l'entretien et rejouerait en fantôme des minutes après.
                    if (exoTail !== player) {
                        pendingSwitch = null
                        fadeGain = 0f
                        applyVolume()
                        onSwitch()
                        fadeInMain(NO_TAIL_FADE_MS)
                        return@launch
                    }
                }
                pendingSwitch = null
                fadeGain = 0f
                applyVolume()
                onSwitch()
                fadeInMain(eff)
                fadeOutTail(player, v0, eff)
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

        // Une queue pré-armée est DÉJÀ prête : le listener ci-dessus ne
        // verra jamais son passage à READY, il a eu lieu avant lui. La
        // bascule part tout de suite — c'est tout l'intérêt du pré-armement.
        if (player.playbackState == Player.STATE_READY) {
            switchNow(withTail = true)
        }

        // Garde-fou : un fichier qui met trop longtemps à s'ouvrir ne doit
        // pas retarder indéfiniment le geste de l'utilisateur. Il est plus
        // large pour l'enchaînement automatique de fin de morceau, qui n'a
        // personne à faire attendre et dispose de douze secondes d'avance :
        // renoncer trop vite à la queue, c'était renoncer au fondu.
        autoScope.launch(Dispatchers.Main) {
            delay(watchdogMs)
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
        try {
            eqTail?.release()
        } catch (_: Exception) {
        }
        eqTail = null
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
        // Geste : tout ce qui attendait est réglé d'abord, puis la cible est
        // arrêtée en absolu (morceau + position) — la barre visait CE
        // morceau, pas celui où la file serait rendue au moment de basculer.
        consumePendingBeforeGesture()
        val d = exo.duration
        if (d <= 0) return
        val target = (d * frac).toLong()
        val targetIndex = exo.currentMediaItemIndex
        // Vrai fondu croisé : le passage qu'on quitte continue sur le second
        // lecteur pendant que le principal se replace et remonte. Le
        // déplacement lui-même n'a lieu qu'une fois ce second lecteur prêt à
        // prendre le relais, sinon il y aurait un blanc. À L'ARRÊT en
        // revanche, il n'y a aucun son à prolonger : lancer la queue ferait
        // sonner l'appareil en pleine pause — on se replace en silence,
        // comme les boutons suivant/précédent le font déjà.
        val track = currentTrack.value
        if (track == null || !crossfade.value || !isPlaying.value) {
            exo.seekTo(targetIndex, target)
            persistState()
            return
        }
        handOffTail(
            track, exo.currentPosition, SEEK_CROSSFADE_MS, GESTURE_WATCHDOG_MS,
            fromGesture = true
        ) {
            exo.seekTo(targetIndex, target)
            // Différé : ce bloc s'exécute à l'instant précis de la bascule.
            scheduleHousekeeping()
        }
    }

    /**
     * Fondu croisé vers le morceau suivant : la fin du morceau en cours est
     * confiée au second lecteur, et le principal démarre le suivant dès que
     * ce relais est en place. Les deux se croisent réellement.
     */
    private fun crossfadeToNext() {
        val track = currentTrack.value ?: return
        handOffTail(
            track, exo.currentPosition, CROSSFADE_MS, AUTO_WATCHDOG_MS,
            fromGesture = false
        ) {
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
        ensureTicker()
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
        shiftPhaseStartsForMove(from, to)
        updateFromExo()
        persistState()
    }

    /**
     * Bornes des phases d'un mix après déplacement d'un morceau [from] →
     * [to]. La formule vit dans [QueueMath] (pur, testé en JVM) : ici on
     * ne fait que l'appliquer à l'état courant.
     */
    private fun shiftPhaseStartsForMove(from: Int, to: Int) {
        if (phaseStartIndices.isEmpty()) return
        phaseStartIndices = QueueMath.shiftPhaseStartsForMove(
            phaseStartIndices, from, to, queueTracks.size
        )
    }

    /**
     * Programme [track] juste après le morceau en cours (lecture normale
     * et mix). S'il figure déjà dans la file, il y est DÉPLACÉ plutôt que
     * dupliqué — la file affiche chaque chanson une seule fois. Sans
     * lecture en cours, le morceau est simplement lancé.
     */
    fun playNext(track: Track) {
        if (mode.value == PlayerMode.DJ) return // le moteur DJ tient son plan
        if (!initialized || exo.mediaItemCount == 0 || queueTracks.isEmpty()) {
            playNormal(listOf(track))
            return
        }
        // Comme les autres gestes qui remanient la file : un fondu de fin
        // déjà armé peut être invalidé par le décalage des index — laisser
        // le déclencheur en réarmer un propre sur la file remaniée.
        crossfadedFrom = null
        val cur = exo.currentMediaItemIndex.coerceAtLeast(0)
        val existing = queueTracks.indexOfFirst { it.uri == track.uri }
        val target: Int
        if (existing >= 0) {
            if (existing == cur) return // déjà en train de jouer
            // Après retrait, l'index du morceau en cours a pu glisser :
            // la place « juste après lui » n'est pas la même selon le sens.
            target = if (existing < cur) cur else cur + 1
            if (existing != target) {
                queueTracks = queueTracks.toMutableList().also {
                    val t = it.removeAt(existing)
                    it.add(target, t)
                }
                exo.moveMediaItem(existing, target)
                shiftPhaseStartsForMove(existing, target)
            }
        } else {
            target = (cur + 1).coerceAtMost(queueTracks.size)
            queueTracks = queueTracks.toMutableList().also { it.add(target, track) }
            exo.addMediaItem(target, mediaItem(track))
            // Le morceau inséré rejoint la phase du morceau en cours : les
            // phases qui commençaient à cet endroit ou après reculent.
            phaseStartIndices = phaseStartIndices.map { s ->
                if (s >= target) s + 1 else s
            }
        }
        // En aléatoire, l'ordre de lecture est une permutation qui ignore
        // les index : reconstruire un ordre qui part du morceau en cours,
        // enchaîne sur celui qu'on vient de programmer, puis brasse le
        // reste — sinon « jouer ensuite » atterrissait n'importe quand.
        if (exo.shuffleModeEnabled) {
            val cur2 = exo.currentMediaItemIndex.coerceAtLeast(0)
            val rest = (0 until exo.mediaItemCount)
                .filter { it != cur2 && it != target }
                .shuffled()
            exo.setShuffleOrder(
                androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(
                    (listOf(cur2, target) + rest).toIntArray(),
                    System.currentTimeMillis()
                )
            )
        }
        updateFromExo()
        persistState()
    }

    /** Saute directement à un élément de la file. */
    fun playQueueItem(index: Int) {
        if (mode.value == PlayerMode.DJ) return
        if (index < 0 || index >= exo.mediaItemCount) return
        // Choisir un morceau dans la file rend caduc tout ce qui attendait :
        // sans cette purge, un fondu déjà en préparation s'appliquait
        // PAR-DESSUS ce choix quelques dixièmes de seconde plus tard, et la
        // lecture repartait ailleurs.
        pendingSwitch = null
        stopTail()
        crossfadedFrom = null
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

    /**
     * Répétition en lecture normale : 0 = aucune, 1 = la liste en boucle,
     * 2 = le morceau en boucle. Le bouton fait le tour des trois.
     */
    val repeatMode = MutableStateFlow(0)

    fun cycleRepeat() {
        repeatMode.value = (repeatMode.value + 1) % 3
        appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putInt("repeatMode", repeatMode.value).apply()
        applyRepeat()
    }

    /** Traduit le réglage vers ExoPlayer — hors mix et DJ, qui n'en ont pas. */
    private fun applyRepeat() {
        if (mode.value != PlayerMode.NORMAL && mode.value != PlayerMode.DOUCE) return
        exo.repeatMode = when (repeatMode.value) {
            1 -> Player.REPEAT_MODE_ALL
            2 -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ------------------------------------------------- reprise après arrêt

    /**
     * Sauvegarde d'état et rafraîchissement des widgets, remis à dans un
     * instant et fusionnés s'ils se répètent. Ce travail — photographier le
     * plan complet, construire les RemoteViews — tombait sur le thread
     * principal à l'instant exact des bascules de lecture, précisément là où
     * chaque milliseconde compte pour l'oreille. Rien n'exige qu'il soit
     * fait à la milliseconde : 600 ms plus tard, la bascule est passée.
     */
    private var housekeepingPending = false
    private fun scheduleHousekeeping() {
        if (housekeepingPending) return
        housekeepingPending = true
        handler.postDelayed({
            housekeepingPending = false
            persistState()
            notifyWidgets()
        }, 600L)
    }

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
                // La file VIVANTE, découpée par les bornes de phases — pas
                // le plan d'origine : morceaux programmés « juste après »,
                // réordonnés ou retirés doivent survivre à la reprise,
                // sinon l'index sauvegardé retombe sur la mauvaise chanson.
                val starts = phaseStartIndices
                val phaseUris = it.phases.indices.map { i ->
                    val s = (starts.getOrNull(i) ?: 0)
                        .coerceIn(0, queueTracks.size)
                    val e = (starts.getOrNull(i + 1) ?: queueTracks.size)
                        .coerceIn(s, queueTracks.size)
                    queueTracks.subList(s, e).map { t -> t.uri }
                }
                PlaybackState(
                    mode = m.name,
                    planId = it.id,
                    planName = it.name,
                    planDescription = it.description,
                    phaseNames = it.phases.map { ph -> ph.name },
                    phaseUris = phaseUris,
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
        ioScope.launch(stateWriter) {
            if (state != null) {
                stateStore.save(state)
                // Le mini-fichier de position est réaligné sur l'instantané
                // frais : sans ça, une position périmée (écrite avant un
                // seek ou un remaniement de file) survivait avec un index
                // par hasard concordant et gagnait à la reprise.
                stateStore.savePosition(state.currentIndex, state.positionMs)
            } else {
                stateStore.clear()
            }
        }
    }

    /**
     * Tick périodique : n'écrit QUE {index, position} dans un mini-fichier
     * séparé (voir PlaybackStateStore). L'instantané complet reste celui
     * de la dernière modification de structure ; restore() applique cette
     * position par-dessus si les index concordent toujours.
     */
    private fun persistPositionOnly() {
        if (!initialized) return
        lastSaveMs = System.currentTimeMillis()
        // En DJ, ExoPlayer boucle sur la piste silencieuse : sa position
        // ne veut rien dire, et la reprise DJ repart au début de phase.
        if (mode.value == PlayerMode.DJ) return
        val idx = exo.currentMediaItemIndex
        val pos = exo.currentPosition.coerceAtLeast(0L)
        ioScope.launch(stateWriter) { stateStore.savePosition(idx, pos) }
    }

    /** Restaure la dernière session dès que library.json est chargé. */
    fun scheduleRestore(store: TrackStore) {
        ioScope.launch {
            store.loaded.first { it }
            val saved = stateStore.load() ?: return@launch
            // Position du dernier tick (mini-fichier séparé) : plus fraîche
            // que celle de l'instantané complet, elle prime — mais seulement
            // si la file n'a pas bougé depuis (même index), sinon elle
            // viserait un autre morceau.
            val tick = stateStore.loadPosition()
            val merged = if (tick != null && tick.first == saved.currentIndex)
                saved.copy(positionMs = tick.second.coerceAtLeast(0L))
            else saved
            handler.post { restore(merged, store) }
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
                applyRepeat()
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
                queueTracks = restored.phases.flatMap { it.tracks }
                currentIndex.value = currentTrack.value?.let { cur ->
                    queueTracks.indexOfFirst { it.uri == cur.uri }
                } ?: -1
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
        currentIndex.value = if (idx in queueTracks.indices) idx else -1
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
        scheduleHousekeeping()
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
        // dans une file qui n'existe déjà plus. La queue pré-armée visait le
        // morceau en cours : caduque aussi.
        pendingSwitch = null
        releasePrepared()
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
        // Tout ce qui vit à côté du lecteur principal doit partir avec lui :
        // queue de fondu (second lecteur + égaliseur), queue pré-armée,
        // égaliseur principal, réveils du ticker — sans ça, des AudioEffect
        // et des instances ExoPlayer fuyaient, avec du son fantôme possible
        // après fermeture.
        stopTail()
        releasePrepared()
        try {
            eqExo?.release()
        } catch (_: Exception) {
        }
        eqExo = null
        handler.removeCallbacksAndMessages(null)
        tickerRunning = false
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
