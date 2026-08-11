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
    val genreLocked: Boolean = false,
    /** Énergie du dernier tiers rapportée au premier : la montée. */
    val energySlope: Float = 0f,
    /** Écart entre crête et fond sonore : l'amplitude de respiration. */
    val dynamicSpread: Float = 0f,
    /** Part de son tenu (0..1) : nappes, chœurs et cuivres contre percussions. */
    val sustainRatio: Float = 0f,
    /** Part de l'énergie entre 180 et 1200 Hz : voix massées et cuivres. */
    val lowMidRatio: Float = 0f,
    /**
     * Version du jeu de caractéristiques extraites. En deçà de
     * [com.pulsemix.app.analysis.AudioAnalyzer.FEATURES_VERSION], le
     * morceau est réanalysé au prochain scan.
     */
    val featuresVersion: Int = 0,
    /**
     * Jamais dans le mix Épique, quoi qu'en disent son nom ou son profil
     * sonore — et jamais comme référence de ce que l'épique sonne.
     */
    val notEpic: Boolean = false,
    /**
     * Gain de normalisation MESURÉ (dB, ±8), calculé à l'analyse depuis le
     * RMS global (voir AudioAnalyzer.gainDbFor). 0 = pas de mesure (morceau
     * analysé avant l'arrivée du champ, pas de réanalyse forcée) : les
     * lecteurs retombent alors sur l'ancienne formule à base d'energyMean.
     */
    val gainDb: Float = 0f,
    /**
     * Structure du morceau (intro/montée/temps fort/calme/outro), forme
     * compacte « KIND:startMs:endMs;… » de StructureDetector. Vide =
     * analysé avant l'arrivée du champ (pas de réanalyse forcée) : la
     * forme d'onde n'est pas colorée et le moteur DJ garde son calage
     * historique.
     */
    val structure: String = ""
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

    private suspend fun load() {
        mutex.withLock {
            // Le fichier principal peut être tronqué (processus tué en
            // pleine écriture, disque plein…). On retombe alors sur la
            // version précédente plutôt que de démarrer avec une
            // bibliothèque vide et des dossiers perdus.
            //
            // Le résultat de l'analyse est retenu au passage : relire un
            // JSON d'un mégaoctet deux fois au démarrage ne servirait à rien.
            var parsed: Pair<List<String>, List<Track>>? = null
            val text = SafeFile.readWithFallback(file, bakFile) { candidate ->
                parsed = parse(candidate)
                parsed != null
            }
            if (text == null) return@withLock
            val p = parsed ?: return@withLock
            _folders.value = p.first
            _tracks.value = SortedTracks.sorted(p.second)
        }
    }

    /** @return (dossiers, morceaux), ou null si le JSON est inexploitable. */
    private fun parse(text: String): Pair<List<String>, List<Track>>? = try {
        val root = JSONObject(text)
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
        folderList to list
    } catch (_: Exception) {
        null
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
    suspend fun save() {
        saveSoonJob?.cancel()
        mutex.withLock {
            SafeFile.writeAtomic(file, tmpFile, bakFile, exportJson().toByteArray())
        }
    }

    private var saveSoonJob: kotlinx.coroutines.Job? = null

    /**
     * Sauvegarde différée et regroupée : au plus une écriture toutes les
     * dix secondes, quelle que soit la cadence des demandes. Pour les
     * points de passage fréquents — un scan sauvegardait la bibliothèque
     * ENTIÈRE tous les cinq morceaux analysés, la correction de tags tous
     * les vingt : autant d'I/O répétées pour réécrire presque la même
     * chose. Les moments critiques (fin de scan, action utilisateur)
     * gardent [save], immédiat.
     */
    fun saveSoon() {
        if (saveSoonJob?.isActive == true) return
        saveSoonJob = scope.launch {
            kotlinx.coroutines.delay(10_000)
            mutex.withLock {
                SafeFile.writeAtomic(file, tmpFile, bakFile, exportJson().toByteArray())
            }
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
            o.put("energySlope", t.energySlope.toDouble())
            o.put("dynamicSpread", t.dynamicSpread.toDouble())
            o.put("sustainRatio", t.sustainRatio.toDouble())
            o.put("lowMidRatio", t.lowMidRatio.toDouble())
            o.put("featuresVersion", t.featuresVersion)
            o.put("notEpic", t.notEpic)
            o.put("gainDb", t.gainDb.toDouble())
            o.put("structure", t.structure)
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
            genreLocked = o.optBoolean("genreLocked", false),
            energySlope = o.optDouble("energySlope", 0.0).toFloat(),
            dynamicSpread = o.optDouble("dynamicSpread", 0.0).toFloat(),
            sustainRatio = o.optDouble("sustainRatio", 0.0).toFloat(),
            lowMidRatio = o.optDouble("lowMidRatio", 0.0).toFloat(),
            featuresVersion = o.optInt("featuresVersion", 0),
            notEpic = o.optBoolean("notEpic", false),
            gainDb = o.optDouble("gainDb", 0.0).toFloat(),
            structure = o.optString("structure", "")
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
        _tracks.value = SortedTracks.put(_tracks.value, track)
    }

    fun get(uri: String): Track? = _tracks.value.firstOrNull { it.uri == uri }

    /** Modifie un morceau en place (favori, exclusion, BPM corrigé…). */
    fun update(uri: String, transform: (Track) -> Track) = synchronized(this) {
        _tracks.value = SortedTracks.update(_tracks.value, uri, transform)
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
