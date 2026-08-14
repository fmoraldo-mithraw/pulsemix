package com.pulsemix.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Playlist(val name: String, val uris: List<String>)

/** Playlists nommées, persistées dans playlists.json. */
object PlaylistStore {

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists

    private var file: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        val f = File(context.filesDir, "playlists.json")
        file = f
        scope.launch {
            try {
                if (!f.exists()) return@launch
                val arr = JSONArray(f.readText())
                val list = ArrayList<Playlist>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val uris = ArrayList<String>()
                    val ua = o.optJSONArray("uris") ?: JSONArray()
                    for (j in 0 until ua.length()) uris.add(ua.getString(j))
                    list.add(Playlist(o.getString("name"), uris))
                }
                _playlists.value = list
            } catch (_: Exception) {
            }
        }
    }

    fun save(name: String, uris: List<String>) {
        if (name.isBlank() || uris.isEmpty()) return
        _playlists.value = _playlists.value.filter { it.name != name } +
            Playlist(name, uris)
        persist()
    }

    fun delete(name: String) {
        _playlists.value = _playlists.value.filter { it.name != name }
        persist()
    }

    private fun persist() {
        val snapshot = _playlists.value
        scope.launch {
            try {
                val arr = JSONArray()
                for (p in snapshot) {
                    val o = JSONObject()
                    o.put("name", p.name)
                    o.put("uris", JSONArray(p.uris))
                    arr.put(o)
                }
                file?.writeText(arr.toString())
            } catch (_: Exception) {
            }
        }
    }

    /** Résultat du dernier export M3U (chemin créé, ou erreur) : l'écran
     *  affichait avant une confirmation générique, mensongère quand
     *  l'écriture échouait en silence (stockage indisponible). */
    val exportMessage = MutableStateFlow<String?>(null)

    /** Export M3U8 dans le dossier de fichiers externes de l'appli. */
    fun exportM3u(context: Context, playlist: Playlist, titles: Map<String, String>) {
        scope.launch {
            try {
                val dir = context.getExternalFilesDir("Playlists")
                if (dir == null) {
                    exportMessage.value = "Export impossible : stockage indisponible."
                    return@launch
                }
                val safe = playlist.name.replace(Regex("[^A-Za-z0-9 _-]"), "_")
                val out = File(dir, "$safe.m3u8")
                out.writeText(buildString {
                    append("#EXTM3U\n")
                    for (uri in playlist.uris) {
                        append("#EXTINF:-1,").append(titles[uri] ?: uri).append('\n')
                        append(uri).append('\n')
                    }
                })
                exportMessage.value =
                    "« ${playlist.name} » exportée : ${out.absolutePath}"
            } catch (e: Exception) {
                exportMessage.value =
                    "Export M3U échoué : ${e.message ?: e::class.java.simpleName}"
            }
        }
    }
}
