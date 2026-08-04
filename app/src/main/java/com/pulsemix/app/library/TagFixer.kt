package com.pulsemix.app.library

import android.content.Context
import com.pulsemix.app.data.Track
import com.pulsemix.app.data.TrackStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Recherche des « vrais » tags (titre / artiste) en ligne via MusicBrainz,
 * pour un morceau ou toute la bibliothèque.
 *
 *  - Les corrections SÛRES (même titre à la casse/ponctuation près, ou
 *    correspondance quasi certaine) sont appliquées automatiquement.
 *  - Les propositions INCERTAINES vont dans une liste à valider à la main.
 *  - Les tags corrigés vivent dans la bibliothèque (et sa sauvegarde),
 *    comme les types de musique. Les fichiers audio ne sont touchés que si
 *    l'option « écrire les tags dans les fichiers » est activée.
 */
object TagFixer {

    data class Suggestion(
        val uri: String,
        val oldTitle: String,
        val oldArtist: String,
        val newTitle: String,
        val newArtist: String,
        val score: Int
    )

    /** Propositions incertaines en attente de validation. */
    val pending = MutableStateFlow<List<Suggestion>>(emptyList())

    /** Dernière erreur réseau/MusicBrainz (null si tout va bien). */
    val lastError = MutableStateFlow<String?>(null)

    /** Corrections appliquées (auto ou validées), la plus récente d'abord. */
    val applied = MutableStateFlow<List<Suggestion>>(emptyList())

    /** Nombre maximal d'entrées gardées dans l'historique des corrections. */
    private const val APPLIED_MAX = 300

    /**
     * Option : écrire aussi les corrections dans le fichier audio. Par
     * défaut non — la bibliothèque de l'app suffit à bien ranger sa
     * musique, et modifier les fichiers n'est pas anodin.
     */
    val writeToFiles = MutableStateFlow(false)

    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun setWriteToFiles(enabled: Boolean) {
        writeToFiles.value = enabled
        appContext?.getSharedPreferences("settings", Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("writeTagsToFiles", enabled)?.apply()
    }

    /**
     * Reporte une correction dans le fichier lui-même, si l'option est
     * active. En arrière-plan : ffmpeg recopie le fichier entier, ce qui
     * prend un instant et ne doit jamais retarder l'affichage.
     */
    private fun writeTagsIfEnabled(uri: String, title: String, artist: String) {
        if (!writeToFiles.value) return
        val ctx = appContext ?: return
        writeScope.launch { TagWriter.write(ctx, uri, title, artist) }
    }

    /**
     * Écrit dans les fichiers toutes les corrections déjà appliquées :
     * pour celles faites avant d'activer l'option.
     * @return nombre de fichiers effectivement réécrits.
     */
    suspend fun writeAllApplied(): Int = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext 0
        var n = 0
        // Une seule écriture par morceau, la plus récente d'abord
        val seen = HashSet<String>()
        for (s in applied.value) {
            if (!seen.add(s.uri)) continue
            if (TagWriter.write(ctx, s.uri, s.newTitle, s.newArtist)) n++
        }
        n
    }

    /** Progression du passage bibliothèque : (faits, total, appliqués auto). */
    val progress: StateFlow<Triple<Int, Int, Int>?> get() = _progress
    private val _progress = MutableStateFlow<Triple<Int, Int, Int>?>(null)

    @Volatile private var stopRequested = false
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        writeToFiles.value = appContext!!
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("writeTagsToFiles", false)
        load()
    }

    fun requestStop() {
        stopRequested = true
    }

    // ------------------------------------------------------------ traitement

    /** Cherche les tags d'un seul morceau (proposition ou application sûre). */
    suspend fun fixOne(store: TrackStore, track: Track): Unit =
        withContext(Dispatchers.IO) {
            when (handleBySound(store, track)) {
                SoundOutcome.APPLIED, SoundOutcome.DONE -> {}
                SoundOutcome.NOT_FOUND -> handle(store, track)
            }
            markChecked(track.uri)
            save()
            store.save()
        }

    /**
     * Passe toute la bibliothèque : identification par empreinte sonore
     * d'abord (AcoustID), recherche texte MusicBrainz en repli. Les
     * morceaux les plus cassés — titre commençant par un chiffre et
     * artiste poubelle type « Downloads » — passent en premier.
     *
     * @param force true pour tout revérifier, y compris les morceaux déjà
     * examinés lors d'un passage précédent.
     */
    suspend fun fixAll(store: TrackStore, force: Boolean = false): Unit =
        withContext(Dispatchers.IO) {
            if (_progress.value != null) return@withContext
            stopRequested = false
            lastError.value = null
            if (force) {
                checked.clear()
                checkedCount.value = 0
            }
            // Déjà examinés lors d'un passage précédent : inutile de
            // redécoder l'audio et de réinterroger AcoustID pour eux. Sur
            // une grosse bibliothèque, ça faisait des heures de travail
            // refait à l'identique à chaque relance.
            val list = store.tracks.value
                .filter { it.uri !in checked }
                .sortedByDescending {
                    if (it.title.trim().firstOrNull()?.isDigit() == true &&
                        it.artist.isNotBlank() && cleanArtist(it.artist).isBlank()
                    ) 1 else 0
                }
            var applied = 0
            _progress.value = Triple(0, list.size, 0)
            try {
                for ((i, t) in list.withIndex()) {
                    if (stopRequested) break
                    when (handleBySound(store, t)) {
                        SoundOutcome.APPLIED -> applied++
                        SoundOutcome.DONE -> {}
                        SoundOutcome.NOT_FOUND -> if (handle(store, t)) applied++
                    }
                    markChecked(t.uri)
                    _progress.value = Triple(i + 1, list.size, applied)
                    // Sauvegarde régulière : un passage interrompu (appli
                    // tuée, batterie) ne doit pas être entièrement à refaire
                    if ((i + 1) % 20 == 0) {
                        save()
                        store.save()
                    }
                }
            } finally {
                save()
                store.save()
                _progress.value = null
            }
        }

    /** Morceaux déjà examinés (empreinte + recherche), pour ne pas y revenir. */
    private val checked = HashSet<String>()

    /** Nombre de morceaux déjà examinés — affiché dans l'écran Tags. */
    val checkedCount = MutableStateFlow(0)

    private fun markChecked(uri: String) {
        if (checked.add(uri)) checkedCount.value = checked.size
    }

    /** Oublie les morceaux déjà examinés : le passage suivant reprend tout. */
    fun resetChecked() {
        checked.clear()
        checkedCount.value = 0
        writeChecked()
    }

    // ---------------------------------------------- identification sonore

    private enum class SoundOutcome { APPLIED, DONE, NOT_FOUND }

    /**
     * Identifie le morceau par son empreinte sonore. APPLIED : correction
     * sûre appliquée ; DONE : déjà correct ou proposition ajoutée (pas
     * besoin du repli texte) ; NOT_FOUND : rien d'exploitable.
     */
    private fun handleBySound(store: TrackStore, t: Track): SoundOutcome {
        val ctx = appContext ?: return SoundOutcome.NOT_FOUND
        val fp = AcoustId.fingerprint(ctx, t.uri) ?: return SoundOutcome.NOT_FOUND
        val cands = AcoustId.lookup(fp.first, fp.second)
        val best = pickBest(cands, t.durationMs) ?: return SoundOutcome.NOT_FOUND
        if (best.title.isBlank()) return SoundOutcome.NOT_FOUND
        return when {
            // L'empreinte est formelle : c'est cet enregistrement
            best.score >= 90 -> {
                if (best.title == t.title &&
                    (best.artist.isBlank() || best.artist == t.artist)
                ) {
                    SoundOutcome.DONE
                } else {
                    store.update(t.uri) {
                        it.copy(
                            title = best.title,
                            artist = best.artist.ifBlank { it.artist }
                        )
                    }
                    writeTagsIfEnabled(
                        t.uri, best.title, best.artist.ifBlank { t.artist }
                    )
                    applied.value = (
                        listOf(
                            Suggestion(
                                t.uri, t.title, t.artist,
                                best.title, best.artist, best.score
                            )
                        ) + applied.value
                        ).take(APPLIED_MAX)
                    pending.value = pending.value.filter { it.uri != t.uri }
                    SoundOutcome.APPLIED
                }
            }
            // Correspondance partielle : à valider à la main
            best.score >= 60 -> {
                pending.value = pending.value.filter { it.uri != t.uri } +
                    Suggestion(
                        t.uri, t.title, t.artist,
                        best.title, best.artist, best.score
                    )
                SoundOutcome.DONE
            }
            else -> SoundOutcome.NOT_FOUND
        }
    }

    /** Candidats par empreinte sonore, pour la recherche manuelle. */
    fun searchCandidatesBySound(t: Track): List<Candidate> {
        val ctx = appContext ?: return emptyList()
        val fp = AcoustId.fingerprint(ctx, t.uri)
        if (fp == null) {
            lastError.value =
                "Impossible de décoder l'audio pour calculer l'empreinte."
            return emptyList()
        }
        return AcoustId.lookup(fp.first, fp.second)
    }

    /** Valide une proposition : applique à la bibliothèque (pas au fichier). */
    fun accept(store: TrackStore, s: Suggestion) {
        store.update(s.uri) {
            it.copy(
                title = s.newTitle,
                artist = s.newArtist.ifBlank { it.artist }
            )
        }
        writeTagsIfEnabled(s.uri, s.newTitle, s.newArtist)
        applied.value = (listOf(s) + applied.value).take(APPLIED_MAX)
        pending.value = pending.value.filter { it.uri != s.uri }
        markChecked(s.uri)
        save()
    }

    fun clearApplied() {
        applied.value = emptyList()
        save()
    }

    fun reject(s: Suggestion) {
        pending.value = pending.value.filter { it.uri != s.uri }
        markChecked(s.uri)
        save()
    }

    /** @return true si une correction sûre a été appliquée automatiquement. */
    private fun handle(store: TrackStore, t: Track): Boolean {
        val cleaned = cleanTitle(t.title)
        val split = splitArtistTitle(cleaned)
        val artistTag = cleanArtist(t.artist)

        // Essais du plus précis au plus vague : tag artiste existant, puis
        // découpage « Artiste - Titre » du nom de fichier, puis titre seul.
        // On s'arrête au premier essai qui donne une correction sûre.
        val attempts = buildList {
            if (artistTag.isNotBlank()) add(cleaned to artistTag)
            if (split != null) add(split.second to split.first)
            add((split?.second ?: cleaned) to "")
        }.distinct()

        var proposal: Suggestion? = null
        for ((qTitle, qArtist) in attempts) {
            if (qTitle.isBlank()) continue
            val best = pickBest(lookup(qTitle, qArtist), t.durationMs) ?: continue
            if (best.title.isBlank()) continue
            if (best.title == t.title &&
                (best.artist.isBlank() || best.artist == t.artist)
            ) return false // déjà correct

            val durOk = durationClose(best.lengthMs, t.durationMs)
            val titleMatch = norm(best.title) == norm(qTitle)
            val artistMatch = qArtist.isNotBlank() &&
                norm(best.artist) == norm(qArtist)

            // Sûr : titre et artiste identiques à la casse près, ou titre
            // identique + durée qui colle. Le score MusicBrainz seul ne
            // suffit jamais : il est relatif à la recherche (le premier
            // résultat frôle 100 même quand c'est un mauvais match).
            // Deux garde-fous : sans artiste dans la requête, jamais sûr
            // (trop d'homonymes de titres) ; et si le morceau n'a pas
            // d'artiste exploitable au départ (tag vide ou poubelle type
            // « Downloads »), on ne remplace jamais en silence — la
            // réponse part en proposition, à valider à la main.
            val sure = artistTag.isNotBlank() && qArtist.isNotBlank() && (
                (titleMatch && artistMatch) ||
                    (titleMatch && durOk) ||
                    (artistMatch && durOk && best.score >= 95)
                )
            if (sure) {
                store.update(t.uri) {
                    it.copy(
                        title = best.title,
                        artist = best.artist.ifBlank { it.artist }
                    )
                }
                writeTagsIfEnabled(
                    t.uri, best.title, best.artist.ifBlank { t.artist }
                )
                applied.value = (
                    listOf(
                        Suggestion(
                            t.uri, t.title, t.artist,
                            best.title, best.artist, best.score
                        )
                    ) + applied.value
                ).take(APPLIED_MAX)
                pending.value = pending.value.filter { it.uri != t.uri }
                return true
            }
            // Proposition : plausible, mais on exige que la durée ne
            // contredise pas la correspondance (inconnue tolérée).
            if (proposal == null && best.score >= 60 &&
                (durOk || best.lengthMs <= 0)
            ) {
                proposal = Suggestion(
                    t.uri, t.title, t.artist, best.title, best.artist, best.score
                )
            }
        }
        if (proposal != null) {
            pending.value = pending.value.filter { it.uri != t.uri } + proposal
        }
        return false
    }

    // ---------------------------------------------------------- MusicBrainz

    data class Candidate(
        val title: String,
        val artist: String,
        val score: Int,
        val lengthMs: Long
    )

    /**
     * Recherche manuelle : l'utilisateur fournit titre (et artiste), on
     * renvoie plusieurs candidats parmi lesquels choisir.
     */
    fun searchCandidates(title: String, artist: String): List<Candidate> =
        lookup(title.trim(), artist.trim(), limit = 10)

    /** Pré-remplissage du formulaire de recherche manuelle. */
    fun prefill(t: Track): Pair<String, String> {
        val cleaned = cleanTitle(t.title)
        val artist = cleanArtist(t.artist)
        if (artist.isNotBlank()) return cleaned to artist
        val split = splitArtistTitle(cleaned)
        return if (split != null) split.second to split.first else cleaned to ""
    }

    /** Applique le candidat choisi à la main (bibliothèque seulement). */
    fun applyManual(store: TrackStore, t: Track, c: Candidate) {
        if (c.title.isBlank()) return
        store.update(t.uri) {
            it.copy(title = c.title, artist = c.artist.ifBlank { it.artist })
        }
        writeTagsIfEnabled(t.uri, c.title, c.artist.ifBlank { t.artist })
        applied.value = (
            listOf(Suggestion(t.uri, t.title, t.artist, c.title, c.artist, c.score)) +
                applied.value
            ).take(APPLIED_MAX)
        pending.value = pending.value.filter { it.uri != t.uri }
        markChecked(t.uri)
        save()
    }

    /**
     * Annule une correction : remet les tags d'origine enregistrés dans
     * l'entrée d'historique, puis retire celle-ci de la liste des corrigés.
     */
    fun revert(store: TrackStore, s: Suggestion) {
        if (s.oldTitle.isBlank()) return
        store.update(s.uri) { it.copy(title = s.oldTitle, artist = s.oldArtist) }
        writeTagsIfEnabled(s.uri, s.oldTitle, s.oldArtist)
        applied.value = applied.value - s
        // Correction annulée : le morceau redevient à examiner
        checked.remove(s.uri)
        checkedCount.value = checked.size
        save()
    }

    /**
     * Sauvegarde directe de tags saisis à la main, sans recherche.
     * Contrairement à [applyManual], l'artiste est pris tel quel : le
     * vider efface vraiment le tag artiste.
     */
    fun saveDirect(store: TrackStore, t: Track, title: String, artist: String) {
        val newTitle = title.trim()
        val newArtist = artist.trim()
        if (newTitle.isBlank()) return
        if (newTitle == t.title && newArtist == t.artist) return
        store.update(t.uri) { it.copy(title = newTitle, artist = newArtist) }
        writeTagsIfEnabled(t.uri, newTitle, newArtist)
        applied.value = (
            listOf(Suggestion(t.uri, t.title, t.artist, newTitle, newArtist, 0)) +
                applied.value
            ).take(APPLIED_MAX)
        pending.value = pending.value.filter { it.uri != t.uri }
        save()
    }

    /** Meilleur candidat : durée compatible d'abord, score ensuite. */
    private fun pickBest(cands: List<Candidate>, durMs: Long): Candidate? {
        val durOk = cands.filter { durationClose(it.lengthMs, durMs) }
        return (durOk.ifEmpty { cands }).maxByOrNull { it.score }
    }

    private fun durationClose(a: Long, b: Long): Boolean =
        a > 0 && b > 0 && kotlin.math.abs(a - b) <= 7_000

    @Volatile private var lastRequestAt = 0L

    /** Espacement ≥ 1,1 s entre requêtes (politesse MusicBrainz). */
    private fun throttle() {
        val wait = lastRequestAt + 1_100 - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    private fun lookup(title: String, artist: String, limit: Int = 5): List<Candidate> {
        if (title.isBlank()) return emptyList()
        return try {
            throttle()
            val q = buildString {
                append("recording:\"").append(title.replace("\"", " ")).append('"')
                if (artist.isNotBlank()) {
                    append(" AND artist:\"").append(artist.replace("\"", " ")).append('"')
                }
            }
            val url = URL(
                "https://musicbrainz.org/ws/2/recording?query=" +
                    URLEncoder.encode(q, "UTF-8") + "&fmt=json&limit=$limit"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty(
                "User-Agent",
                "PulseMix/1.4 (https://github.com/fmoraldo-mithraw/pulsemix)"
            )
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val recs = JSONObject(body).optJSONArray("recordings")
                ?: return emptyList()
            val out = ArrayList<Candidate>()
            for (i in 0 until recs.length()) {
                val r = recs.getJSONObject(i)
                out.add(
                    Candidate(
                        r.optString("title", ""),
                        r.optJSONArray("artist-credit")
                            ?.optJSONObject(0)?.optString("name", "") ?: "",
                        r.optInt("score", 0),
                        r.optLong("length", 0L)
                    )
                )
            }
            lastError.value = null
            out
        } catch (e: Exception) {
            lastError.value = "MusicBrainz injoignable : " +
                (e.message ?: e::class.java.simpleName).take(120)
            emptyList()
        }
    }

    /**
     * « Artiste - Titre » : le format quasi universel des fichiers sans
     * tags. Le séparateur doit être entouré d'espaces pour ne pas couper
     * les mots composés (« Jean-Michel »).
     */
    private fun splitArtistTitle(s: String): Pair<String, String>? {
        val m = Regex("^(.{2,60}?)\\s+[-–—|]\\s+(.{2,})$").find(s) ?: return null
        val artist = m.groupValues[1].trim()
        val title = m.groupValues[2].trim()
        if (artist.isBlank() || title.isBlank()) return null
        return artist to title
    }

    // Valeurs d'artiste « poubelle » écrites par certains logiciels de
    // téléchargement : à ignorer pour les recherches — le vrai artiste
    // sera retrouvé via le nom de fichier ou MusicBrainz.
    private val junkArtists = setOf(
        "downloads", "download", "téléchargements", "telechargements",
        "unknown", "unknown artist", "<unknown>", "artiste inconnu",
        "various artists", "va", "audio", "music"
    )

    /** Artiste utilisable pour une recherche ("" si valeur poubelle). */
    private fun cleanArtist(raw: String): String {
        val a = raw.trim()
        return if (a.lowercase() in junkArtists) "" else a
    }

    /** Nettoie un titre « nom de fichier » avant la recherche. */
    private fun cleanTitle(raw: String): String {
        var s = raw
            .replace(
                Regex(
                    "\\.(mp3|m4a|aac|flac|ogg|opus|wav|weba)$",
                    RegexOption.IGNORE_CASE
                ), ""
            )
            .replace('_', ' ')
        // Identifiant vidéo yt-dlp en fin de nom : « Titre [dQw4w9WgXcQ] »
        s = s.replace(Regex("\\s*\\[[A-Za-z0-9_-]{6,}\\]\\s*$"), " ")
        s = s.replace(
            Regex(
                "[\\(\\[](official|clip|video|lyrics?|audio|hd|hq|4k|paroles" +
                    "|visuali[sz]er|explicit|remaster(ed)?)" +
                    "[^\\)\\]]*[\\)\\]]",
                RegexOption.IGNORE_CASE
            ), " "
        )
        // « feat. X » : l'invité ne fait pas partie du titre MusicBrainz
        s = s.replace(
            Regex(
                "[\\(\\[]\\s*(feat\\.?|ft\\.?|featuring|avec)\\s[^\\)\\]]*[\\)\\]]",
                RegexOption.IGNORE_CASE
            ), " "
        )
        s = s.replace(
            Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE),
            " "
        )
        s = s.replace(Regex("^\\s*\\d{1,3}\\s*[-.]\\s*"), "")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun norm(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9à-ÿ]"), "")

    // ---------------------------------------------------------- persistance

    private fun file() = java.io.File(appContext!!.filesDir, "tag_suggestions.json")
    private fun appliedFile() = java.io.File(appContext!!.filesDir, "tag_applied.json")
    private fun checkedFile() = java.io.File(appContext!!.filesDir, "tag_checked.json")

    private fun readChecked(): Set<String> = try {
        val arr = JSONArray(checkedFile().readText())
        val set = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) set.add(arr.getString(i))
        set
    } catch (_: Exception) {
        emptySet()
    }

    private fun writeChecked() {
        try {
            checkedFile().writeText(JSONArray(checked.toList()).toString())
        } catch (_: Exception) {
        }
    }

    private fun readList(f: java.io.File): List<Suggestion> = try {
        val arr = JSONArray(f.readText())
        val list = ArrayList<Suggestion>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Suggestion(
                    o.getString("uri"),
                    o.optString("oldTitle", ""),
                    o.optString("oldArtist", ""),
                    o.optString("newTitle", ""),
                    o.optString("newArtist", ""),
                    o.optInt("score", 0)
                )
            )
        }
        list
    } catch (_: Exception) {
        emptyList()
    }

    private fun writeList(f: java.io.File, list: List<Suggestion>) {
        try {
            val arr = JSONArray()
            for (s in list) {
                arr.put(
                    JSONObject()
                        .put("uri", s.uri)
                        .put("oldTitle", s.oldTitle)
                        .put("oldArtist", s.oldArtist)
                        .put("newTitle", s.newTitle)
                        .put("newArtist", s.newArtist)
                        .put("score", s.score)
                )
            }
            f.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun load() {
        pending.value = readList(file())
        applied.value = readList(appliedFile())
        checked.clear()
        checked.addAll(readChecked())
        checkedCount.value = checked.size
    }

    private fun save() {
        writeList(file(), pending.value)
        writeList(appliedFile(), applied.value)
        writeChecked()
    }
}
