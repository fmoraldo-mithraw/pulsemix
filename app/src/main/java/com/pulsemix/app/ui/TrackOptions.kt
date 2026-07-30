package com.pulsemix.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulsemix.app.PlayerViewModel
import com.pulsemix.app.data.Track
import com.pulsemix.app.mix.MixEngine

/**
 * Menu d'options d'un morceau (pré-écoute, mix/DJ similaires, favori,
 * exclusion, BPM, meilleur passage, type, suppression) et ses
 * sous-dialogues. Partagé entre la bibliothèque et l'écran lecteur.
 */
@Composable
fun TrackOptionsDialogs(
    vm: PlayerViewModel,
    tracks: List<Track>,
    track: Track,
    onClose: () -> Unit
) {
    var menuOpen by remember(track.uri) { mutableStateOf(true) }
    var bpmEdit by remember(track.uri) { mutableStateOf(false) }
    var segEdit by remember(track.uri) { mutableStateOf(false) }
    var genreEdit by remember(track.uri) { mutableStateOf(false) }
    var delEdit by remember(track.uri) { mutableStateOf(false) }

    if (menuOpen) {
        AlertDialog(
            onDismissRequest = onClose,
            title = {
                Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            text = {
                Column {
                    TextButton(onClick = { vm.preview(track); onClose() }) {
                        Text("▶ Écouter le meilleur passage")
                    }
                    TextButton(onClick = { vm.startSimilar(track, false); onClose() }) {
                        Text("Mix « comme ce morceau »")
                    }
                    TextButton(onClick = { vm.startSimilar(track, true); onClose() }) {
                        Text("DJ « comme ce morceau »")
                    }
                    TextButton(onClick = { vm.toggleFavorite(track); onClose() }) {
                        Text(
                            if (track.favorite) "★ Retirer des favoris"
                            else "☆ Ajouter aux favoris"
                        )
                    }
                    TextButton(onClick = { vm.toggleExcluded(track); onClose() }) {
                        Text(
                            if (track.excluded) "Réinclure dans les mix"
                            else "Exclure des mix"
                        )
                    }
                    TextButton(onClick = { bpmEdit = true; menuOpen = false }) {
                        Text("Corriger le BPM (${track.bpm})")
                    }
                    TextButton(onClick = { segEdit = true; menuOpen = false }) {
                        Text("Définir le meilleur passage…")
                    }
                    TextButton(onClick = { genreEdit = true; menuOpen = false }) {
                        Text(
                            "Changer le type" +
                                (track.genre.takeIf { it.isNotBlank() && it != "-" }
                                    ?.let { " ($it)" } ?: "") + "…"
                        )
                    }
                    TextButton(onClick = { delEdit = true; menuOpen = false }) {
                        Text(
                            "Supprimer le fichier…",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onClose) { Text("Fermer") }
            }
        )
    }

    // ---------------------------------------------------- correction du BPM
    if (bpmEdit) {
        var bpmText by remember(track.uri) { mutableStateOf(track.bpm.toString()) }
        val taps = remember(track.uri) { mutableListOf<Long>() }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Corriger le BPM") },
            text = {
                Column {
                    OutlinedTextField(
                        value = bpmText,
                        onValueChange = { bpmText = it },
                        label = { Text("BPM") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            bpmText.toFloatOrNull()?.let { bpmText = "%.1f".format(it / 2) }
                        }) { Text("÷2") }
                        OutlinedButton(onClick = {
                            bpmText.toFloatOrNull()?.let { bpmText = "%.1f".format(it * 2) }
                        }) { Text("×2") }
                        Button(onClick = {
                            val now = System.currentTimeMillis()
                            if (taps.isNotEmpty() && now - taps.last() > 3000) taps.clear()
                            taps.add(now)
                            if (taps.size >= 3) {
                                val recent = taps.takeLast(9)
                                val avg = (recent.last() - recent.first()).toFloat() /
                                    (recent.size - 1)
                                if (avg > 0) bpmText = "%.1f".format(60_000f / avg)
                            }
                        }) { Text("Tap tempo") }
                    }
                    if (track.bpmLocked) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = {
                            vm.unlockBpm(track)
                            onClose()
                        }) { Text("Déverrouiller (réanalysable)") }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    bpmText.replace(',', '.').toFloatOrNull()?.let {
                        if (it in 40f..250f) vm.setManualBpm(track, it)
                    }
                    onClose()
                }) { Text("Enregistrer") }
            },
            dismissButton = {
                TextButton(onClick = onClose) { Text("Annuler") }
            }
        )
    }

    // ------------------------------------------------------ type de musique
    if (genreEdit) {
        var genreText by remember(track.uri) {
            mutableStateOf(track.genre.takeIf { it != "-" } ?: "")
        }
        val knownGenres = remember(track.uri) {
            MixEngine.genresOf(tracks).map { it.first }.take(8)
        }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Type de musique") },
            text = {
                Column {
                    OutlinedTextField(
                        value = genreText,
                        onValueChange = { genreText = it },
                        label = { Text("Genre") },
                        singleLine = true
                    )
                    if (knownGenres.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (g in knownGenres) {
                                FilterChip(
                                    selected = genreText == g,
                                    onClick = { genreText = g },
                                    label = { Text(g) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Le fichier audio n'est pas modifié : le type est " +
                            "gardé dans la bibliothèque et protégé contre la " +
                            "réanalyse.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    if (track.genreLocked) {
                        TextButton(onClick = {
                            vm.unlockGenre(track)
                            onClose()
                        }) { Text("Revenir au type automatique") }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.setManualGenre(track, genreText)
                    onClose()
                }) { Text("Enregistrer") }
            },
            dismissButton = {
                TextButton(onClick = onClose) { Text("Annuler") }
            }
        )
    }

    // ------------------------------------------------ meilleur passage manuel
    if (segEdit) {
        val maxStartS = ((track.durationMs - 20_000L).coerceAtLeast(0L) / 1000L)
            .toFloat()
        var startS by remember(track.uri) {
            mutableFloatStateOf(
                (track.bestStartMs / 1000L).toFloat().coerceIn(0f, maxStartS)
            )
        }
        var durS by remember(track.uri) {
            mutableIntStateOf((track.segmentMs / 1000L).toInt().coerceIn(20, 90))
        }
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Meilleur passage (mode DJ)") },
            text = {
                Column {
                    Text(
                        "Départ : %d:%02d".format(
                            startS.toInt() / 60, startS.toInt() % 60
                        )
                    )
                    Slider(
                        value = startS,
                        onValueChange = { startS = it },
                        valueRange = 0f..maxStartS.coerceAtLeast(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (d in listOf(30, 45, 60, 90)) {
                            FilterChip(
                                selected = durS == d,
                                onClick = { durS = d },
                                label = { Text("${d}s") }
                            )
                        }
                    }
                    TextButton(onClick = {
                        vm.previewSegment(track, startS.toLong() * 1000, durS * 1000L)
                    }) { Text("▶ Écouter ce passage") }
                    if (track.segmentLocked) {
                        TextButton(onClick = {
                            vm.unlockSegment(track)
                            onClose()
                        }) { Text("Déverrouiller (réanalysable)") }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.setManualSegment(track, startS.toLong() * 1000, durS * 1000L)
                    onClose()
                }) { Text("Enregistrer") }
            },
            dismissButton = {
                TextButton(onClick = onClose) { Text("Annuler") }
            }
        )
    }

    // --------------------------------------------------- confirmation suppression
    if (delEdit) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Supprimer ce morceau ?") },
            text = {
                Text(
                    "« ${track.title} » sera supprimé du téléphone et de la " +
                        "bibliothèque. Cette action est définitive."
                )
            },
            confirmButton = {
                Button(onClick = { vm.deleteTrack(track); onClose() }) {
                    Text("Supprimer")
                }
            },
            dismissButton = {
                TextButton(onClick = onClose) { Text("Annuler") }
            }
        )
    }
}
