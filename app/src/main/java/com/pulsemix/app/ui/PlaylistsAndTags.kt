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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    var showApplied by remember { mutableStateOf(false) }
    // Recherche manuelle : depuis une proposition incertaine, ou depuis
    // n'importe quel morceau via le sélecteur ci-dessous
    var manualFor by remember { mutableStateOf<com.pulsemix.app.data.Track?>(null) }
    var pickTrack by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubHeader("Tags en ligne", onBack)
        Spacer(Modifier.height(4.dp))
        Text(
            "Recherche les vrais titre et artiste de chaque morceau sur " +
                "MusicBrainz. Les corrections sûres sont appliquées " +
                "automatiquement ; les incertaines s'ajoutent à la liste " +
                "ci-dessous, à valider à la main. Les fichiers audio ne sont " +
                "jamais modifiés.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(8.dp))

        val p = prog
        if (p == null) {
            Button(
                onClick = { vm.fetchTagsAll() },
                enabled = tracks.isNotEmpty()
            ) { Text("Vérifier toute la bibliothèque (${tracks.size})") }
            Text(
                "~1 morceau par seconde (limite du service). Tu peux aussi " +
                    "vérifier un seul morceau depuis son menu ⋮.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        } else {
            val (done, total, applied) = p
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Recherche : $done/$total — $applied corrigés auto",
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
        tagError?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row {
            TextButton(
                onClick = { pickTrack = true },
                enabled = tracks.isNotEmpty()
            ) { Text("Corriger un morceau…") }
            if (appliedList.isNotEmpty()) {
                TextButton(onClick = { showApplied = true }) {
                    Text("Morceaux corrigés (${appliedList.size})")
                }
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
        if (pending.isEmpty()) {
            Text(
                "Rien à valider.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            LazyColumn {
                items(pending, key = { it.uri }) { s ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            listOf(s.oldTitle, s.oldArtist)
                                .filter { it.isNotBlank() }
                                .joinToString(" — "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                                .copy(alpha = 0.55f),
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
                            // Ni l'un ni l'autre : chercher soi-même avec
                            // des infos corrigées et choisir le bon résultat
                            TextButton(onClick = {
                                manualFor = tracks.find { it.uri == s.uri }
                            }) { Text("Affiner…") }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }

    // ---------------------------------------- recherche manuelle depuis la liste
    manualFor?.let { t ->
        ManualTagDialog(vm, t, onClose = { manualFor = null })
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

    // ------------------------------------- historique des corrections faites
    if (showApplied) {
        AlertDialog(
            onDismissRequest = { showApplied = false },
            title = { Text("Morceaux corrigés (${appliedList.size})") },
            text = {
                LazyColumn {
                    items(appliedList.size) { i ->
                        val s = appliedList[i]
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                listOf(s.oldTitle, s.oldArtist)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" — "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.55f),
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
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showApplied = false }) { Text("Fermer") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.clearTagApplied()
                    showApplied = false
                }) {
                    Text("Vider la liste", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
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
