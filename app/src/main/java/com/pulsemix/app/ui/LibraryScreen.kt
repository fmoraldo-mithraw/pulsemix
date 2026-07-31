package com.pulsemix.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.pulsemix.app.data.Track
import com.pulsemix.app.mix.MixEngine
import kotlin.math.abs

private enum class SortMode(val label: String) {
    TITRE("Titre"), BPM("BPM"), ENERGIE("Énergie"), CLE("Clé")
}

@Composable
fun LibraryScreen(
    vm: PlayerViewModel,
    modifier: Modifier = Modifier,
    onPickFolder: () -> Unit
) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val progress by vm.scanProgress.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val current by vm.currentTrack.collectAsStateWithLifecycle()

    var search by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.TITRE) }
    var compatOnly by remember { mutableStateOf(false) }
    var failedOnly by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var optionsFor by remember { mutableStateOf<Track?>(null) }
    val playlists by vm.playlists.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Bibliothèque",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showStats = true }) {
                Icon(Icons.Rounded.BarChart, "Statistiques")
            }
        }
        Spacer(Modifier.height(8.dp))

        // ------------------------------------------------------------ dossiers
        for (f in folders) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Uri.parse(f).lastPathSegment?.substringAfterLast(':')?.ifBlank { f } ?: f,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = { vm.removeFolder(f) }) {
                    Icon(
                        Icons.Rounded.Close, "Retirer le dossier",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // ----------------------------------------------------------- playlists
        for (pl in playlists) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "♪ ${pl.name} (${pl.uris.size})",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = { vm.playPlaylist(pl) }) {
                    Icon(Icons.Rounded.PlayArrow, "Lire la playlist")
                }
                TextButton(onClick = { vm.exportPlaylist(pl) }) { Text("m3u") }
                IconButton(onClick = { vm.deletePlaylist(pl.name) }) {
                    Icon(
                        Icons.Rounded.Close, "Supprimer la playlist",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }

        val p = progress
        val scanning = p != null
        val unanalyzed = tracks.count { !it.analyzed }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickFolder, enabled = !scanning) {
                Text(if (folders.isEmpty()) "Choisir un dossier" else "Ajouter un dossier")
            }
            if (folders.isNotEmpty() && scanning) {
                OutlinedButton(onClick = { vm.stopScan() }) {
                    Text("Stopper l'analyse")
                }
            }
        }
        if (folders.isNotEmpty() && !scanning) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.rescan() }) {
                    Text(
                        if (unanalyzed > 0) "Reprendre l'analyse ($unanalyzed restants)"
                        else "Analyser à nouveau"
                    )
                }
                OutlinedButton(onClick = { vm.rescanFromScratch() }) {
                    Text("Tout réanalyser")
                }
            }
        }

        if (p != null) {
            Spacer(Modifier.height(12.dp))
            if (p.total > 0) {
                LinearProgressIndicator(
                    progress = { p.done.toFloat() / p.total },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Analyse ${p.done}/${p.total} — ${p.currentName}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Préparation de l'analyse — parcours des dossiers…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (tracks.isEmpty()) {
            Text(
                "Aucun morceau pour l'instant. Choisis un dossier contenant tes " +
                    "fichiers audio (mp3, m4a, flac, ogg, wav…) : chaque morceau " +
                    "sera analysé une seule fois.\n\n" +
                    "Après une réinstallation, re-choisis simplement le même " +
                    "dossier : les analyses sont restaurées automatiquement " +
                    "depuis la sauvegarde qu'il contient (PulseMix.library.json).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        } else {
            // ------------------------------------------------ recherche & tri
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Rechercher un titre ou un artiste…") },
                singleLine = true
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (m in SortMode.entries) {
                    FilterChip(
                        selected = sortMode == m,
                        onClick = { sortMode = m },
                        label = { Text(m.label) }
                    )
                }
                FilterChip(
                    selected = compatOnly,
                    onClick = { compatOnly = !compatOnly },
                    enabled = current != null,
                    label = { Text("Compatibles") }
                )
                FilterChip(
                    selected = failedOnly,
                    onClick = { failedOnly = !failedOnly },
                    label = { Text("Non analysés ($unanalyzed)") }
                )
            }
            Spacer(Modifier.height(6.dp))

            val cur = current
            val displayed = tracks
                .filter { !failedOnly || !it.analyzed }
                .filter {
                    search.isBlank() ||
                        it.title.contains(search, ignoreCase = true) ||
                        it.artist.contains(search, ignoreCase = true)
                }
                .filter {
                    if (!compatOnly || cur == null || !cur.analyzed) true
                    else {
                        val bpmOk = cur.bpm > 0f && it.bpm > 0f && minOf(
                            abs(it.bpm - cur.bpm),
                            abs(it.bpm * 2 - cur.bpm),
                            abs(it.bpm / 2 - cur.bpm)
                        ) / cur.bpm <= 0.08f
                        bpmOk && MixEngine.camelotScore(cur.camelot, it.camelot) >= 0.8f
                    }
                }
                .let { list ->
                    when (sortMode) {
                        SortMode.TITRE -> list.sortedBy { it.title.lowercase() }
                        SortMode.BPM -> list.sortedBy { it.bpm }
                        SortMode.ENERGIE -> list.sortedByDescending { it.energyPeak }
                        SortMode.CLE -> list.sortedBy { camelotSortKey(it.camelot) }
                    }
                }

            Text(
                "${displayed.size}/${tracks.size} morceaux · " +
                    "${tracks.count { it.analyzed }} analysés",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            // Résultat de recherche/filtre : lecture directe comme file, et
            // sauvegarde en playlist depuis l'interface (aucun fichier à gérer)
            val filterActive = search.isNotBlank() || compatOnly || failedOnly
            if (filterActive && displayed.isNotEmpty()) {
                var showSaveSearch by remember { mutableStateOf(false) }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.playTracks(displayed) }) {
                        Text("▶ Lire ces ${displayed.size} morceaux")
                    }
                    OutlinedButton(onClick = { showSaveSearch = true }) {
                        Text("En playlist…")
                    }
                }
                if (showSaveSearch) {
                    var plName by remember {
                        mutableStateOf(search.trim().ifBlank { "Sélection" })
                    }
                    AlertDialog(
                        onDismissRequest = { showSaveSearch = false },
                        title = { Text("Enregistrer comme playlist") },
                        text = {
                            Column {
                                OutlinedTextField(
                                    value = plName,
                                    onValueChange = { plName = it },
                                    label = { Text("Nom de la playlist") },
                                    singleLine = true
                                )
                                Text(
                                    "${displayed.size} morceaux, dans l'ordre affiché.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                        .copy(alpha = 0.6f)
                                )
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                if (plName.isNotBlank()) {
                                    vm.saveTracksAsPlaylist(plName.trim(), displayed)
                                }
                                showSaveSearch = false
                            }) { Text("Enregistrer") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSaveSearch = false }) {
                                Text("Annuler")
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            val maxEnergy = tracks.maxOfOrNull { it.energyPeak }?.takeIf { it > 0f } ?: 1f
            LazyColumn {
                items(displayed, key = { it.uri }) { track ->
                    TrackRow(
                        track, maxEnergy,
                        onClick = { vm.playTrack(track) },
                        onMore = { optionsFor = track }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }

    // ------------------------------------- menu du morceau (partagé lecteur)
    optionsFor?.let { opt ->
        TrackOptionsDialogs(vm, tracks, opt) { optionsFor = null }
    }

    // ------------------------------------------------------------ statistiques
    if (showStats) {
        val analyzed = tracks.filter { it.analyzed && it.bpm > 0f }
        AlertDialog(
            onDismissRequest = { showStats = false },
            title = { Text("Statistiques") },
            text = {
                Column {
                    Text("${tracks.size} morceaux, ${analyzed.size} analysés")
                    analyzed.takeIf { it.isNotEmpty() }?.let { list ->
                        Text("BPM moyen : ${"%.0f".format(list.map { it.bpm }.average())}")
                        Spacer(Modifier.height(8.dp))
                        Text("Répartition des tempos", fontWeight = FontWeight.SemiBold)
                        val bands = listOf(
                            "Calme (<95)" to list.count { it.bpm < 95f },
                            "Groove (95-115)" to list.count { it.bpm >= 95f && it.bpm < 115f },
                            "Dance (115-135)" to list.count { it.bpm >= 115f && it.bpm < 135f },
                            "Intense (>135)" to list.count { it.bpm >= 135f }
                        )
                        val maxBand = bands.maxOf { it.second }.coerceAtLeast(1)
                        for ((label, count) in bands) {
                            Text("$label — $count", style = MaterialTheme.typography.labelSmall)
                            LinearProgressIndicator(
                                progress = { count.toFloat() / maxBand },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Tonalités les plus présentes", fontWeight = FontWeight.SemiBold)
                        val keys = list.groupBy { it.camelot }
                            .filterKeys { it != "--" }
                            .entries.sortedByDescending { it.value.size }
                            .take(5)
                        for (e in keys) {
                            Text(
                                "${e.key} (${e.value.firstOrNull()?.keyName ?: ""}) — " +
                                    "${e.value.size} morceaux",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStats = false }) { Text("Fermer") }
            }
        )
    }
}

private fun camelotSortKey(camelot: String): Int {
    if (camelot.length < 2 || camelot == "--") return 99
    val n = camelot.dropLast(1).toIntOrNull() ?: return 99
    return n * 2 + if (camelot.last() == 'B') 1 else 0
}

@Composable
private fun TrackRow(
    track: Track,
    maxEnergy: Float,
    onClick: () -> Unit,
    onMore: () -> Unit
) {
    val alpha = if (track.excluded) 0.4f else 1f
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackArtwork(
            uri = track.uri,
            modifier = Modifier.size(44.dp),
            corner = 8.dp,
            targetPx = 96,
            fallback = "🎵",
            fallbackStyle = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.favorite) {
                    Icon(
                        Icons.Rounded.Star, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                )
            }
            val info = buildString {
                if (track.artist.isNotBlank()) append(track.artist).append(" · ")
                if (track.analyzed) {
                    append("${track.bpm} BPM")
                    if (track.bpmLocked) append(" 🔒")
                    append(" · ${track.keyName} (${track.camelot})")
                } else {
                    append("non analysé")
                }
                if (track.excluded) append(" · exclu des mix")
                if (track.durationMs > 0) {
                    val m = track.durationMs / 60000
                    val s = (track.durationMs / 1000) % 60
                    append(" · %d:%02d".format(m, s))
                }
            }
            Text(
                info,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f * alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Barre d'énergie
        Box(
            Modifier
                .width(36.dp)
                .height(6.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(3.dp)
                )
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(end = (36 * (1f - (track.energyPeak / maxEnergy)
                        .coerceIn(0f, 1f))).dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(3.dp)
                    )
            )
        }
        IconButton(onClick = onMore) {
            Icon(
                Icons.Rounded.MoreVert, "Options",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
