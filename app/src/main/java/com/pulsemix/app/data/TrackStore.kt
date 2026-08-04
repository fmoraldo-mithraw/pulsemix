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
    val bpmLocked: Boolean = false,
    /** Meilleur passage défini à la main : protégé contre la réanalyse. */
    val segmentLocked: Boolean = false,
    /** Genre lu dans les métadonnées du fichier (normalisé en minuscules). */
    val genre: String = "",
    /** Type choisi à la main : protégé contre le scan et la réanalyse.
     *  Le fichier audio n'est jamais modifié. */
    val genreLocked: Boolean = false
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

    // Déclarés AVANT le bloc init : load() y accède depuis la coroutine
    // qu'il lance, et des propriétés encore non initialisées à cet instant
    // seraient nulles.
    private val tmpFile = File(appContext.filesDir, "library.json.tmp")
    private val bakFile = File(appContext.filesDir, "library.json.bak")

    init {
        scope.launch {
            load()
            _loaded.value = true
        }
    }

    private suspend fun load() = mutex.withLock {
        // Le fichier principal peut être tronqué (processus tué en pleine
        // écriture avant que save() ne devienne atomique, disque plein…).
        // On retombe alors sur la version précédente plutôt que de démarrer
        // avec une bibliothèque vide et des dossiers perdus.
        if (readInto(file)) return@withLock
        if (readInto(bakFile)) {
            try {
                bakFile.copyTo(file, overwrite = true)
            } catch (_: Exception) {
            }
        }
    }

    /** @return true si le fichier a été lu et la bibliothèque remplie. */
    private fun readInto(src: File): Boolean {
        if (!src.exists() || src.length() == 0L) return false
        return try {
            val root = JSONObject(src.readText())
            val folderList = ArrayList<String>()
            val fArr = root.optJSONArray("folders")
            if (fArr != null) {
                for (i in 0 until fArr.length()) folderList.add(fArr.getString(i))
            } else {
                // ancien format : un seul dossier
                root.optString("folder").takeIf { it.isNotEmpty() }?.let { folderList.add(it) }
            }
            val arr = root.optJSONArray("tracks") ?: JSONArray()
            val list = ArrayList<Track>(arr.length())
            for (i in 0 until arr.length()) {
                list.add(trackFromJson(arr.getJSONObject(i)))
            }
            _folders.value = folderList
            _tracks.value = list.sortedBy { sortKey(it) }
            true
        } catch (_: Exception) {
            false
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

    /**
     * Écriture atomique : le JSON part d'abord dans un fichier temporaire,
     * et ne remplace la bibliothèque que complet. La version précédente est
     * conservée en filet. Sans ça, un processus tué en pleine écriture
     * laissait un fichier tronqué — et la bibliothèque entière disparaissait
     * au démarrage suivant, dossiers et analyses compris.
     */
    suspend fun save() = mutex.withLock {
        try {
            val bytes = exportJson().toByteArray()
            tmpFile.writeBytes(bytes)
            // Contrôle de taille : un disque plein tronque sans rien lever,
            // et remplacer la bibliothèque par un fichier court serait pire
            // que de ne pas la sauvegarder du tout.
            if (tmpFile.length() != bytes.size.toLong()) {
                tmpFile.delete()
                return@withLock
            }
            if (file.exists()) {
                bakFile.delete()
                if (!file.renameTo(bakFile)) file.copyTo(bakFile, overwrite = true)
            }
            if (!tmpFile.renameTo(file)) {
                tmpFile.copyTo(file, overwrite = true)
                tmpFile.delete()
            }
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
            o.put("segmentLocked", t.segmentLocked)
            o.put("genre", t.genre)
            o.put("genreLocked", t.genreLocked)
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
            bpmLocked = o.optBoolean("bpmLocked", false),
            segmentLocked = o.optBoolean("segmentLocked", false),
            genre = o.optString("genre", ""),
            genreLocked = o.optBoolean("genreLocked", false)
        )
    }

    fun addFolder(uri: String) {
        if (uri !in _folders.value) _folders.value = _folders.value + uri
    }

    fun removeFolder(uri: String) {
        _folders.value = _folders.value.filter { it != uri }
    }

    /**
     * Ajoute ou remplace un morceau (clé = uri). Thread-safe (analyse
     * parallèle).
     *
     * La liste est tenue triée par insertion plutôt que re-triée en entier :
     * un scan de 800 morceaux faisait 800 tris de 800 éléments, chacun
     * rallouant un titre en minuscules par comparaison. C'était une bonne
     * part de la lenteur du premier scan.
     */
    fun put(track: Track) = synchronized(this) {
        val list = _tracks.value.toMutableList()
        val idx = list.indexOfFirst { it.uri == track.uri }
        if (idx >= 0) {
            // Le rang ne bouge que si le titre change (correction de tag)
            if (sortKey(list[idx]) == sortKey(track)) {
                list[idx] = track
                _tracks.value = list
                return@synchronized
            }
            list.removeAt(idx)
        }
        list.add(insertionPoint(list, sortKey(track)), track)
        _tracks.value = list
    }

    fun get(uri: String): Track? = _tracks.value.firstOrNull { it.uri == uri }

    /** Modifie un morceau en place (favori, exclusion, BPM corrigé…). */
    fun update(uri: String, transform: (Track) -> Track) = synchronized(this) {
        val list = _tracks.value
        val idx = list.indexOfFirst { it.uri == uri }
        if (idx < 0) return@synchronized
        val updated = transform(list[idx])
        val out = list.toMutableList()
        if (sortKey(list[idx]) == sortKey(updated)) {
            out[idx] = updated
        } else {
            // Titre corrigé : le morceau change de place dans la liste
            out.removeAt(idx)
            out.add(insertionPoint(out, sortKey(updated)), updated)
        }
        _tracks.value = out
    }

    private fun sortKey(t: Track): String = t.title.lowercase()

    /** Premier rang où insérer [key] pour garder la liste triée. */
    private fun insertionPoint(list: List<Track>, key: String): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sortKey(list[mid]) <= key) lo = mid + 1 else hi = mid
        }
        return lo
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
            if (it.bpmLocked || it.segmentLocked) it
            else Track(
                uri = it.uri,
                title = it.title,
                artist = it.artist,
                durationMs = it.durationMs,
                favorite = it.favorite,
                excluded = it.excluded,
                genre = it.genre,
                genreLocked = it.genreLocked
            )
        }
    }
}
