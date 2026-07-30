package com.pulsemix.app

import android.app.Application
import android.content.Context
import com.pulsemix.app.data.PlayHistory
import com.pulsemix.app.data.TrackStore
import com.pulsemix.app.player.PlayerCore

/** Graphe de dépendances minimaliste. */
object Graph {
    lateinit var store: TrackStore
        private set

    fun init(context: Context) {
        val firstInit = !::store.isInitialized
        if (firstInit) {
            store = TrackStore(context)
            PlayHistory.init(context)
        }
        PlayerCore.init(context)
        // Reprendre la dernière session (morceau + position) après une
        // fermeture, une mise en veille ou un plantage.
        if (firstInit) PlayerCore.scheduleRestore(store)
    }
}

class PulseMixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        logLastExitReasons()
        Graph.init(this)
    }

    /**
     * Écrit dans exit_log.txt la raison des dernières morts du process
     * (crash natif, ANR, kill mémoire...) : couvre les fermetures inopinées
     * qui ne passent pas par le handler d'exceptions Java.
     */
    private fun logLastExitReasons() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        try {
            val am = getSystemService(android.app.ActivityManager::class.java) ?: return
            val exits = am.getHistoricalProcessExitReasons(packageName, 0, 3)
            if (exits.isEmpty()) return
            val sb = StringBuilder()
            for (info in exits) {
                sb.append(java.text.DateFormat.getDateTimeInstance()
                    .format(java.util.Date(info.timestamp)))
                sb.append(" — ").append(exitReasonName(info.reason))
                sb.append(" (status ").append(info.status).append(")")
                info.description?.let { sb.append(" : ").append(it) }
                sb.append('\n')
                if (info.reason == android.app.ApplicationExitInfo.REASON_ANR ||
                    info.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
                ) {
                    try {
                        info.traceInputStream?.bufferedReader()?.use { r ->
                            sb.append(r.readText().take(20_000)).append('\n')
                        }
                    } catch (_: Exception) {
                    }
                }
                sb.append('\n')
            }
            for (dir in listOfNotNull(filesDir, getExternalFilesDir(null))) {
                java.io.File(dir, "exit_log.txt").writeText(sb.toString())
            }
        } catch (_: Exception) {
        }
    }

    private fun exitReasonName(r: Int): String = when (r) {
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR (interface bloquée)"
        android.app.ApplicationExitInfo.REASON_CRASH -> "crash Java"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash natif"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "tué : mémoire insuffisante"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "tué par signal"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "ressources excessives"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "fermé par l'utilisateur"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "stoppé par l'utilisateur"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dépendance morte"
        android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "sortie volontaire"
        android.app.ApplicationExitInfo.REASON_OTHER -> "autre (système)"
        else -> "raison $r"
    }

    /**
     * Écrit la trace de tout plantage dans crash_log.txt (dossier interne +
     * Android/data/com.pulsemix.app/files, lisible via USB) pour pouvoir
     * diagnostiquer les crashs sur l'appareil.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val log = buildString {
                    append(java.text.DateFormat.getDateTimeInstance()
                        .format(java.util.Date()))
                    append(" — thread ").append(thread.name).append('\n')
                    append(android.util.Log.getStackTraceString(e))
                }
                for (dir in listOfNotNull(filesDir, getExternalFilesDir(null))) {
                    java.io.File(dir, "crash_log.txt").writeText(log)
                }
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, e)
        }
    }
}
