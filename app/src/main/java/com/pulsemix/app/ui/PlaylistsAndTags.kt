package com.pulsemix.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulsemix.app.PlayerViewModel

/** En-tête d'un sous-écran : retour + titre. */
@Composable
private fun SubHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, "Retour")
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Écran de choix des playlists : charger (= lire comme file de lecture),
 * exporter en M3U, supprimer. La création se fait depuis la file de lecture
 * (« Enregistrer… ») ou un résultat de recherche (« En playlist… »).
 */
@Composable
fun PlaylistsScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubHeader("Playlists", onBack)
        Spacer(Modifier.height(8.dp))
        if (playlists.isEmpty()) {
            Text(
                "Aucune playlist pour l'instant.\n\n" +
                    "Pour en créer une :\n" +
                    "• depuis le lecteur : ouvre la file de lecture puis " +
                    "« Enregistrer… » ;\n" +
                    "• depuis la bibliothèque : fais une recherche puis " +
                    "« En playlist… ».",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        } else {
            Text(
                "Appuie sur une playlist pour la charger comme file de lecture.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn {
                items(playlists, key = { it.name }) { pl ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.playPlaylist(pl); onBack() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                pl.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${pl.uris.size} morceaux",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.6f)
                            )
                        }
                        IconButton(onClick = { vm.playPlaylist(pl); onBack() }) {
                            Icon(Icons.Rounded.PlayArrow, "Lire")
                        }
                        TextButton(onClick = { vm.exportPlaylist(pl) }) { Text("m3u") }
                        IconButton(onClick = { vm.deletePlaylist(pl.name) }) {
                            Icon(
                                Icons.Rounded.Close, "Supprimer",
                                tint = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.5f)
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

/**
 * Écran de gestion des tags en ligne : lancement de la recherche
 * (bibliothèque entière), progression, et liste des propositions
 * incertaines à valider une par une.
 */
@Composable
fun TagsScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val pending by vm.tagPending.collectAsStateWithLifecycle()
    val prog by vm.tagProgress.collectAsStateWithLifecycle()
    val tagError by vm.tagError.collectAsStateWithLifecycle()
    val appliedList by vm.tagApplied.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    // Afficher aussi les corrections déjà faites dans la liste, pour
    // pouvoir les revoir / re-corriger
    var showFixed by remember { mutableStateOf(false) }
    // Recherche manuelle : depuis une proposition incertaine, ou depuis
    // n'importe quel morceau via le sélecteur ci-dessous
    var manualFor by remember { mutableStateOf<com.pulsemix.app.data.Track?>(null) }
    // Vrai quand la recherche doit se lancer dès l'ouverture (bouton
    // « Chercher » d'une proposition incertaine)
    var manualAuto by remember { mutableStateOf(false) }
    var pickTrack by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val writeToFiles by vm.writeTagsToFiles.collectAsStateWithLifecycle()
    val resetMessage by vm.tagResetMessage.collectAsStateWithLifecycle()

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Tout remettre à zéro ?") },
            text = {
                val surLesFichiers = if (writeToFiles) {
                    "Comme l'option « écrire les tags dans les fichiers » est " +
                        "active, les fichiers eux-mêmes sont réécrits avec " +
                        "leurs tags d'origine. C'est long : un fichier est " +
                        "recopié par correction à défaire."
                } else {
                    "Les fichiers audio ne sont pas touchés."
                }
                Text(
                    "Toutes les corrections de tags sont effacées — celles " +
                        "trouvées automatiquement comme celles que tu as " +
                        "faites à la main. Chaque morceau retrouve le titre " +
                        "et l'artiste inscrits dans son fichier.\n\n" +
                        surLesFichiers + "\n\n" +
                        "Les analyses (BPM, tonalité, meilleur passage) sont " +
                        "conservées : rien à réanalyser."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    vm.resetAllTags()
                }) {
                    Text("Tout remettre à zéro", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Annuler") }
            }
        )
    }

    resetMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.clearTagResetMessage() },
            title = { Text("Remise à zéro terminée") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { vm.clearTagResetMessage() }) { Text("OK") }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubHeader("Tags en ligne", onBack)
        Spacer(Modifier.height(4.dp))
        Text(
            "Identifie chaque morceau d'abord par son empreinte sonore " +
                "(AcoustID écoute le son, peu importe les tags), puis par " +
                "recherche texte MusicBrainz si le son ne suffit pas. Les " +
                "corrections sûres sont appliquées automatiquement ; les " +
                "incertaines s'ajoutent à la liste ci-dessous. Les fichiers " +
                "audio ne sont jamais modifiés.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(8.dp))

        val coverProg by vm.coverProgress.collectAsStateWithLifecycle()
        val p = prog
        if (p == null) {
            val done by vm.tagChecked.collectAsStateWithLifecycle()
            val remaining = (tracks.size - done).coerceAtLeast(0)
            Button(
                onClick = { vm.fetchTagsAll() },
                // Le service ne mène qu'un passage à la fois
                enabled = remaining > 0 && coverProg == null
            ) {
                Text(
                    if (done > 0) "Vérifier les $remaining restants"
                    else "Vérifier toute la bibliothèque (${tracks.size})"
                )
            }
            Text(
                "~1 morceau par seconde (limite du service). Continue en " +
                    "arrière-plan, appli fermée ou écran éteint — la " +
                    "notification suit l'avancement. Les morceaux déjà " +
                    "examinés sont sautés : une relance ne refait que les " +
                    "nouveaux. Tu peux aussi vérifier un seul morceau " +
                    "depuis son menu ⋮.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            if (done > 0) {
                TextButton(
                    onClick = { vm.recheckAllTags() },
                    // Le service n'accepte qu'un passage à la fois : un
                    // appui pendant le passage jaquettes serait perdu
                    enabled = coverProg == null
                ) {
                    Text("Tout revérifier depuis zéro ($done déjà examinés)")
                }
            }
        } else {
            val (done, total, applied) = p
            val resetting by vm.tagResetting.collectAsStateWithLifecycle()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (resetting) "Remise à zéro : $done/$total — $applied rétablis"
                    else "Recherche : $done/$total — $applied corrigés auto",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { vm.stopTagFetch() }) { Text("Stop") }
            }
            LinearProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ------------------------------------------------ jaquettes manquantes
        val writeProg by vm.tagWriteProgress.collectAsStateWithLifecycle()
        val cp = coverProg
        if (cp == null) {
            TextButton(
                onClick = { vm.fetchAllCovers() },
                // Un seul passage à la fois (tags, report, jaquettes)
                enabled = tracks.isNotEmpty() && p == null && writeProg == null
            ) { Text("Chercher toutes les jaquettes manquantes") }
            Text(
                "Pour chaque morceau sans pochette, retrouve l'album sur " +
                    "MusicBrainz d'après ses tags et télécharge la jaquette " +
                    "officielle (Cover Art Archive). En arrière-plan, comme " +
                    "la vérification des tags. Les fichiers audio ne sont " +
                    "pas modifiés.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        } else {
            val (cDone, cTotal) = cp
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Jaquettes : $cDone/$cTotal",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { vm.stopTagFetch() }) { Text("Stop") }
            }
            LinearProgressIndicator(
                progress = { if (cTotal > 0) cDone.toFloat() / cTotal else 0f },
                modifier = Modifier.fillMaxWidth()
            )
        }
        val coverMsg by vm.coverMessage.collectAsStateWithLifecycle()
        coverMsg?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.clearCoverMessage() }) { Text("OK") }
            }
        }
        tagError?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        TextButton(
            onClick = { pickTrack = true },
            enabled = tracks.isNotEmpty()
        ) { Text("Corriger un morceau…") }

        if (prog == null) {
            TextButton(
                onClick = { confirmReset = true },
                enabled = tracks.isNotEmpty()
            ) {
                Text(
                    "Tout remettre à zéro…",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "À valider (${pending.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (pending.isNotEmpty()) {
                TextButton(onClick = { pending.forEach { vm.rejectTag(it) } }) {
                    Text("Tout refuser", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        // Toggle : montrer aussi les corrections déjà appliquées, pour les
        // vérifier à l'oreille et les refaire si besoin
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { showFixed = !showFixed }
        ) {
            Switch(checked = showFixed, onCheckedChange = { showFixed = it })
            Spacer(Modifier.width(8.dp))
            Text(
                "Afficher les corrigés (${appliedList.size})",
                style = MaterialTheme.typography.labelMedium
            )
        }
        if (pending.isEmpty() && !(showFixed && appliedList.isNotEmpty())) {
            Text(
                "Rien à valider.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            LazyColumn {
                items(pending, key = { "p:" + it.uri }) { s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            TagChangeTexts(s)
                            Row {
                                TextButton(onClick = { vm.acceptTag(s) }) {
                                    Text("Accepter")
                                }
                                TextButton(onClick = { vm.rejectTag(s) }) {
                                    Text(
                                        "Refuser",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                // Ni l'un ni l'autre : voir la liste des
                                // possibilités et choisir le bon résultat
                                TextButton(onClick = {
                                    manualAuto = true
                                    manualFor = tracks.find { it.uri == s.uri }
                                }) { Text("Chercher") }
                            }
                        }
                        // Écouter le morceau pour vérifier l'artiste
                        IconButton(onClick = {
                            tracks.find { it.uri == s.uri }?.let(vm::playTrack)
                        }) { Icon(Icons.Rounded.PlayArrow, "Écouter") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
                if (showFixed && appliedList.isNotEmpty()) {
                    item(key = "fixed-header") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 12.dp)
                        ) {
                            Text(
                                "Corrigés (${appliedList.size})",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { vm.clearTagApplied() }) {
                                Text(
                                    "Vider",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    itemsIndexed(appliedList, key = { i, _ -> "a:$i" }) { _, s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                TagChangeTexts(s)
                                Row {
                                    // Correction douteuse ? Revoir la liste
                                    // des possibilités, en choisir une autre.
                                    TextButton(onClick = {
                                        manualAuto = true
                                        manualFor = tracks.find { it.uri == s.uri }
                                    }) { Text("Chercher") }
                                    // Ou revenir aux tags d'avant correction
                                    TextButton(onClick = { vm.revertTag(s) }) {
                                        Text(
                                            "Rétablir l'original",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = {
                                tracks.find { it.uri == s.uri }?.let(vm::playTrack)
                            }) { Icon(Icons.Rounded.PlayArrow, "Écouter") }
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------- recherche manuelle depuis la liste
    manualFor?.let { t ->
        ManualTagDialog(vm, t, autoSearch = manualAuto, onClose = {
            manualFor = null
            manualAuto = false
        })
    }

    // -------------------------- choisir n'importe quel morceau à corriger
    if (pickTrack) {
        var filter by remember { mutableStateOf("") }
        val filtered = remember(filter, tracks) {
            val q = filter.trim()
            (if (q.isBlank()) tracks
            else tracks.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.artist.contains(q, ignoreCase = true)
            }).take(60)
        }
        AlertDialog(
            onDismissRequest = { pickTrack = false },
            title = { Text("Quel morceau corriger ?") },
            text = {
                Column {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Filtrer (titre ou artiste)") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(filtered, key = { it.uri }) { t ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pickTrack = false
                                        manualAuto = false
                                        manualFor = t
                                    }
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(
                                    t.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (t.artist.isNotBlank()) {
                                    Text(
                                        t.artist,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                            .copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickTrack = false }) { Text("Fermer") }
            }
        )
    }
}

/** Lignes « ancien — artiste » puis « → nouveau — artiste » d'une correction. */
@Composable
private fun TagChangeTexts(s: com.pulsemix.app.library.TagFixer.Suggestion) {
    Text(
        listOf(s.oldTitle, s.oldArtist)
            .filter { it.isNotBlank() }
            .joinToString(" — "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Text(
        "→ " + listOf(s.newTitle, s.newArtist)
            .filter { it.isNotBlank() }
            .joinToString(" — "),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * Écran « Importer depuis une URL » :
 *  - colle un lien (YouTube/SoundCloud/Bandcamp, fichier audio direct, item
 *    Internet Archive, flux podcast RSS) ; ou
 *  - cherche directement sur YouTube et télécharge un résultat d'un tap.
 * L'audio arrive dans le dossier scanné par le lecteur, puis est analysé.
 * En bas, « Mettre à jour yt-dlp » récupère la dernière version du moteur
 * en temps réel (utile quand YouTube change son site et casse l'extraction).
 *
 * Note affichée : la récupération et l'usage des contenus relèvent de la
 * responsabilité de l'utilisateur.
 */
@Composable
fun ImportUrlScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val state by vm.importState.collectAsStateWithLifecycle()
    val results by vm.ytResults.collectAsStateWithLifecycle()
    val searching by vm.ytSearching.collectAsStateWithLifecycle()
    val searchError by vm.ytSearchError.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val hasFolder = vm.hasFolder()
    val working = state is com.pulsemix.app.library.UrlImporter.State.Working

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubHeader("Importer depuis une URL", onBack)
        Spacer(Modifier.height(4.dp))
        Text(
            "Colle un lien (YouTube, SoundCloud, Bandcamp, fichier audio, " +
                "Internet Archive, podcast RSS) ou cherche sur YouTube. " +
                "L'audio est téléchargé en MP3 dans ton dossier de musique, " +
                "puis analysé. Tu es responsable de ce que tu télécharges " +
                "et de son usage.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(10.dp))

        if (!hasFolder) {
            Text(
                "Choisis d'abord un dossier de musique dans la bibliothèque : " +
                    "c'est là que les fichiers importés seront rangés.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            return@Column
        }

        // ------------------------------------------------------ lien direct
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("URL") },
            placeholder = { Text("https://…") },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = androidx.compose.foundation.layout
                .Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { vm.importFromUrl(url) },
                enabled = url.isNotBlank() && !working
            ) { Text("Importer") }
            if (working) {
                OutlinedButton(onClick = { vm.stopImport() }) { Text("Stop") }
            }
        }

        // ----------------------------------------------- état de l'import
        Spacer(Modifier.height(10.dp))
        when (val s = state) {
            is com.pulsemix.app.library.UrlImporter.State.Working -> {
                Text(
                    s.message,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(
                    progress = {
                        if (s.total > 0) s.done.toFloat() / s.total else 0f
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            is com.pulsemix.app.library.UrlImporter.State.Done -> {
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = { url = ""; vm.resetImport() }) {
                    Text("Importer un autre lien")
                }
            }
            is com.pulsemix.app.library.UrlImporter.State.Error -> {
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            else -> {}
        }

        // ------------------------------------------------ recherche YouTube
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Chercher sur YouTube") },
            placeholder = { Text("titre, artiste…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = { vm.searchYoutube(query) }
            ),
            trailingIcon = {
                IconButton(
                    onClick = {
                        if (searching) vm.cancelYoutubeSearch()
                        else vm.searchYoutube(query)
                    },
                    enabled = searching || query.isNotBlank()
                ) {
                    Icon(
                        if (searching) Icons.Rounded.Close
                        else Icons.Rounded.Search,
                        if (searching) "Annuler" else "Chercher"
                    )
                }
            }
        )
        if (searching) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        searchError?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(results, key = { it.videoId }) { r ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Un tap sur la ligne remplit le champ URL (pour
                        // vérifier le lien avant de lancer, si on veut).
                        .clickable { url = r.url }
                        .padding(vertical = 6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            r.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            listOf(r.channel, r.durationText)
                                .filter { it.isNotBlank() }
                                .joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                                .copy(alpha = 0.6f)
                        )
                    }
                    // Téléchargement direct du résultat, sans coller de lien
                    IconButton(
                        onClick = {
                            url = r.url
                            vm.importFromUrl(r.url)
                        },
                        enabled = !working
                    ) {
                        Icon(Icons.Rounded.Download, "Télécharger")
                    }
                }
                HorizontalDivider()
            }
        }

        // --------------------------------------- mise à jour du moteur
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { vm.updateImportEngine() },
                enabled = !working
            ) { Text("Mettre à jour yt-dlp") }
            Text(
                "si YouTube casse l'extraction",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
            )
        }
    }
}
