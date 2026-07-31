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
 */
class PlaybackStateStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "playback_state.json")

    fun save(state: PlaybackState) {
        try {
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

            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(o.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun load(): PlaybackState? {
        if (!file.exists()) return null
        return try {
            val o = JSONObject(file.readText())
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

    fun clear() {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }
}
