package com.pulsemix.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulsemix.app.PlayerViewModel
import com.pulsemix.app.data.Track

@Composable
fun LibraryScreen(
    vm: PlayerViewModel,
    modifier: Modifier = Modifier,
    onPickFolder: () -> Unit
) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val progress by vm.scanProgress.collectAsStateWithLifecycle()
    val folder by vm.folderUri.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Bibliothèque",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))

        val p = progress
        val scanning = p != null
        val unanalyzed = tracks.count { !it.analyzed }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickFolder, enabled = !scanning) {
                Text("Choisir un dossier")
            }
            if (folder != null && scanning) {
                OutlinedButton(onClick = { vm.stopScan() }) {
                    Text("Stopper l'analyse")
                }
            }
        }
        if (folder != null && !scanning) {
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
                // Parcours du dossier en cours : barre indéterminée
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Préparation de l'analyse — parcours du dossier…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (tracks.isEmpty()) {
            Text(
                "Aucun morceau pour l'instant. Choisis un dossier contenant tes " +
                    "fichiers audio (mp3, m4a, flac, ogg, wav…) : chaque morceau " +
                    "sera analysé une seule fois (BPM, tonalité, énergie, " +
                    "meilleure minute).\n\n" +
                    "Après une réinstallation, re-choisis simplement le même " +
                    "dossier : les analyses sont restaurées automatiquement " +
                    "depuis la sauvegarde qu'il contient (PulseMix.library.json).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        } else {
            Text(
                "${tracks.size} morceaux · ${tracks.count { it.analyzed }} analysés",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(8.dp))
            val maxEnergy = tracks.maxOfOrNull { it.energyPeak }?.takeIf { it > 0f } ?: 1f
            LazyColumn {
                items(tracks, key = { it.uri }) { track ->
                    TrackRow(track, maxEnergy) { vm.playTrack(track) }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, maxEnergy: Float, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val info = buildString {
                if (track.artist.isNotBlank()) append(track.artist).append(" · ")
                if (track.analyzed) {
                    append("${track.bpm} BPM · ${track.keyName} (${track.camelot})")
                } else {
                    append("non analysé")
                }
                if (track.durationMs > 0) {
                    val m = track.durationMs / 60000
                    val s = (track.durationMs / 1000) % 60
                    append(" · %d:%02d".format(m, s))
                }
            }
            Text(
                info,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Barre d'énergie
        Box(
            Modifier
                .width(46.dp)
                .height(6.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(3.dp)
                )
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(end = (46 * (1f - (track.energyPeak / maxEnergy)
                        .coerceIn(0f, 1f))).dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(3.dp)
                    )
            )
        }
    }
}
