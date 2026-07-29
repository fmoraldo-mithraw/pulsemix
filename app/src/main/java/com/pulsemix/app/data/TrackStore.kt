package com.pulsemix.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Un morceau de la bibliothèque, avec ses caractéristiques analysées. */
data class Track(
    val uri: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val bpm: Float = 0f,
    val keyName: String = "--",
    val camelot: String = "--",
    val energyMean: Float = 0f,
    val energyPeak: Float = 0f,
    val centroid: Float = 0f,
    val onsetRate: Float = 0f,
    val bestStartMs: Long = 0L,
    val segmentMs: Long = 60_000L,
    val firstBeatMs: Long = 0L,
    val analyzed: Boolean = false
)

/**
 * Persistance simple de la bibliothèque dans un fichier JSON interne
 * (pas de base de données : peu de données, lecture/écriture rapides).
 */
class TrackStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "library.json")
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks

    private val _folderUri = MutableStateFlow<String?>(null)
    val folderUri: StateFlow<String?> = _folderUri

    init {
        scope.launch { load() }
    }

    private suspend fun load() = mutex.withLock {
        if (!file.exists()) return@withLock
        try {
            val root = JSONObject(file.readText())
            _folderUri.value = root.optString("folder").takeIf { it.isNotEmpty() }
            val arr = root.optJSONArray("tracks") ?: JSONArray()
            val list = ArrayList<Track>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Track(
                        uri = o.getString("uri"),
                        title = o.optString("title", "?"),
                        artist = o.optString("artist", ""),
                        durationMs = o.optLong("durationMs", 0L),
                        bpm = o.optDouble("bpm", 0.0).toFloat(),
                        keyName = o.optString("keyName", "--"),
                        camelot = o.optString("camelot", "--"),
                        energyMean = o.optDouble("energyMean", 0.0).toFloat(),
                        energyPeak = o.optDouble("energyPeak", 0.0).toFloat(),
                        centroid = o.optDouble("centroid", 0.0).toFloat(),
                        onsetRate = o.optDouble("onsetRate", 0.0).toFloat(),
                        bestStartMs = o.optLong("bestStartMs", 0L),
                        segmentMs = o.optLong("segmentMs", 60_000L),
                        firstBeatMs = o.optLong("firstBeatMs", 0L),
                        analyzed = o.optBoolean("analyzed", false)
                    )
                )
            }
            _tracks.value = list.sortedBy { it.title.lowercase() }
        } catch (_: Exception) {
        }
    }

    suspend fun save() = mutex.withLock {
        try {
            val root = JSONObject()
            root.put("folder", _folderUri.value ?: "")
            val arr = JSONArray()
            for (t in _tracks.value) {
                val o = JSONObject()
                o.put("uri", t.uri)
                o.put("title", t.title)
                o.put("artist", t.artist)
                o.put("durationMs", t.durationMs)
                o.put("bpm", t.bpm.toDouble())
                o.put("keyName", t.keyName)
                o.put("camelot", t.camelot)
                o.put("energyMean", t.energyMean.toDouble())
                o.put("energyPeak", t.energyPeak.toDouble())
                o.put("centroid", t.centroid.toDouble())
                o.put("onsetRate", t.onsetRate.toDouble())
                o.put("bestStartMs", t.bestStartMs)
                o.put("segmentMs", t.segmentMs)
                o.put("firstBeatMs", t.firstBeatMs)
                o.put("analyzed", t.analyzed)
                arr.put(o)
            }
            root.put("tracks", arr)
            file.writeText(root.toString())
        } catch (_: Exception) {
        }
    }

    fun setFolder(uri: String) {
        _folderUri.value = uri
    }

    /** Ajoute ou remplace un morceau (clé = uri). */
    fun put(track: Track) {
        val list = _tracks.value.toMutableList()
        val idx = list.indexOfFirst { it.uri == track.uri }
        if (idx >= 0) list[idx] = track else list.add(track)
        _tracks.value = list.sortedBy { it.title.lowercase() }
    }

    fun get(uri: String): Track? = _tracks.value.firstOrNull { it.uri == uri }

    /** Supprime les morceaux qui ne sont plus dans le dossier. */
    fun retainOnly(uris: Set<String>) {
        _tracks.value = _tracks.value.filter { it.uri in uris }
    }
}
