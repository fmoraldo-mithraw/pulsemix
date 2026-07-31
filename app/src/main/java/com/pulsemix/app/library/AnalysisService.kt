package com.pulsemix.app.library

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * Service en avant-plan pour l'analyse de la bibliothèque : l'analyse
 * continue même quand l'appli est quittée ou l'écran éteint, avec une
 * notification de progression. Un wake lock partiel garde le CPU actif
 * pendant le décodage écran éteint.
 */
class AnalysisService : Service() {

    companion object {
        private const val EXTRA_FROM_SCRATCH = "fromScratch"
        private const val ACTION_STOP = "com.pulsemix.app.analysis.STOP"
        private const val CHANNEL_ID = "analysis"
        private const val NOTIF_ID = 2

        fun start(context: Context, fromScratch: Boolean = false) {
            val i = Intent(context, AnalysisService::class.java)
                .putExtra(EXTRA_FROM_SCRATCH, fromScratch)
            ContextCompat.startForegroundService(context, i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Analyse de la bibliothèque",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground immédiat : obligation liée à startForegroundService
        startForeground(NOTIF_ID, buildNotification("Préparation de l'analyse…", 0, 0))

        // Bouton « Arrêter » de la notification : arrêt propre, le service se
        // termine dès que le scan a lâché le fichier en cours.
        if (intent?.action == ACTION_STOP) {
            LibraryScanner.requestStop()
            return START_NOT_STICKY
        }

        if (running) return START_NOT_STICKY // une analyse tourne déjà
        running = true

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pulsemix:analysis")
            .apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L) // garde-fou : 6 h max
            }

        val fromScratch = intent?.getBooleanExtra(EXTRA_FROM_SCRATCH, false) ?: false
        scope.launch {
            val notifJob = launch {
                LibraryScanner.progress.collect { p ->
                    if (p != null) {
                        val text =
                            if (p.total > 0) "Analyse ${p.done}/${p.total} — ${p.currentName}"
                            else "Préparation — parcours des dossiers…"
                        notify(buildNotification(text, p.done, p.total))
                    }
                }
            }
            try {
                val store = Graph.store
                store.loaded.first { it }
                val folders = store.folders.value.map { Uri.parse(it) }
                if (folders.isNotEmpty()) {
                    if (fromScratch) {
                        store.resetAnalysis()
                        store.save()
                    }
                    LibraryScanner.scan(
                        applicationContext, folders, store,
                        restoreBackup = !fromScratch
                    )
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
            Intent(this, AnalysisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("PulseMix — analyse")
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
