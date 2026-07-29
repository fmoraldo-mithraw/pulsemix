package com.pulsemix.app

import android.app.Application
import android.content.Context
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
        Graph.init(this)
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
