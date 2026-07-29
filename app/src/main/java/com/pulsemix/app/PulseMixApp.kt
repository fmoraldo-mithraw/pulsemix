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
        if (!::store.isInitialized) {
            store = TrackStore(context)
        }
        PlayerCore.init(context)
    }
}

class PulseMixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
