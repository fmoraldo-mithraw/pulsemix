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

        /** Canal de la notification de réveil (idempotent). IMPORTANCE_HIGH :
         *  indispensable pour que le fullScreenIntent s'affiche et que la
         *  notification passe en pop-up. */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
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
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }

        /** Poste (ou rafraîchit) la notification de réveil SANS service :
         *  utilisable depuis le receveur d'alarme, quand le service en
         *  avant-plan est refusé. */
        fun postNotification(context: Context) {
            try {
                ensureChannel(context)
                context.getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIF_ID, buildNotification(context))
            } catch (_: Exception) {
            }
        }

        fun cancelNotification(context: Context) {
            try {
                context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
            } catch (_: Exception) {
            }
        }

        private fun snoozeIntent(context: Context, minutes: Int): PendingIntent =
            PendingIntent.getService(
                context, 1000 + minutes,
                Intent(context, AlarmService::class.java)
                    .setAction(ACTION_SNOOZE)
                    .putExtra(EXTRA_MINUTES, minutes),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        fun buildNotification(context: Context): Notification {
            val full = PendingIntent.getActivity(
                context, 0,
                Intent(context, AlarmAlertActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val dismiss = PendingIntent.getService(
                context, 999,
                Intent(context, AlarmService::class.java).setAction(ACTION_DISMISS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val track = PlayerCore.currentTrack.value
            val text = track?.let {
                listOf(it.title, it.artist).filter(String::isNotBlank)
                    .joinToString(" — ")
            } ?: "Lancement de la musique…"

            val b = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(AlarmClock.ringLabel.value)
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
                b.addAction(0, "+$min min", snoozeIntent(context, min))
            }
            b.addAction(0, "Arrêter", dismiss)
            return b.build()
        }
    }

    private var launched = false

    override fun onBind(intent: Intent?): IBinder? = null

    private fun log(message: String) {
        try {
            PlayerCore.engineLog("Réveil", message)
        } catch (_: Exception) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        log("service : onCreate")
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("service : onStartCommand (${intent?.action ?: "sonnerie"})")
        // startForeground immédiat : obligation liée à startForegroundService.
        // Android 12+ peut le REFUSER (ForegroundServiceStartNotAllowed…)
        // quand l'appli est en arrière-plan et que l'exemption d'alarme
        // exacte n'est pas reconnue : l'exception tuait le processus
        // (journal 14 : plus rien après « alarme programmée »). On la
        // journalise et on lance la musique quand même — le service
        // média de la lecture prend ensuite son propre avant-plan.
        try {
            startForeground(NOTIF_ID, buildNotification(this))
        } catch (e: Exception) {
            log("service : avant-plan refusé : ${e::class.java.simpleName} ${e.message?.take(160)}")
            try {
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIF_ID, buildNotification(this))
            } catch (_: Exception) {
            }
        }

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
            log("service : lancement de la musique")
            AlarmClock.launchOnce(this, "service") {
                try {
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIF_ID, buildNotification(this))
                } catch (_: Exception) {
                }
            }
        }
        return START_NOT_STICKY
    }
}
