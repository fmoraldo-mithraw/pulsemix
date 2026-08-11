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

    /** Nombre total de lectures par morceau (jamais purgé, ~1 Int/morceau). */
    private val counts = ConcurrentHashMap<String, Int>()
    private var countPrefs: SharedPreferences? = null
    @Volatile private var avgCache = -1f

    fun init(context: Context) {
        val p = context.getSharedPreferences("play_history", Context.MODE_PRIVATE)
        prefs = p
        for ((k, v) in p.all) if (v is Long) map[k] = v
        val c = context.getSharedPreferences("play_counts", Context.MODE_PRIVATE)
        countPrefs = c
        for ((k, v) in c.all) if (v is Int) counts[k] = v
    }

    fun record(uri: String) {
        val now = System.currentTimeMillis()
        map[uri] = now
        prefs?.edit()?.putLong(uri, now)?.apply()
        val n = (counts[uri] ?: 0) + 1
        counts[uri] = n
        countPrefs?.edit()?.putInt(uri, n)?.apply()
        avgCache = -1f
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

    /** Nombre de lectures du morceau (0 si jamais joué). */
    fun count(uri: String): Int = counts[uri] ?: 0

    /**
     * Dernière lecture du morceau (ms epoch), null si jamais vu. Persistant
     * (mis à jour par [record]) — mais borné à [MAX] entrées : un morceau
     * pas joué depuis très longtemps peut avoir été purgé, ce qui revient
     * au même pour les règles « pas joué depuis N jours ».
     */
    fun lastPlayed(uri: String): Long? = map[uri]

    /**
     * 0 = dans la norme, → 1 = beaucoup trop joué par rapport au reste de
     * la bibliothèque. Le malus démarre à 1,5× la moyenne des morceaux
     * joués et sature à 4× : un morceau sur-joué laisse la place aux
     * autres sans devenir interdit.
     */
    fun overplayPenalty(uri: String): Float {
        val c = counts[uri] ?: return 0f
        // Trop peu de données : ne rien pénaliser
        if (c < 3 || counts.size < 5) return 0f
        var avg = avgCache
        if (avg < 0f) {
            var sum = 0L
            for (v in counts.values) sum += v
            avg = (sum.toFloat() / counts.size).coerceAtLeast(1f)
            avgCache = avg
        }
        return ((c / avg - 1.5f) / 2.5f).coerceIn(0f, 1f)
    }

    /** Export/import pour la sauvegarde dans le dossier de musique. */
    fun export(): Map<String, Long> = HashMap(map)

    fun import(entries: Map<String, Long>) {
        val editor = prefs?.edit()
        for ((k, v) in entries) {
            if (!map.containsKey(k)) {
                map[k] = v
                editor?.putLong(k, v)
            }
        }
        editor?.apply()
    }

    fun exportCounts(): Map<String, Int> = HashMap(counts)

    fun importCounts(entries: Map<String, Int>) {
        val editor = countPrefs?.edit()
        for ((k, v) in entries) {
            if ((counts[k] ?: 0) < v) {
                counts[k] = v
                editor?.putInt(k, v)
            }
        }
        editor?.apply()
        avgCache = -1f
    }

    /** 1,0 = joué à l'instant, 0 = il y a 48 h ou plus (ou jamais). */
    fun penalty(uri: String): Float {
        val t = map[uri] ?: return 0f
        val h48 = 48L * 3600_000L
        return ((h48 - (System.currentTimeMillis() - t)).toFloat() / h48)
            .coerceIn(0f, 1f)
    }
}
