package com.pulsemix.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.pulsemix.app.mix.MixEngine
import com.pulsemix.app.player.PlayerMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    modifier: Modifier = Modifier,
    onGoLibrary: () -> Unit
) {
    val track by vm.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val shuffle by vm.shuffle.collectAsStateWithLifecycle()
    val planName by vm.planName.collectAsStateWithLifecycle()
    val phases by vm.phaseNames.collectAsStateWithLifecycle()
    val currentPhase by vm.currentPhase.collectAsStateWithLifecycle()
    val tracks by vm.tracks.collectAsStateWithLifecycle()

    var showDouceDialog by remember { mutableStateOf(false) }
    var showMixSheet by remember { mutableStateOf(false) }
    var mixSheetDj by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "PulseMix",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))

        // ------------------------------------------------------------ modes
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == PlayerMode.NORMAL,
                onClick = { vm.playAll() },
                label = { Text("Normal") }
            )
            FilterChip(
                selected = mode == PlayerMode.DOUCE,
                onClick = { showDouceDialog = true },
                label = { Text("Douce") }
            )
            FilterChip(
                selected = mode == PlayerMode.MIX,
                onClick = { mixSheetDj = false; showMixSheet = true },
                label = { Text("Mix") }
            )
            FilterChip(
                selected = mode == PlayerMode.DJ,
                onClick = { mixSheetDj = true; showMixSheet = true },
                label = { Text("DJ") }
            )
        }

        Spacer(Modifier.height(24.dp))

        // ---------------------------------------------------- morceau courant
        Box(
            Modifier
                .size(180.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(24.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (mode == PlayerMode.DJ) "🎧" else "🎵",
                style = MaterialTheme.typography.displayLarge
            )
        }
        Spacer(Modifier.height(16.dp))

        Text(
            track?.title ?: "Aucun morceau",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!track?.artist.isNullOrBlank()) {
            Text(
                track?.artist ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        Spacer(Modifier.height(8.dp))

        val t = track
        if (t != null && t.analyzed) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("${t.bpm} BPM") })
                AssistChip(onClick = {}, label = { Text("${t.keyName} · ${t.camelot}") })
            }
        }

        // ------------------------------------------------------------ phases
        if ((mode == PlayerMode.MIX || mode == PlayerMode.DJ) && phases.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            planName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(phases.size) { i ->
                    FilterChip(
                        selected = i == currentPhase,
                        onClick = {},
                        label = { Text(phases[i]) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "⏭ passe à la phase suivante",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }

        Spacer(Modifier.height(20.dp))

        // -------------------------------------------------------- progression
        if (mode == PlayerMode.DJ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Meilleure minute en cours…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        } else {
            Slider(
                value = progress,
                onValueChange = { vm.seekTo(it) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(8.dp))

        // ---------------------------------------------------------- transport
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(onClick = { vm.setShuffle(!shuffle) }) {
                Icon(
                    Icons.Rounded.Shuffle, "Aléatoire",
                    tint = if (shuffle) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            FilledIconButton(
                onClick = { vm.previous() },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) { Icon(Icons.Rounded.SkipPrevious, "Précédent") }

            FilledIconButton(
                onClick = { vm.togglePlayPause() },
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    "Lecture / pause",
                    modifier = Modifier.size(36.dp)
                )
            }

            FilledIconButton(
                onClick = { vm.next() },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) { Icon(Icons.Rounded.SkipNext, "Suivant") }

            Spacer(Modifier.size(48.dp))
        }

        if (tracks.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onGoLibrary) {
                Text("Choisir un dossier de musique")
            }
        }
    }

    // ------------------------------------------------------- dialogue Douce
    if (showDouceDialog) {
        var cutoff by remember { mutableFloatStateOf(95f) }
        AlertDialog(
            onDismissRequest = { showDouceDialog = false },
            title = { Text("Musique douce") },
            text = {
                Column {
                    Text(
                        "Sélectionne les morceaux calmes : BPM sous le seuil, " +
                            "énergie et brillance basses."
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Seuil : ${cutoff.toInt()} BPM")
                    Slider(
                        value = cutoff,
                        onValueChange = { cutoff = it },
                        valueRange = 60f..120f
                    )
                    Text(
                        "${vm.softCount(cutoff)} morceau(x) correspondant(s)",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.playDouce(cutoff)
                    showDouceDialog = false
                }) { Text("Lancer") }
            },
            dismissButton = {
                TextButton(onClick = { showDouceDialog = false }) { Text("Annuler") }
            }
        )
    }

    // ----------------------------------------------------- feuille des mix
    if (showMixSheet) {
        val plans = remember(tracks) { vm.proposeMixes() }
        ModalBottomSheet(onDismissRequest = { showMixSheet = false }) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    if (mixSheetDj) "Mode DJ — choisis ton mix"
                    else "Choisis ton mix",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (mixSheetDj) {
                    Text(
                        "Seule la meilleure minute de chaque morceau est jouée, " +
                            "avec des transitions calées sur le beat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (plans.isEmpty()) {
                    Text(
                        "Pas assez de morceaux analysés. Ajoute un dossier dans " +
                            "la bibliothèque et laisse l'analyse se terminer."
                    )
                    Spacer(Modifier.height(24.dp))
                } else {
                    plans.forEach { plan ->
                        MixPlanCard(plan, mixSheetDj) {
                            vm.startMix(plan, mixSheetDj)
                            showMixSheet = false
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun MixPlanCard(
    plan: MixEngine.MixPlan,
    dj: Boolean,
    onStart: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                plan.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                plan.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                plan.phases.joinToString(" · ") { "${it.name} ${it.tracks.size}" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(if (dj) "Lancer en DJ" else "Lancer le mix")
            }
        }
    }
}
