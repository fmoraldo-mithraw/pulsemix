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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
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
import androidx.compose.runtime.saveable.rememberSaveable
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

private enum class SortMode(val label: String, val naturalDesc: Boolean = false) {
    TITRE("Titre"), BPM("BPM"), ENERGIE("Énergie", naturalDesc = true), CLE("Clé")
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

    // rememberSaveable : recherche, tri et filtres survivent à la rotation —
    // avec remember seul, un pivot de l'écran remettait tout à zéro.
    var search by rememberSaveable { mutableStateOf("") }
    // SortMode est un enum, non sauvegardable tel quel dans un Bundle : on
    // stocke son nom (String) et on reconvertit, plus simple qu'un Saver.
    var sortModeName by rememberSaveable { mutableStateOf(SortMode.TITRE.name) }
    val sortMode = SortMode.entries.firstOrNull { it.name == sortModeName }
        ?: SortMode.TITRE
    // Re-cliquer le tri actif inverse son ordre (flèche sur la puce)
    var sortReversed by rememberSaveable { mutableStateOf(false) }
    var compatOnly by rememberSaveable { mutableStateOf(false) }
    var failedOnly by rememberSaveable { mutableStateOf(false) }
    var optionsFor by remember { mutableStateOf<Track?>(null) }

    // Sous-écrans : playlists, tags en ligne, import, statistiques (et ses
    // annexes tonalités/doublons) en ligne — la bibliothèque reste légère
    var subScreen by rememberSaveable { mutableStateOf(0) }
    if (subScreen == 1) {
        Box(modifier.fillMaxSize()) { PlaylistsScreen(vm, onBack = { subScreen = 0 }) }
        return
    }
    if (subScreen == 2) {
        Box(modifier.fillMaxSize()) { TagsScreen(vm, onBack = { subScreen = 0 }) }
        return
    }
    if (subScreen == 3) {
        Box(modifier.fillMaxSize()) { ImportUrlScreen(vm, onBack = { subScreen = 0 }) }
        return
    }
    if (subScreen == 4) {
        Box(modifier.fillMaxSize()) {
            StatsScreen(
                vm,
                onBack = { subScreen = 0 },
                onOpenKeys = { subScreen = 5 },
                onOpenDuplicates = { subScreen = 6 }
            )
        }
        return
    }
    // Tonalités et doublons s'ouvrent depuis les statistiques : le retour
    // y ramène, pas à la bibliothèque.
    if (subScreen == 5) {
        Box(modifier.fillMaxSize()) { CamelotWheelScreen(vm, onBack = { subScreen = 4 }) }
        return
    }
    if (subScreen == 6) {
        Box(modifier.fillMaxSize()) { DuplicatesScreen(vm, onBack = { subScreen = 4 }) }
        return
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Bibliothèque",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { subScreen = 3 }) {
                Icon(Icons.Rounded.Download, "Importer depuis une URL")
            }
            IconButton(onClick = { subScreen = 1 }) {
                Icon(Icons.Rounded.QueueMusic, "Playlists")
            }
            IconButton(onClick = { subScreen = 2 }) {
                Icon(Icons.Rounded.Label, "Tags en ligne")
            }
            IconButton(onClick = { subScreen = 4 }) {
                Icon(Icons.Rounded.BarChart, "Statistiques")
            }
        }
        Spacer(Modifier.height(8.dp))

        // Échec de lancement d'un mix/DJ depuis le menu ⋮ d'un morceau :
        // dire pourquoi rien ne se passe (le message n'était visible que
        // dans l'écran lecteur).
        val launchMsg by vm.launchMessage.collectAsStateWithLifecycle()
        launchMsg?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(4.dp))
        }
        // Résultat de l'export de la liste des titres (bouton des stats)
        val exportMsg by vm.exportMessage.collectAsStateWithLifecycle()
        exportMsg?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(4.dp))
        }
        // Résultat de l'export du meilleur passage (menu ⋮ d'un morceau) :
        // la découpe tourne en arrière-plan, le chemin arrive ici.
        val segmentMsg by vm.segmentExportMessage.collectAsStateWithLifecycle()
        segmentMsg?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(4.dp))
        }

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
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                singleLine = true,
                // Croix d'effacement : vider champ + filtre d'un tap, au lieu
                // d'effacer le texte lettre par lettre au clavier.
                trailingIcon = if (search.isEmpty()) null else {
                    {
                        IconButton(onClick = { search = "" }) {
                            Icon(Icons.Rounded.Close, "Effacer la recherche")
                        }
                    }
                }
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (m in SortMode.entries) {
                    FilterChip(
                        selected = sortMode == m,
                        onClick = {
                            if (sortMode == m) sortReversed = !sortReversed
                            else {
                                sortModeName = m.name
                                sortReversed = false
                            }
                        },
                        label = {
                            Text(
                                if (sortMode != m) m.label
                                else m.label + if (m.naturalDesc != sortReversed)
                                    " ↓" else " ↑"
                            )
                        }
                    )
                }
                FilterChip(
                    selected = compatOnly,
                    onClick = { compatOnly = !compatOnly },
                    // Le filtre exige un morceau en cours ANALYSÉ (BPM/clé
                    // connus) : actif sans ça, la puce s'allumait sans
                    // changer la liste d'un iota.
                    enabled = current?.analyzed == true,
                    label = { Text("Compatibles") }
                )
                FilterChip(
                    selected = failedOnly,
                    onClick = { failedOnly = !failedOnly },
                    label = { Text("Non analysés ($unanalyzed)") }
                )
            }
            Spacer(Modifier.height(6.dp))

            // Mémorisé : filtres + tri complet, rejoués sinon à CHAQUE
            // recomposition — y compris à chaque tick de progression du scan,
            // qui n'affecte ni la liste ni son ordre.
            val displayed = remember(
                tracks, search, sortMode, sortReversed, compatOnly, failedOnly,
                current
            ) {
                val cur = current
                tracks
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
                    .let { if (sortReversed) it.asReversed() else it }
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
