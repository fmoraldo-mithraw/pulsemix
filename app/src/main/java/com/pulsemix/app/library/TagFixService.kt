package com.pulsemix.app.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.pulsemix.app.Graph
import com.pulsemix.app.MainActivity
import com.pulsemix.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Service en avant-plan pour la correction des tags en ligne : le passage
 * de la bibliothèque continue appli quittée ou écran éteint, avec une
 * notification de progression.
 *
 * Sans lui, la vérification vivait dans le ViewModel : quitter l'écran ou
 * l'appli l'arrêtait net — gênant pour un travail limité à ~1 morceau par
 * seconde par la politesse envers AcoustID et MusicBrainz, donc long sur
 * une vraie bibliothèque. Le wake lock garde le CPU actif écran éteint :
 * chaque morceau est décodé pour son empreinte sonore.
 */
class TagFixService : Service() {

    companion object {
        private const val EXTRA_FORCE = "force"
        private const val EXTRA_WRITE_ALL = "writeAll"
        private const val EXTRA_COVERS = "covers"
        private const val ACTION_STOP = "com.pulsemix.app.tagfix.STOP"
        private const val CHANNEL_ID = "tagfix"
        private const val NOTIF_ID = 3

        /**
         * @param writeAll true pour reporter les tags de la bibliothèque
         * dans les fichiers, au lieu de chercher des corrections en ligne.
         * @param covers true pour récupérer les jaquettes manquantes de
         * toute la bibliothèque, au lieu de corriger les tags.
         */
        fun start(
            context: Context,
            force: Boolean = false,
            writeAll: Boolean = false,
            covers: Boolean = false
        ) {
            val i = Intent(context, TagFixService::class.java)
                .putExtra(EXTRA_FORCE, force)
                .putExtra(EXTRA_WRITE_ALL, writeAll)
                .putExtra(EXTRA_COVERS, covers)
            ContextCompat.startForegroundService(context, i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Correction des tags",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground immédiat : obligation liée à startForegroundService
        startForeground(NOTIF_ID, buildNotification("Préparation…", 0, 0))

        if (intent?.action == ACTION_STOP) {
            TagFixer.requestStop()
            return START_NOT_STICKY
        }

        if (running) return START_NOT_STICKY // un passage tourne déjà
        running = true

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pulsemix:tagfix")
            .apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L) // garde-fou : 6 h max
            }

        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
        val writeAll = intent?.getBooleanExtra(EXTRA_WRITE_ALL, false) ?: false
        val covers = intent?.getBooleanExtra(EXTRA_COVERS, false) ?: false
        scope.launch {
            val notifJob = launch {
                when {
                    covers -> TagFixer.coverProgress.collect { p ->
                        if (p != null) {
                            val (done, total) = p
                            notify(
                                buildNotification(
                                    "Jaquettes $done/$total", done, total
                                )
                            )
                        }
                    }
                    writeAll -> TagFixer.writeProgress.collect { p ->
                        if (p != null) {
                            val (done, total) = p
                            notify(
                                buildNotification(
                                    "Écriture des tags $done/$total", done, total
                                )
                            )
                        }
                    }
                    else -> TagFixer.progress.collect { p ->
                        if (p != null) {
                            val (done, total, applied) = p
                            notify(
                                buildNotification(
                                    "Tags $done/$total — $applied corrigés",
                                    done, total
                                )
                            )
                        }
                    }
                }
            }
            try {
                val store = Graph.store
                store.loaded.first { it }
                when {
                    covers -> TagFixer.fetchAllCovers(store)
                    writeAll -> TagFixer.writeAllToFiles(store)
                    else -> TagFixer.fixAll(store, force)
                }
            } finally {
                notifJob.cancel()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String, done: Int, total: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, TagFixService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("PulseMix — correction des tags")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?, "Arrêter", stopPi
                ).build()
            )
        if (total > 0) b.setProgress(total, done, false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }
}
