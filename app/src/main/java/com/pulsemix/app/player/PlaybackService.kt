package com.pulsemix.app.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.pulsemix.app.MainActivity
import com.pulsemix.app.R

/**
 * Service de lecture : expose une MediaSession (notification média, casque,
 * commandes Bluetooth AVRCP). Les commandes next/previous — qu'elles viennent
 * du Bluetooth, du casque ou de la notification — passent par le
 * ForwardingPlayer et sont routées vers [PlayerCore], qui applique la
 * sémantique du mode courant (morceau suivant, ou phase suivante en Mix/DJ).
 */
class PlaybackService : MediaSessionService() {

    companion object {
        // Même ID que la notification auto de Media3 : si elle finit par
        // être postée, elle remplace la nôtre au lieu de se dupliquer.
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "playback"
        private const val ACTION_PREV = "com.pulsemix.app.PREV"
        private const val ACTION_TOGGLE = "com.pulsemix.app.TOGGLE"
        private const val ACTION_NEXT = "com.pulsemix.app.NEXT"
    }

    private var mediaSession: MediaSession? = null
    private var sessionIntent: PendingIntent? = null

    // Notification média maison : sur certains appareils Media3 ne poste
    // jamais la sienne (onUpdateNotification jamais appelé, cf. service_log).
    // On construit donc nous-mêmes la notification à partir de l'état du
    // lecteur — titre + précédent / play-pause / suivant, style média
    // rattaché à la session (affichage écran verrouillé).
    private val notifListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updateNotification()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) =
            updateNotification()

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) =
            updateNotification()
    }

    private fun actionIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun updateNotification() {
        val session = mediaSession ?: return
        val player = session.player
        if (player.mediaItemCount == 0) return
        val playing = player.isPlaying
        val meta = player.mediaMetadata
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(meta.title?.toString()?.ifBlank { null } ?: "PulseMix")
            .setContentText(meta.artist?.toString() ?: "")
            .setContentIntent(sessionIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_previous, "Précédent",
                    actionIntent(ACTION_PREV)
                )
            )
            .addAction(
                if (playing) NotificationCompat.Action(
                    android.R.drawable.ic_media_pause, "Pause",
                    actionIntent(ACTION_TOGGLE)
                ) else NotificationCompat.Action(
                    android.R.drawable.ic_media_play, "Lecture",
                    actionIntent(ACTION_TOGGLE)
                )
            )
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_next, "Suivant",
                    actionIntent(ACTION_NEXT)
                )
            )
            .setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
        try {
            if (playing) {
                startForeground(NOTIF_ID, notif)
            } else {
                stopForeground(STOP_FOREGROUND_DETACH)
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIF_ID, notif)
            }
            svcLog("notification postée (playing=$playing)")
        } catch (e: Exception) {
            svcLog("notification ÉCHEC : ${e::class.java.simpleName} ${e.message}")
        }
    }

    /** Journal du service média : service_log.txt (interne + externe,
     *  comme crash_log.txt — visible dans Android/data/.../files). */
    private fun svcLog(message: String) {
        try {
            for (dir in listOfNotNull(filesDir, getExternalFilesDir(null))) {
                val f = java.io.File(dir, "service_log.txt")
                if (f.length() > 64_000) f.delete()
                f.appendText("${java.util.Date()}: $message\n")
            }
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        svcLog("onStartCommand (intent=${intent?.action})")
        when (intent?.action) {
            ACTION_PREV -> PlayerCore.previous()
            ACTION_TOGGLE -> PlayerCore.togglePlayPause()
            ACTION_NEXT -> PlayerCore.next()
        }
        updateNotification()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean
    ) {
        svcLog(
            "onUpdateNotification fg=$startInForegroundRequired " +
                "playing=${session.player.isPlaying} " +
                "items=${session.player.mediaItemCount}"
        )
        try {
            super.onUpdateNotification(session, startInForegroundRequired)
        } catch (e: Exception) {
            svcLog("onUpdateNotification ÉCHEC: ${e::class.java.simpleName} ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        PlayerCore.init(applicationContext)

        // Fournisseur de notification média explicite (titre + play/pause +
        // suivant/précédent) : ne pas dépendre du comportement par défaut.
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .build()
        )

        val forwarding = object : ForwardingPlayer(PlayerCore.exo) {
            // En DJ, ExoPlayer boucle sur une piste silencieuse minuscule :
            // la barre de progression de la notification affichait 0:00-0:00
            // en frétillant. On rapporte à la place la progression réelle du
            // passage DJ en cours (position dans le meilleur passage).
            private fun djDurationMs(): Long? =
                if (PlayerCore.mode.value == PlayerMode.DJ)
                    PlayerCore.currentTrack.value?.segmentMs?.coerceAtLeast(1L)
                else null

            override fun getDuration(): Long =
                djDurationMs() ?: super.getDuration()

            override fun getContentDuration(): Long =
                djDurationMs() ?: super.getContentDuration()

            override fun getCurrentPosition(): Long =
                djDurationMs()?.let {
                    (PlayerCore.progress.value * it).toLong().coerceIn(0L, it)
                } ?: super.getCurrentPosition()

            override fun getContentPosition(): Long =
                djDurationMs()?.let {
                    (PlayerCore.progress.value * it).toLong().coerceIn(0L, it)
                } ?: super.getContentPosition()

            override fun getBufferedPosition(): Long =
                djDurationMs() ?: super.getBufferedPosition()

            override fun isCurrentMediaItemSeekable(): Boolean =
                if (PlayerCore.mode.value == PlayerMode.DJ) false
                else super.isCurrentMediaItemSeekable()

            override fun seekToNext() {
                PlayerCore.next()
            }

            override fun seekToNextMediaItem() {
                PlayerCore.next()
            }

            override fun seekToPrevious() {
                PlayerCore.previous()
            }

            override fun seekToPreviousMediaItem() {
                PlayerCore.previous()
            }

            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .addAll(
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                    )
                    .build()

            override fun isCommandAvailable(command: Int): Boolean =
                getAvailableCommands().contains(command)
        }

        val si = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        sessionIntent = si

        mediaSession = try {
            buildSession(forwarding, si)
                .also { svcLog("onCreate : session média créée") }
        } catch (e: Exception) {
            svcLog("onCreate : ÉCHEC création session — ${e::class.java.simpleName} ${e.message}")
            null
        }

        // Canal + notification maison (voir notifListener)
        try {
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "Lecture en cours",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        } catch (_: Exception) {
        }
        PlayerCore.exo.addListener(notifListener)
        updateNotification()
    }

    private fun buildSession(
        forwarding: Player,
        sessionIntent: PendingIntent
    ): MediaSession =
        MediaSession.Builder(this, forwarding)
            .setSessionActivity(sessionIntent)
            .setCallback(object : MediaSession.Callback {
                // « Play » depuis la voiture / le casque alors que rien n'est
                // chargé : reprendre la file restaurée (reprise de session).
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val exo = PlayerCore.exo
                    val items = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it) }
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            items,
                            exo.currentMediaItemIndex.coerceAtLeast(0),
                            exo.currentPosition.coerceAtLeast(0L)
                        )
                    )
                }
            })
            .build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        try {
            PlayerCore.exo.removeListener(notifListener)
        } catch (_: Exception) {
        }
        try {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
