package com.pulsemix.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Instantané de la lecture en cours, pour reprendre après fermeture, veille ou plantage. */
data class PlaybackState(
    val mode: String,
    val queueUris: List<String> = emptyList(),
    val planId: String? = null,
    val planName: String? = null,
    val planDescription: String? = null,
    val phaseNames: List<String> = emptyList(),
    val phaseUris: List<List<String>> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val currentPhase: Int = 0,
    val shuffle: Boolean = false
)

/**
 * Persistance de l'état de lecture dans un fichier JSON interne, écrit de
 * façon atomique (fichier temporaire puis rename) pour qu'un arrêt brutal en
 * pleine écriture ne laisse jamais un fichier corrompu.
 *
 * Deux fichiers, deux cadences : l'instantané COMPLET (file, plan, phases)
 * n'est écrit que quand la structure change, tandis que la position de
 * lecture — la seule chose qui bouge toutes les quelques secondes — vit dans
 * un mini-fichier séparé. Sérialiser toute la file toutes les 5 s pendant la
 * lecture, c'était ~70 Mo/h d'écritures flash pour deux nombres qui changent.
 */
class PlaybackStateStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "playback_state.json")
    private val posFile = File(context.applicationContext.filesDir, "playback_pos.json")

    companion object {
        /**
         * Sérialisation pure (sans Context) : séparée de l'écriture disque
         * pour être testable en JVM (aller-retour encode/decode).
         */
        internal fun encode(state: PlaybackState): String {
            val o = JSONObject()
            o.put("mode", state.mode)
            o.put("queue", JSONArray(state.queueUris))
            o.put("planId", state.planId ?: "")
            o.put("planName", state.planName ?: "")
            o.put("planDesc", state.planDescription ?: "")
            val phases = JSONArray()
            for (i in state.phaseNames.indices) {
                val p = JSONObject()
                p.put("name", state.phaseNames[i])
                p.put("uris", JSONArray(state.phaseUris.getOrElse(i) { emptyList<String>() }))
                phases.put(p)
            }
            o.put("phases", phases)
            o.put("index", state.currentIndex)
            o.put("positionMs", state.positionMs)
            o.put("phase", state.currentPhase)
            o.put("shuffle", state.shuffle)
            return o.toString()
        }

        internal fun decode(text: String): PlaybackState? {
            return try {
                val o = JSONObject(text)
                val queue = ArrayList<String>()
                val queueArr = o.optJSONArray("queue") ?: JSONArray()
                for (i in 0 until queueArr.length()) queue.add(queueArr.getString(i))

                val phaseNames = ArrayList<String>()
                val phaseUris = ArrayList<List<String>>()
                val phasesArr = o.optJSONArray("phases") ?: JSONArray()
                for (i in 0 until phasesArr.length()) {
                    val p = phasesArr.getJSONObject(i)
                    phaseNames.add(p.optString("name", "?"))
                    val uris = ArrayList<String>()
                    val urisArr = p.optJSONArray("uris") ?: JSONArray()
                    for (j in 0 until urisArr.length()) uris.add(urisArr.getString(j))
                    phaseUris.add(uris)
                }

                PlaybackState(
                    mode = o.getString("mode"),
                    queueUris = queue,
                    planId = o.optString("planId").takeIf { it.isNotEmpty() },
                    planName = o.optString("planName").takeIf { it.isNotEmpty() },
                    planDescription = o.optString("planDesc").takeIf { it.isNotEmpty() },
                    phaseNames = phaseNames,
                    phaseUris = phaseUris,
                    currentIndex = o.optInt("index", 0),
                    positionMs = o.optLong("positionMs", 0L),
                    currentPhase = o.optInt("phase", 0),
                    shuffle = o.optBoolean("shuffle", false)
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun save(state: PlaybackState) {
        try {
            writeAtomic(file, encode(state))
        } catch (_: Exception) {
        }
    }

    fun load(): PlaybackState? {
        if (!file.exists()) return null
        return try {
            decode(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Tick périodique de position : seuls l'index et la position bougent,
     * on n'écrit qu'eux. [restore][load] applique cette position par-dessus
     * l'instantané complet si les index concordent encore.
     */
    fun savePosition(index: Int, positionMs: Long) {
        try {
            val o = JSONObject()
            o.put("index", index)
            o.put("positionMs", positionMs)
            writeAtomic(posFile, o.toString())
        } catch (_: Exception) {
        }
    }

    /** @return (index, positionMs) du dernier tick, ou null. */
    fun loadPosition(): Pair<Int, Long>? {
        if (!posFile.exists()) return null
        return try {
            val o = JSONObject(posFile.readText())
            o.getInt("index") to o.getLong("positionMs")
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        try {
            file.delete()
            posFile.delete()
        } catch (_: Exception) {
        }
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }
}
