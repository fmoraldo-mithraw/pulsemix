package com.pulsemix.app.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulsemix.app.PlayerViewModel
import com.pulsemix.app.data.Track

/**
 * Menu d'options d'un morceau (pré-écoute, jouer ensuite, mix/DJ
 * similaires, favori, exclusion, BPM, meilleur passage, tags, suppression)
 * et ses sous-dialogues. Partagé entre la bibliothèque et l'écran lecteur.
 * Type, épique et jaquette se règlent dans « Modifier les tags ».
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
    var delEdit by remember(track.uri) { mutableStateOf(false) }
    var manualTagEdit by remember(track.uri) { mutableStateOf(false) }
    var mashup by remember(track.uri) { mutableStateOf(false) }

    if (menuOpen) {
        AlertDialog(
            onDismissRequest = onClose,
            title = {
                Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            text = {
                // La liste dépasse les petits écrans (une douzaine d'entrées) :
                // sans défilement, le bas était simplement coupé.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val plays = remember(track.uri) { vm.playCount(track) }
                    Text(
                        if (plays == 0) "Jamais joué"
                        else "Joué $plays fois",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                    TextButton(onClick = { vm.preview(track); onClose() }) {
                        Text("▶ Écouter le meilleur passage")
                    }
                    // Le moteur DJ tient son propre plan : hors de ce mode
                    // seulement. Déjà dans la file : déplacé, pas dupliqué.
                    val playerMode by vm.mode.collectAsStateWithLifecycle()
                    if (playerMode != com.pulsemix.app.player.PlayerMode.DJ) {
                        TextButton(onClick = { vm.playNext(track); onClose() }) {
                            Text("Jouer après ce morceau")
                        }
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
                    // Un seul morceau, sans réanalyser tout le dossier
                    TextButton(onClick = { vm.analyzeTrack(track); onClose() }) {
                        Text(
                            if (track.analyzed) "Réanalyser ce morceau"
                            else "Analyser ce morceau"
                        )
                    }
                    TextButton(onClick = { bpmEdit = true; menuOpen = false }) {
                        Text("Corriger le BPM (${track.bpm})")
                    }
                    TextButton(onClick = { segEdit = true; menuOpen = false }) {
                        Text("Définir le meilleur passage…")
                    }
                    // Découpe ffmpeg en arrière-plan ; le chemin du fichier
                    // créé s'affiche en message d'état dans la bibliothèque.
                    // L'original n'est jamais modifié.
                    TextButton(onClick = { vm.exportBestSegment(track); onClose() }) {
                        Text("Exporter le meilleur passage…")
                    }
                    // Mashup « deux platines » avec un morceau compatible
                    // (tempo, tonalité) : choix du partenaire dans une
                    // modale, rendu ffmpeg, résultat ajouté à la bibliothèque.
                    TextButton(onClick = { mashup = true; menuOpen = false }) {
                        Text("Mashup avec un morceau compatible…")
                    }
                    // Titre/artiste, recherche en ligne (texte ou empreinte
                    // sonore), type, « pas épique » et jaquette vivent tous
                    // dans ce dialogue : les entrées séparées faisaient doublon.
                    TextButton(onClick = { manualTagEdit = true; menuOpen = false }) {
                        Text("Modifier les tags…")
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
        // « Enregistrer » n'accepte qu'un BPM plausible (40-250) : avant,
        // une valeur invalide fermait le dialogue sans rien dire ni rien
        // enregistrer — l'utilisateur croyait sa correction prise en compte.
        val bpmValue = bpmText.replace(',', '.').toFloatOrNull()
        val bpmValid = bpmValue != null && bpmValue in 40f..250f
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Corriger le BPM") },
            text = {
                Column {
                    OutlinedTextField(
                        value = bpmText,
                        onValueChange = { bpmText = it },
                        label = { Text("BPM") },
                        singleLine = true,
                        // Clavier numérique : on ne tape ici que des chiffres
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        )
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
                Button(
                    enabled = bpmValid,
                    onClick = {
                        bpmValue?.let { vm.setManualBpm(track, it) }
                        onClose()
                    }
                ) { Text("Enregistrer") }
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
            // Le moteur DJ applique un plancher d'1 min : proposer moins
            // n'aurait aucun effet
            mutableIntStateOf((track.segmentMs / 1000L).toInt().coerceIn(60, 240))
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
                        for (d in listOf(60, 90, 120, 180)) {
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
                    // Passages par rôle (montée, groove, calme) dérivés de
                    // l'analyse : le mode DJ les choisit selon l'ambiance du
                    // mix. Écoute et refus par rôle ; le passage fort (celui
                    // réglé ci-dessus) reste toujours disponible.
                    val live = tracks.firstOrNull { it.uri == track.uri } ?: track
                    val candidates = remember(live.structure, live.phraseProfile, live.bestStartMs, live.segmentMs) {
                        com.pulsemix.app.mix.Passages.candidates(live)
                    }
                    val refusedRoles = com.pulsemix.app.mix.Passages.refused(live)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Passages selon l'ambiance",
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (candidates.isEmpty()) {
                        Text(
                            if (live.durationMs < com.pulsemix.app.mix.Passages.MIN_TRACK_MS)
                                "Morceau court : un seul passage, le fort."
                            else
                                "Aucun autre passage détecté (analyse sans structure " +
                                    "ni profil : réanalyser le morceau)."
                            ,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    } else {
                        Text(
                            "Le mode DJ joue le calme ou le groove en chill et en " +
                                "accalmie, la montée dans les phases qui montent, le fort " +
                                "quand ça tape — et change de passage d'un set au suivant.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        for (p in candidates) {
                            val refused = p.role in refusedRoles
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Text(
                                    "${p.role.label.replaceFirstChar { it.uppercase() }} · " +
                                        com.pulsemix.app.mix.Passages.describe(p),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (refused)
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    vm.previewSegment(track, p.startMs, p.lengthMs)
                                }) { Text("▶") }
                                TextButton(onClick = {
                                    vm.setPassageRefused(track, p.role, !refused)
                                }) { Text(if (refused) "Autoriser" else "Refuser") }
                            }
                        }
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

    // -------------------------------------------- recherche manuelle des tags
    if (manualTagEdit) {
        ManualTagDialog(vm, track, onClose = onClose)
    }

    // --------------------------------------------------- confirmation suppression
    if (mashup) {
        MashupDialog(vm, track, onClose)
    }

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
