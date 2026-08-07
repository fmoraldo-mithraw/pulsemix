package com.pulsemix.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulsemix.app.PlayerViewModel
import com.pulsemix.app.data.Track

/**
 * Recherche manuelle des tags d'un morceau : l'utilisateur ajuste titre
 * et artiste, lance la recherche MusicBrainz, et choisit le bon résultat
 * dans la liste (la durée aide à départager). Utilisé quand la correction
 * automatique ne trouve rien ou propose des résultats douteux.
 */
@Composable
fun ManualTagDialog(
    vm: PlayerViewModel,
    track: Track,
    autoSearch: Boolean = false,
    onClose: () -> Unit
) {
    val prefill = remember(track.uri) { vm.manualTagPrefill(track) }
    var title by remember(track.uri) { mutableStateOf(prefill.first) }
    var artist by remember(track.uri) { mutableStateOf(prefill.second) }
    val results by vm.manualTagResults.collectAsStateWithLifecycle()
    val searching by vm.manualTagSearching.collectAsStateWithLifecycle()
    val error by vm.manualTagError.collectAsStateWithLifecycle()

    // Ne pas montrer les résultats d'une recherche précédente ; en mode
    // autoSearch (bouton « Chercher » d'une proposition), lancer tout de
    // suite la recherche pour afficher la liste des possibilités.
    LaunchedEffect(track.uri) {
        vm.resetManualTagSearch()
        vm.resetManualCoverSearch()
        if (autoSearch && title.isNotBlank()) vm.manualTagSearch(title, artist)
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Corriger les tags à la main") },
        text = {
            Column {
                Text(
                    "Fichier : ${track.title}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Titre") },
                    singleLine = true
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Artiste (optionnel)") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.manualTagSearch(title, artist) },
                        enabled = title.isNotBlank() && !searching
                    ) { Text("Chercher") }
                    // Écrire directement ce qui est saisi, sans recherche
                    OutlinedButton(
                        onClick = {
                            vm.saveTagsDirect(track, title, artist)
                            onClose()
                        },
                        enabled = title.isNotBlank()
                    ) { Text("Sauvegarder") }
                }
                // Ignore les champs : écoute le morceau et l'identifie par
                // son empreinte sonore (AcoustID), imparable quand les
                // tags et le nom de fichier ne veulent rien dire
                TextButton(
                    onClick = { vm.manualTagIdentify(track) },
                    enabled = !searching
                ) { Text("Identifier par le son") }
                // Jaquette seule : retrouve l'album d'après les champs et
                // remplace la pochette affichée, sans toucher aux tags
                val coverBusy by vm.manualCoverBusy.collectAsStateWithLifecycle()
                val coverMsg by vm.manualCoverMessage.collectAsStateWithLifecycle()
                TextButton(
                    onClick = { vm.manualCoverSearch(track, title, artist) },
                    enabled = title.isNotBlank() && !coverBusy
                ) { Text("Chercher la jaquette") }
                if (searching || coverBusy) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                coverMsg?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (results.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Touche le bon résultat pour l'appliquer :",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(results.size) { i ->
                            val c = results[i]
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.applyManualTag(track, c)
                                        onClose()
                                    }
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(
                                    c.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    listOf(c.artist, durationLabel(c.lengthMs))
                                        .filter { it.isNotBlank() }
                                        .joinToString(" • "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                        .copy(alpha = 0.6f)
                                )
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Fermer") }
        }
    )
}

private fun durationLabel(ms: Long): String {
    if (ms <= 0) return ""
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
