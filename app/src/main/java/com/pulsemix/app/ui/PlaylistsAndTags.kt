package com.pulsemix.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
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
    val tracks by vm.tracks.collectAsStateWithLifecycle()

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
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

/**
 * Écran « Importer depuis une URL » : colle un lien (fichier audio direct,
 * item Internet Archive, flux podcast RSS, ou page d'une plateforme vidéo /
 * radio prise en charge par yt-dlp) ; l'audio est téléchargé dans le dossier
 * déjà scanné par le lecteur, puis analysé automatiquement.
 *
 * Note affichée : la récupération et l'usage des contenus relèvent de la
 * responsabilité de l'utilisateur.
 */
@Composable
fun ImportUrlScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val state by vm.importState.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    val hasFolder = vm.hasFolder()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubHeader("Importer depuis une URL", onBack)
        Spacer(Modifier.height(4.dp))
        Text(
            "Colle un lien direct vers un fichier audio, un item Internet " +
                "Archive, un flux podcast RSS, ou la page d'une plateforme " +
                "vidéo/radio (yt-dlp intégré). L'audio est téléchargé dans " +
                "ton dossier de musique, puis analysé.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Tu es responsable de ce que tu télécharges et de son usage.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
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

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("URL") },
            placeholder = { Text("https://…") },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        val working = state is com.pulsemix.app.library.UrlImporter.State.Working
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement
            .spacedBy(8.dp)) {
            Button(
                onClick = { vm.importFromUrl(url) },
                enabled = url.isNotBlank() && !working
            ) { Text("Importer") }
            if (working) {
                OutlinedButton(onClick = { vm.stopImport() }) { Text("Stop") }
            }
        }

        Spacer(Modifier.height(12.dp))
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
                    color = MaterialTheme.colorScheme.error
                )
            }
            else -> {}
        }

        Spacer(Modifier.weight(1f))
        // Les sites changent souvent : mettre à jour yt-dlp est le premier
        // réflexe quand l'extraction d'une page se met à échouer.
        if (!working) {
            TextButton(onClick = { vm.updateImportEngine() }) {
                Text("Mettre à jour l'extracteur (yt-dlp)")
            }
        }
    }
}
