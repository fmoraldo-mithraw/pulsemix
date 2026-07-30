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
    /** Début réel de la musique (saut des intros parlées/sketchs). */
    val musicStartMs: Long = 0L,
    val analyzed: Boolean = false,
    /** Mis en avant dans les sélections de mix. */
    val favorite: Boolean = false,
    /** Jamais sélectionné par les mix / la douce (reste jouable en Normal). */
    val excluded: Boolean = false,
    /** BPM corrigé à la main : protégé contre la réanalyse. */
    val bpmLocked: Boolean = false
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

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    /** Dossiers de musique (multi-dossiers). */
    val folders: StateFlow<List<String>> = _folders

    /** Passe à true une fois library.json lu (utile pour restaurer la lecture). */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    init {
        scope.launch {
            load()
            _loaded.value = true
        }
    }

    private suspend fun load() = mutex.withLock {
        if (!file.exists()) return@withLock
        try {
            val root = JSONObject(file.readText())
            val folderList = ArrayList<String>()
            val fArr = root.optJSONArray("folders")
            if (fArr != null) {
                for (i in 0 until fArr.length()) folderList.add(fArr.getString(i))
            } else {
                // ancien format : un seul dossier
                root.optString("folder").takeIf { it.isNotEmpty() }?.let { folderList.add(it) }
            }
            _folders.value = folderList
            val arr = root.optJSONArray("tracks") ?: JSONArray()
            val list = ArrayList<Track>(arr.length())
            for (i in 0 until arr.length()) {
                list.add(trackFromJson(arr.getJSONObject(i)))
            }
            _tracks.value = list.sortedBy { it.title.lowercase() }
        } catch (_: Exception) {
        }
    }

    /** Le contenu de la bibliothèque en JSON (fichier local et sauvegarde SAF). */
    fun exportJson(): String {
        val root = JSONObject()
        root.put("folder", _folders.value.firstOrNull() ?: "")
        root.put("folders", JSONArray(_folders.value))
        val arr = JSONArray()
        for (t in _tracks.value) arr.put(trackToJson(t))
        root.put("tracks", arr)
        return root.toString()
    }

    suspend fun save() = mutex.withLock {
        try {
            file.writeText(exportJson())
        } catch (_: Exception) {
        }
    }

    companion object {
        fun trackToJson(t: Track): JSONObject {
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
            o.put("musicStartMs", t.musicStartMs)
            o.put("analyzed", t.analyzed)
            o.put("favorite", t.favorite)
            o.put("excluded", t.excluded)
            o.put("bpmLocked", t.bpmLocked)
            return o
        }

        fun trackFromJson(o: JSONObject): Track = Track(
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
            musicStartMs = o.optLong("musicStartMs", 0L),
            analyzed = o.optBoolean("analyzed", false),
            favorite = o.optBoolean("favorite", false),
            excluded = o.optBoolean("excluded", false),
            bpmLocked = o.optBoolean("bpmLocked", false)
        )
    }

    fun addFolder(uri: String) {
        if (uri !in _folders.value) _folders.value = _folders.value + uri
    }

    fun removeFolder(uri: String) {
        _folders.value = _folders.value.filter { it != uri }
    }

    /** Ajoute ou remplace un morceau (clé = uri). Thread-safe (analyse parallèle). */
    fun put(track: Track) = synchronized(this) {
        val list = _tracks.value.toMutableList()
        val idx = list.indexOfFirst { it.uri == track.uri }
        if (idx >= 0) list[idx] = track else list.add(track)
        _tracks.value = list.sortedBy { it.title.lowercase() }
    }

    fun get(uri: String): Track? = _tracks.value.firstOrNull { it.uri == uri }

    /** Modifie un morceau en place (favori, exclusion, BPM corrigé…). */
    fun update(uri: String, transform: (Track) -> Track) = synchronized(this) {
        _tracks.value = _tracks.value.map { if (it.uri == uri) transform(it) else it }
    }

    /** Retire un morceau de la bibliothèque (clé = uri). */
    fun remove(uri: String) = synchronized(this) {
        _tracks.value = _tracks.value.filter { it.uri != uri }
    }

    /** Supprime les morceaux qui ne sont plus dans les dossiers. */
    fun retainOnly(uris: Set<String>) = synchronized(this) {
        _tracks.value = _tracks.value.filter { it.uri in uris }
    }

    /** Efface les données d'analyse (les BPM verrouillés et les marquages
     *  favori/exclu sont conservés). */
    fun resetAnalysis() = synchronized(this) {
        _tracks.value = _tracks.value.map {
            if (it.bpmLocked) it
            else Track(
                uri = it.uri,
                title = it.title,
                artist = it.artist,
                durationMs = it.durationMs,
                favorite = it.favorite,
                excluded = it.excluded
            )
        }
    }
}
