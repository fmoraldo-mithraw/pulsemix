package com.pulsemix.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pulsemix.app.MainActivity
import com.pulsemix.app.R

/**
 * Service en avant-plan éphémère du réveil matin : démarré par
 * [AlarmReceiver] dès la sonnerie, il protège le processus pendant le
 * chargement de la bibliothèque et le lancement du mix (un broadcast
 * seul n'est garanti que ~10 s). Il s'arrête sitôt la lecture lancée —
 * le service de lecture ([PlaybackService]) prend alors le relais avec
 * sa propre notification média.
 */
class AlarmService : Service() {

    companion object {
        private const val CHANNEL_ID = "alarm"
        private const val NOTIF_ID = 3

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, AlarmService::class.java)
            )
        }
    }

    private var launched = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Réveil matin",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground immédiat : obligation liée à startForegroundService
        startForeground(NOTIF_ID, buildNotification())
        if (!launched) {
            launched = true
            AlarmClock.launchNow(this) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Réveil PulseMix")
            .setContentText("Lancement de la musique…")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
