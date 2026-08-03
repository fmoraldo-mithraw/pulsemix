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
import com.pulsemix.app.R

/**
 * Service en avant-plan du réveil matin : démarré par [AlarmReceiver] à
 * la sonnerie, il protège le processus pendant le chargement de la
 * bibliothèque, lance le mix, puis **reste vivant tant que le réveil
 * sonne** avec une notification impossible à balayer.
 *
 * La notification est de catégorie alarme et porte un fullScreenIntent
 * vers [AlarmAlertActivity] : sur écran verrouillé, elle s'affiche en
 * grand. Elle ne disparaît qu'après « Répéter » (10/15/20 min) ou
 * « Arrêter le réveil ».
 */
class AlarmService : Service() {

    companion object {
        private const val CHANNEL_ID = "alarm"
        private const val NOTIF_ID = 3
        const val ACTION_SNOOZE = "com.pulsemix.app.ALARM_SNOOZE"
        const val ACTION_DISMISS = "com.pulsemix.app.ALARM_DISMISS"
        const val EXTRA_MINUTES = "minutes"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, AlarmService::class.java)
            )
        }

        /** Arrête le service (appelé après snooze / arrêt du réveil). */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, AlarmService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private var launched = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            // IMPORTANCE_HIGH : indispensable pour que le fullScreenIntent
            // s'affiche et que la notification passe en pop-up
            val channel = NotificationChannel(
                CHANNEL_ID, "Réveil matin", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Sonnerie du réveil musical"
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                // La musique EST la sonnerie : pas de son de notification
                setSound(null, null)
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground immédiat : obligation liée à startForegroundService
        startForeground(NOTIF_ID, buildNotification())

        when (intent?.action) {
            ACTION_SNOOZE -> {
                val min = intent.getIntExtra(EXTRA_MINUTES, 10)
                AlarmClock.snooze(this, min)
                return START_NOT_STICKY
            }
            ACTION_DISMISS -> {
                AlarmClock.dismiss(this)
                return START_NOT_STICKY
            }
        }

        if (!launched) {
            launched = true
            // Le service reste en vie après le lancement : c'est lui qui
            // porte la notification tant que le réveil sonne
            AlarmClock.launchNow(this) {
                try {
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIF_ID, buildNotification())
                } catch (_: Exception) {
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun snoozeIntent(minutes: Int): PendingIntent =
        PendingIntent.getService(
            this, 1000 + minutes,
            Intent(this, AlarmService::class.java)
                .setAction(ACTION_SNOOZE)
                .putExtra(EXTRA_MINUTES, minutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildNotification(): Notification {
        val full = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmAlertActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismiss = PendingIntent.getService(
            this, 999,
            Intent(this, AlarmService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val track = PlayerCore.currentTrack.value
        val text = track?.let {
            listOf(it.title, it.artist).filter(String::isNotBlank)
                .joinToString(" — ")
        } ?: "Lancement de la musique…"

        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Réveil PulseMix")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(full)
            // Plein écran sur écran verrouillé, comme une vraie alarme
            .setFullScreenIntent(full, true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Impossible à balayer : seuls les boutons la retirent
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
        for (min in AlarmClock.SNOOZE_CHOICES) {
            b.addAction(0, "+$min min", snoozeIntent(min))
        }
        b.addAction(0, "Arrêter", dismiss)
        return b.build()
    }
}
