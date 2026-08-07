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
        val score: Int,
        /** Groupe de parution MusicBrainz : la clé de la jaquette. */
        val releaseGroupId: String = ""
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
     * Récupère la jaquette du morceau identifié, en arrière-plan : le
     * passage des tags n'attend jamais un téléchargement d'image.
     */
    private fun fetchCover(uri: String, releaseGroupId: String) {
        if (releaseGroupId.isBlank()) return
        val ctx = appContext ?: return
        writeScope.launch { CoverArt.fetchIfMissing(ctx, uri, releaseGroupId) }
    }

    /**
     * Suites d'une correction appliquée, en arrière-plan et DANS L'ORDRE :
     * l'écriture du fichier d'abord (si l'option est active), la jaquette
     * ensuite. Une seule coroutine par morceau — chercher une jaquette
     * dans le fichier pendant que ffmpeg le réécrit tombait sur un fichier
     * tronqué, et classait à tort le morceau comme « sans jaquette ».
     */
    private fun afterApply(
        uri: String,
        title: String,
        artist: String,
        releaseGroupId: String
    ) {
        val ctx = appContext ?: return
        val write = writeToFiles.value
        if (!write && releaseGroupId.isBlank()) return
        writeScope.launch {
            if (write) TagWriter.write(ctx, uri, title, artist)
            if (releaseGroupId.isNotBlank()) {
                CoverArt.fetchIfMissing(ctx, uri, releaseGroupId)
            }
        }
    }

    /** Avancement du report bibliothèque → fichiers : (faits, total). */
    val writeProgress = MutableStateFlow<Pair<Int, Int>?>(null)

    /** Résultat du dernier report (message à afficher dans les réglages). */
    val writeMessage = MutableStateFlow<String?>(null)

    /**
     * Reporte les tags de la BIBLIOTHÈQUE dans les fichiers audio, pour
     * toutes les corrections faites avant d'activer l'option.
     *
     * La bibliothèque fait foi, pas l'historique : celui-ci ne garde que
     * les 300 dernières corrections, et tout ce qui avait été corrigé
     * au-delà — ou avant qu'il existe — n'était jamais reporté. Chaque
     * fichier est d'abord LU : seuls ceux dont les tags incrustés
     * diffèrent sont réécrits, pas de copie ffmpeg pour rien.
     *
     * @return nombre de fichiers effectivement réécrits.
     */
    suspend fun writeAllToFiles(store: TrackStore): Int = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext 0
        if (writeProgress.value != null || _progress.value != null ||
            coverProgress.value != null
        ) {
            return@withContext 0
        }
        stopRequested = false
        val all = store.tracks.value
        var written = 0
        writeProgress.value = 0 to all.size
        try {
            for ((i, t) in all.withIndex()) {
                if (stopRequested) break
                val embedded = readFileTags(ctx, t.uri)
                // Fichier illisible : ne pas risquer une réécriture aveugle
                if (embedded != null &&
                    (embedded.first != t.title || embedded.second != t.artist)
                ) {
                    if (TagWriter.write(ctx, t.uri, t.title, t.artist)) written++
                }
                writeProgress.value = (i + 1) to all.size
            }
        } finally {
            writeProgress.value = null
            writeMessage.value =
                if (written > 0) "$written fichiers mis à jour."
                else "Tous les fichiers portaient déjà les bons tags."
        }
        written
    }

    /** Progression du passage bibliothèque : (faits, total, appliqués auto). */
    val progress: StateFlow<Triple<Int, Int, Int>?> get() = _progress
    private val _progress = MutableStateFlow<Triple<Int, Int, Int>?>(null)

    // ------------------------------------------------------------ jaquettes

    /** Avancement du passage « jaquettes manquantes » : (faits, total). */
    val coverProgress = MutableStateFlow<Pair<Int, Int>?>(null)

    /** Résultat du dernier passage jaquettes (message de l'écran Tags). */
    val coverMessage = MutableStateFlow<String?>(null)

    /**
     * Passe la bibliothèque et récupère une jaquette pour chaque morceau
     * qui n'en a aucune — ni embarquée dans le fichier, ni déjà
     * téléchargée. L'album est retrouvé par recherche MusicBrainz sur les
     * tags actuels (censés être corrects après la correction des tags) ;
     * un candidat dont ni le titre ni la durée ne collent est écarté :
     * mieux vaut pas de pochette qu'une pochette d'un autre morceau.
     *
     * @return nombre de jaquettes récupérées.
     */
    suspend fun fetchAllCovers(store: TrackStore): Int = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext 0
        // Un seul passage à la fois, tous types confondus : ils partagent
        // le drapeau d'arrêt et se contrediraient (remise à zéro qui efface
        // les jaquettes pendant qu'un passage en télécharge, etc.).
        if (coverProgress.value != null || _progress.value != null ||
            writeProgress.value != null
        ) {
            return@withContext 0
        }
        stopRequested = false
        coverMessage.value = null
        val all = store.tracks.value
        var got = 0
        var seen = 0
        coverProgress.value = 0 to all.size
        try {
            for ((i, t) in all.withIndex()) {
                if (stopRequested) break
                seen = i + 1
                // Déjà une jaquette : rien à faire (vérif locale, rapide)
                if (com.pulsemix.app.ui.ArtworkCache
                        .loadBlocking(ctx, t.uri, 512) == null
                ) {
                    val title = cleanTitle(t.title)
                    val artist = cleanArtist(t.artist)
                    if (title.isNotBlank()) {
                        val best = pickBest(lookup(title, artist), t)
                        if (best != null && best.releaseGroupId.isNotBlank()) {
                            val durOk = durationClose(best.lengthMs, t.durationMs)
                            val titleMatch = norm(best.title) == norm(title)
                            if (titleMatch || durOk) {
                                if (CoverArt.fetchIfMissing(
                                        ctx, t.uri, best.releaseGroupId
                                    )
                                ) got++
                            }
                        }
                    }
                }
                coverProgress.value = (i + 1) to all.size
            }
        } finally {
            coverProgress.value = null
            // Un passage arrêté en route ne doit pas conclure sur toute la
            // bibliothèque : il n'en a vu qu'une partie.
            coverMessage.value = when {
                stopRequested ->
                    "Arrêté après $seen morceaux — $got jaquettes récupérées."
                got > 0 -> "$got jaquettes récupérées."
                else -> "Aucune jaquette manquante n'a pu être retrouvée."
            }
        }
        got
    }

    /**
     * Cherche la jaquette d'UN morceau d'après un titre et un artiste
     * saisis à la main, et remplace celle affichée. Le fichier audio n'est
     * jamais modifié.
     *
     * @return message de résultat, à afficher dans le dialogue.
     */
    suspend fun fetchCoverManual(
        t: Track,
        title: String,
        artist: String
    ): String = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext "Appli pas prête."
        val cands = lookup(title.trim(), artist.trim())
        if (cands.isEmpty()) {
            return@withContext lastError.value
                ?: "Aucun résultat MusicBrainz pour ce titre."
        }
        // Meilleur candidat pourvu d'un album : les suivants servent de
        // repli, tous ne sont pas rattachés à une parution.
        val ordered = listOfNotNull(pickBest(cands, t)) +
            cands.sortedByDescending { it.score }
        val rg = ordered.firstOrNull { it.releaseGroupId.isNotBlank() }
            ?.releaseGroupId
            ?: return@withContext "Résultats trouvés, mais aucun album " +
                "rattaché : pas de jaquette à en tirer."
        if (CoverArt.fetch(ctx, t.uri, rg, force = true)) {
            "Jaquette récupérée."
        } else {
            "Cet album n'a pas de jaquette dans Cover Art Archive."
        }
    }

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
            lastError.value = null
            when (handleBySound(store, track)) {
                SoundOutcome.APPLIED, SoundOutcome.DONE -> {}
                SoundOutcome.NOT_FOUND -> handle(store, track)
            }
            // Réseau tombé : ne pas classer le morceau comme examiné
            if (lastError.value == null) markChecked(track.uri)
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
            if (_progress.value != null || coverProgress.value != null ||
                writeProgress.value != null
            ) {
                return@withContext
            }
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
                    lastError.value = null
                    when (handleBySound(store, t)) {
                        SoundOutcome.APPLIED -> applied++
                        SoundOutcome.DONE -> {}
                        SoundOutcome.NOT_FOUND -> if (handle(store, t)) applied++
                    }
                    // Un morceau n'est « examiné » que s'il a vraiment pu
                    // l'être. Sans ce garde-fou, une coupure réseau marquait
                    // toute la bibliothèque comme faite en quelques secondes,
                    // et il fallait tout revérifier à la main.
                    if (lastError.value == null) markChecked(t.uri)
                    _progress.value = Triple(i + 1, list.size, applied)
                    // Sauvegarde régulière : un passage interrompu (appli
                    // tuée, batterie) ne doit pas être entièrement à refaire.
                    // Différée : au plus une écriture toutes les dix secondes.
                    if ((i + 1) % 20 == 0) {
                        save()
                        store.saveSoon()
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

    /** Vrai pendant une remise à zéro (l'écran Tags le dit autrement). */
    val resetting = MutableStateFlow(false)

    private fun markChecked(uri: String) {
        if (checked.add(uri)) checkedCount.value = checked.size
    }

    /**
     * Remet la base de tags à zéro : plus aucune correction, ni automatique
     * ni faite à la main. Chaque morceau retrouve le titre et l'artiste
     * inscrits dans son fichier.
     *
     * Deux passes, dans cet ordre :
     *
     *  1. Les corrections dont on connaît l'origine sont défaites. Si
     *     l'option « écrire les tags dans les fichiers » est active, le
     *     fichier lui-même est réécrit avec son tag d'origine — sinon la
     *     seconde passe le relirait et rétablirait la correction.
     *  2. Tous les morceaux voient leur titre et leur artiste relus dans le
     *     fichier. C'est le seul recours pour ceux dont l'historique est
     *     perdu (il ne garde que les [APPLIED_MAX] dernières corrections),
     *     et ça remet d'aplomb tout ce que la première passe a raté.
     *
     * Les données d'analyse (BPM, tonalité, meilleur passage) ne sont pas
     * touchées : rien à réanalyser.
     *
     * @return le nombre de morceaux dont les tags ont changé.
     */
    suspend fun resetAll(store: TrackStore): Int = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext 0
        if (_progress.value != null || coverProgress.value != null ||
            writeProgress.value != null
        ) {
            return@withContext 0
        }
        stopRequested = false
        resetting.value = true
        // Remise à zéro : les jaquettes téléchargées lors des
        // identifications partent avec elles — les fichiers font foi.
        com.pulsemix.app.ui.ArtworkCache.clearCovers(ctx)
        val undo = applied.value
        val all = store.tracks.value
        val total = undo.size + all.size
        var done = 0
        var changed = 0
        _progress.value = Triple(0, total, 0)
        // Interrompre ne doit pas effacer l'historique de ce qui n'a pas
        // encore été défait : ces corrections deviendraient irrattrapables.
        var undone = 0
        try {
            // 1. Défaire les corrections connues, fichiers compris
            for (s in undo) {
                if (stopRequested) break
                undone++
                if (s.oldTitle.isNotBlank()) {
                    store.update(s.uri) {
                        it.copy(title = s.oldTitle, artist = s.oldArtist)
                    }
                    if (writeToFiles.value) {
                        TagWriter.write(ctx, s.uri, s.oldTitle, s.oldArtist)
                    }
                }
                _progress.value = Triple(++done, total, changed)
            }
            // 2. Relire les tags dans les fichiers, seule vérité restante
            for (t in all) {
                if (stopRequested) break
                val meta = readFileTags(ctx, t.uri)
                if (meta != null) {
                    val (title, artist) = meta
                    val current = store.get(t.uri)
                    if (current != null &&
                        (current.title != title || current.artist != artist)
                    ) {
                        store.update(t.uri) { it.copy(title = title, artist = artist) }
                        changed++
                    }
                }
                _progress.value = Triple(++done, total, changed)
            }
        } finally {
            resetting.value = false
            pending.value = emptyList()
            applied.value = if (stopRequested) undo.drop(undone) else emptyList()
            checked.clear()
            checkedCount.value = 0
            lastError.value = null
            save()
            store.save()
            _progress.value = null
        }
        changed
    }

    /**
     * Titre et artiste tels qu'ils sont écrits dans le fichier. Le nom du
     * fichier sert de titre de repli, comme au scan, pour qu'un morceau
     * sans tag ne se retrouve pas sans nom.
     */
    private fun readFileTags(ctx: Context, uri: String): Pair<String, String>? {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, android.net.Uri.parse(uri))
            val fallback = androidx.documentfile.provider.DocumentFile
                .fromSingleUri(ctx, android.net.Uri.parse(uri))?.name
                ?.substringBeforeLast('.')
                .orEmpty()
            val title = mmr.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_TITLE
            )?.takeIf { it.isNotBlank() } ?: fallback.takeIf { it.isNotBlank() }
            val artist = mmr.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST
            )?.takeIf { it.isNotBlank() }.orEmpty()
            if (title == null) null else title to artist
        } catch (_: Exception) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
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
        val fp = AcoustId.fingerprint(ctx, t.uri, t.durationMs)
            ?: return SoundOutcome.NOT_FOUND
        val cands = AcoustId.lookup(fp)
        val best = pickBest(cands, t) ?: return SoundOutcome.NOT_FOUND
        if (best.title.isBlank()) return SoundOutcome.NOT_FOUND
        return when {
            // L'empreinte est formelle : c'est cet enregistrement
            best.score >= 90 -> {
                if (best.title == t.title &&
                    (best.artist.isBlank() || best.artist == t.artist)
                ) {
                    // Identifié à coup sûr : sa jaquette aussi, même quand
                    // les tags étaient déjà bons.
                    fetchCover(t.uri, best.releaseGroupId)
                    SoundOutcome.DONE
                } else {
                    store.update(t.uri) {
                        it.copy(
                            title = best.title,
                            artist = best.artist.ifBlank { it.artist }
                        )
                    }
                    afterApply(
                        t.uri, best.title, best.artist.ifBlank { t.artist },
                        best.releaseGroupId
                    )
                    applied.value = AppliedTags.record(
                        applied.value,
                        Suggestion(
                            t.uri, t.title, t.artist,
                            best.title, best.artist, best.score,
                            best.releaseGroupId
                        ),
                        APPLIED_MAX
                    )
                    pending.value = pending.value.filter { it.uri != t.uri }
                    SoundOutcome.APPLIED
                }
            }
            // Correspondance partielle : à valider à la main
            best.score >= 60 -> {
                pending.value = pending.value.filter { it.uri != t.uri } +
                    Suggestion(
                        t.uri, t.title, t.artist,
                        best.title, best.artist, best.score,
                        best.releaseGroupId
                    )
                SoundOutcome.DONE
            }
            else -> SoundOutcome.NOT_FOUND
        }
    }

    /** Candidats par empreinte sonore, pour la recherche manuelle. */
    fun searchCandidatesBySound(t: Track): List<Candidate> {
        val ctx = appContext ?: return emptyList()
        val fp = AcoustId.fingerprint(ctx, t.uri, t.durationMs)
        if (fp == null) {
            lastError.value =
                "Impossible de décoder l'audio pour calculer l'empreinte."
            return emptyList()
        }
        return AcoustId.lookup(fp)
    }

    /** Valide une proposition : applique à la bibliothèque (pas au fichier). */
    fun accept(store: TrackStore, s: Suggestion) {
        store.update(s.uri) {
            it.copy(
                title = s.newTitle,
                artist = s.newArtist.ifBlank { it.artist }
            )
        }
        afterApply(s.uri, s.newTitle, s.newArtist, s.releaseGroupId)
        applied.value = AppliedTags.record(applied.value, s, APPLIED_MAX)
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
            val best = pickBest(lookup(qTitle, qArtist), t) ?: continue
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
                afterApply(
                    t.uri, best.title, best.artist.ifBlank { t.artist },
                    best.releaseGroupId
                )
                applied.value = AppliedTags.record(
                    applied.value,
                    Suggestion(
                        t.uri, t.title, t.artist,
                        best.title, best.artist, best.score,
                        best.releaseGroupId
                    ),
                    APPLIED_MAX
                )
                pending.value = pending.value.filter { it.uri != t.uri }
                return true
            }
            // Proposition : plausible, mais on exige que la durée ne
            // contredise pas la correspondance (inconnue tolérée).
            if (proposal == null && best.score >= 60 &&
                (durOk || best.lengthMs <= 0)
            ) {
                proposal = Suggestion(
                    t.uri, t.title, t.artist, best.title, best.artist,
                    best.score, best.releaseGroupId
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
        val lengthMs: Long,
        /** Groupe de parution MusicBrainz : la clé de la jaquette. */
        val releaseGroupId: String = ""
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
        afterApply(t.uri, c.title, c.artist.ifBlank { t.artist }, c.releaseGroupId)
        applied.value = AppliedTags.record(
            applied.value,
            Suggestion(
                t.uri, t.title, t.artist, c.title, c.artist, c.score,
                c.releaseGroupId
            ),
            APPLIED_MAX
        )
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
        // La jaquette téléchargée appartenait à l'identification qu'on
        // annule : la garder afficherait la pochette du mauvais album, en
        // masquant pour toujours celle embarquée dans le fichier.
        appContext?.let { ctx ->
            writeScope.launch {
                com.pulsemix.app.ui.ArtworkCache.removeCover(ctx, s.uri)
            }
        }
        applied.value = AppliedTags.remove(applied.value, s.uri)
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
        applied.value = AppliedTags.record(
            applied.value,
            Suggestion(t.uri, t.title, t.artist, newTitle, newArtist, 0),
            APPLIED_MAX
        )
        pending.value = pending.value.filter { it.uri != t.uri }
        // Tags saisis à la main : le morceau est réglé, un balayage
        // ultérieur n'a pas à revenir dessus.
        markChecked(t.uri)
        save()
    }

    /**
     * Écart de score en deçà duquel deux candidats sont tenus pour aussi
     * probables l'un que l'autre. AcoustID donne le MÊME score à tous les
     * enregistrements d'une même empreinte — c'est le même son — et des
     * scores voisins d'une empreinte à l'autre. En dessous de ce seuil, le
     * son ne départage rien et c'est au nom du fichier de le faire.
     */
    private const val SCORE_TIE = 3

    /**
     * Meilleur candidat : durée compatible d'abord, puis score, et à score
     * équivalent celui dont le titre et l'artiste ressemblent le plus au
     * nom du fichier.
     *
     * Sans ce dernier critère, on prenait au hasard parmi des candidats
     * strictement à égalité : d'où des « (Remastered 2011) » ou des
     * « Various Artists » de compilation appliqués silencieusement à la
     * place du morceau tel qu'il est sur le disque.
     */
    private fun pickBest(cands: List<Candidate>, t: Track): Candidate? {
        if (cands.isEmpty()) return null
        val durOk = cands.filter { durationClose(it.lengthMs, t.durationMs) }
        val pool = durOk.ifEmpty { cands }
        val fileTokens = NameMatch.tokens(NameMatch.fileNameOf(t.uri))
        // Aucun nom de fichier exploitable : le score seul décide, comme avant
        if (fileTokens.isEmpty()) return pool.maxByOrNull { it.score }
        val topScore = pool.maxOf { it.score }
        return pool
            .filter { it.score >= topScore - SCORE_TIE }
            .maxWithOrNull(
                // À proximité égale — nom de fichier muet ou inexploitable —
                // c'est le score qui tranche, comme avant.
                compareBy<Candidate>(
                    { NameMatch.similarityToFile(fileTokens, it.title, it.artist) },
                    { it.score }
                )
            )
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
                        r.optLong("length", 0L),
                        // Première parution rattachée : sa jaquette
                        r.optJSONArray("releases")?.optJSONObject(0)
                            ?.optJSONObject("release-group")
                            ?.optString("id", "") ?: ""
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

    /**
     * Version du moteur d'identification. Un verdict « déjà examiné » ne
     * vaut que pour le moteur qui l'a rendu : quand celui-ci est corrigé,
     * les verdicts d'avant sont oubliés, sinon le correctif n'atteindrait
     * jamais les morceaux déjà passés — et la correction resterait
     * invisible.
     *
     * 2 : la durée annoncée à AcoustID est celle du morceau entier ; avant,
     * l'identification sonore ne rendait jamais rien.
     */
    private const val CHECKED_ENGINE = 2

    private fun readChecked(): Set<String> = try {
        val root = JSONObject(checkedFile().readText())
        if (root.optInt("engine", 1) < CHECKED_ENGINE) {
            emptySet()
        } else {
            val arr = root.optJSONArray("uris") ?: JSONArray()
            val set = HashSet<String>(arr.length())
            for (i in 0 until arr.length()) set.add(arr.getString(i))
            set
        }
    } catch (_: Exception) {
        // Ancien format (tableau nu) : verdicts d'un moteur dépassé
        emptySet()
    }

    private fun writeChecked() {
        try {
            checkedFile().writeText(
                JSONObject()
                    .put("engine", CHECKED_ENGINE)
                    .put("uris", JSONArray(checked.toList()))
                    .toString()
            )
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
                    o.optInt("score", 0),
                    o.optString("releaseGroup", "")
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
                        .put("releaseGroup", s.releaseGroupId)
                )
            }
            f.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    private fun load() {
        pending.value = readList(file())
        applied.value = AppliedTags.collapse(readList(appliedFile()))
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
