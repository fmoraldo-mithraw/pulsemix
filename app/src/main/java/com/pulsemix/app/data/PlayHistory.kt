package com.pulsemix.app.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Historique de lecture (uri -> dernier passage) : sert d'anti-répétition
 * dans la génération des mix, entre sessions.
 */
object PlayHistory {

    private const val MAX = 400
    private val map = ConcurrentHashMap<String, Long>()
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences("play_history", Context.MODE_PRIVATE)
        prefs = p
        for ((k, v) in p.all) if (v is Long) map[k] = v
    }

    fun record(uri: String) {
        val now = System.currentTimeMillis()
        map[uri] = now
        prefs?.edit()?.putLong(uri, now)?.apply()
        if (map.size > MAX) {
            val oldest = map.entries.sortedBy { it.value }.take(map.size - MAX + 40)
            val editor = prefs?.edit()
            for (e in oldest) {
                map.remove(e.key)
                editor?.remove(e.key)
            }
            editor?.apply()
        }
    }

    /** Export/import pour la sauvegarde dans le dossier de musique. */
    fun export(): Map<String, Long> = HashMap(map)

    fun import(entries: Map<String, Long>) {
        val editor = prefs?.edit()
        for ((k, v) in entries) {
            if (k !in map) {
                map[k] = v
                editor?.putLong(k, v)
            }
        }
        editor?.apply()
    }

    /** 1,0 = joué à l'instant, 0 = il y a 48 h ou plus (ou jamais). */
    fun penalty(uri: String): Float {
        val t = map[uri] ?: return 0f
        val h48 = 48L * 3600_000L
        return ((h48 - (System.currentTimeMillis() - t)).toFloat() / h48)
            .coerceIn(0f, 1f)
    }
}
