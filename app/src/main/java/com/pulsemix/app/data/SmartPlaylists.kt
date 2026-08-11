package com.pulsemix.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Règle d'une playlist intelligente. Tous les critères sont optionnels :
 * null = « ne filtre pas là-dessus ». Un critère de BPM ou d'énergie
 * implique un morceau analysé (bpm 0 / énergie 0 ne le remplissent pas).
 */
data class Rule(
    val minBpm: Float? = null,
    val maxBpm: Float? = null,
    /** Genre normalisé (minuscules) — comparé sans tenir compte de la casse. */
    val genre: String? = null,
    val minEnergy: Float? = null,
    val maxEnergy: Float? = null,
    /** Ne garder que les morceaux pas joués depuis N jours (ou jamais). */
    val notPlayedDays: Int? = null,
    val favoritesOnly: Boolean? = null,
    val excludeExcluded: Boolean? = null
)

/** Une playlist intelligente : un nom et sa règle, évaluée à la lecture. */
data class SmartPlaylist(val name: String, val rule: Rule)

/**
 * Playlists intelligentes : des règles persistées (smart_playlists.json),
 * évaluées sur la bibliothèque au moment de jouer — la sélection suit donc
 * toujours l'état courant (analyses, favoris, historique), contrairement
 * aux playlists figées de [PlaylistStore].
 */
object SmartPlaylists {

    private val _playlists = MutableStateFlow<List<SmartPlaylist>>(emptyList())
    val playlists: StateFlow<List<SmartPlaylist>> = _playlists

    private var file: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        // Le ViewModel peut être recréé (rotation) : ne recharger qu'une fois,
        // sinon la relecture du disque écraserait une écriture en vol.
        if (file != null) return
        val f = File(context.filesDir, "smart_playlists.json")
        file = f
        scope.launch {
            try {
                if (!f.exists()) return@launch
                val loaded = readList(f.readText())
                // Fusion, pas assignation : un add() parti pendant cette
                // lecture (fenêtre de démarrage) prime sur le disque.
                _playlists.update { cur ->
                    loaded.filter { l -> cur.none { it.name == l.name } } + cur
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Ajoute ou remplace (clé = nom) une playlist intelligente. */
    fun add(name: String, rule: Rule) {
        if (name.isBlank()) return
        _playlists.value = _playlists.value.filter { it.name != name } +
            SmartPlaylist(name, rule)
        persist()
    }

    fun remove(name: String) {
        _playlists.value = _playlists.value.filter { it.name != name }
        persist()
    }

    private fun persist() {
        val snapshot = _playlists.value
        scope.launch {
            try {
                file?.writeText(writeList(snapshot))
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------- sérialisation

    internal fun writeList(list: List<SmartPlaylist>): String {
        val arr = JSONArray()
        for (p in list) {
            val r = JSONObject()
            p.rule.minBpm?.let { r.put("minBpm", it.toDouble()) }
            p.rule.maxBpm?.let { r.put("maxBpm", it.toDouble()) }
            p.rule.genre?.let { r.put("genre", it) }
            p.rule.minEnergy?.let { r.put("minEnergy", it.toDouble()) }
            p.rule.maxEnergy?.let { r.put("maxEnergy", it.toDouble()) }
            p.rule.notPlayedDays?.let { r.put("notPlayedDays", it) }
            p.rule.favoritesOnly?.let { r.put("favoritesOnly", it) }
            p.rule.excludeExcluded?.let { r.put("excludeExcluded", it) }
            val o = JSONObject()
            o.put("name", p.name)
            o.put("rule", r)
            arr.put(o)
        }
        return arr.toString()
    }

    internal fun readList(text: String): List<SmartPlaylist> {
        val arr = JSONArray(text)
        val list = ArrayList<SmartPlaylist>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val r = o.optJSONObject("rule") ?: JSONObject()
            list.add(
                SmartPlaylist(
                    o.getString("name"),
                    Rule(
                        minBpm = if (r.has("minBpm")) r.getDouble("minBpm").toFloat() else null,
                        maxBpm = if (r.has("maxBpm")) r.getDouble("maxBpm").toFloat() else null,
                        genre = if (r.has("genre")) r.getString("genre") else null,
                        minEnergy = if (r.has("minEnergy")) r.getDouble("minEnergy").toFloat() else null,
                        maxEnergy = if (r.has("maxEnergy")) r.getDouble("maxEnergy").toFloat() else null,
                        notPlayedDays = if (r.has("notPlayedDays")) r.getInt("notPlayedDays") else null,
                        favoritesOnly = if (r.has("favoritesOnly")) r.getBoolean("favoritesOnly") else null,
                        excludeExcluded = if (r.has("excludeExcluded")) r.getBoolean("excludeExcluded") else null
                    )
                )
            )
        }
        return list
    }

    // --------------------------------------------------------- évaluation

    /**
     * Évalue une règle sur la bibliothèque. Fonction PURE (testable JVM) :
     * l'horloge et l'historique de lecture sont INJECTÉS — [now] pour
     * « aujourd'hui », [lastPlayedMs] pour la dernière lecture d'un URI
     * (null = jamais joué), branché sur [PlayHistory.lastPlayed] en vrai.
     * L'ordre de la bibliothèque est conservé.
     */
    fun evaluate(
        rule: Rule,
        tracks: List<Track>,
        now: Long,
        lastPlayedMs: (String) -> Long?
    ): List<Track> {
        val genre = rule.genre?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return tracks.filter { t ->
            (rule.minBpm == null || t.bpm >= rule.minBpm) &&
                // Un plafond de BPM sous-entend un BPM connu : sans ce garde,
                // tous les morceaux non analysés (bpm 0) passaient le filtre.
                (rule.maxBpm == null || (t.bpm > 0f && t.bpm <= rule.maxBpm)) &&
                (genre == null || t.genre.trim().lowercase() == genre) &&
                (rule.minEnergy == null || t.energyMean >= rule.minEnergy) &&
                (rule.maxEnergy == null || (t.energyMean > 0f && t.energyMean <= rule.maxEnergy)) &&
                (rule.favoritesOnly != true || t.favorite) &&
                (rule.excludeExcluded != true || !t.excluded) &&
                (rule.notPlayedDays == null || run {
                    val last = lastPlayedMs(t.uri)
                    last == null || now - last >= rule.notPlayedDays * 86_400_000L
                })
        }
    }
}
