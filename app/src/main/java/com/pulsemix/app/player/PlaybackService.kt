package com.pulsemix.app.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.pulsemix.app.MainActivity

/**
 * Service de lecture : expose une MediaSession (notification média, casque,
 * commandes Bluetooth AVRCP). Les commandes next/previous — qu'elles viennent
 * du Bluetooth, du casque ou de la notification — passent par le
 * ForwardingPlayer et sont routées vers [PlayerCore], qui applique la
 * sémantique du mode courant (morceau suivant, ou phase suivante en Mix/DJ).
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

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

        val sessionIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, forwarding)
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
