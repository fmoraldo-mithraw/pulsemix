package com.pulsemix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulsemix.app.PlayerViewModel
import com.pulsemix.app.data.Track
import com.pulsemix.app.library.MashupRenderer
import com.pulsemix.app.mix.MashupEngine

/**
 * Modale « Mashup avec… » (menu d'un morceau) : la liste des partenaires
 * compatibles — tempo à ±8 % octave près, tonalité voisine sur la roue de
 * Camelot, assez de matière — avec leur compatibilité, et un bouton
 * « Générer » par partenaire. Le rendu tourne en arrière-plan ; l'état
 * (progression, résultat, erreur) s'affiche en tête.
 */
@Composable
fun MashupDialog(vm: PlayerViewModel, track: Track, onClose: () -> Unit) {
    val candidates = remember(track.uri) { vm.mashupCandidates(track) }
    val state by vm.mashupState.collectAsStateWithLifecycle()
    val working = state is MashupRenderer.State.Working
    // Un résultat d'une ouverture précédente ne concerne pas celle-ci
    LaunchedEffect(track.uri) { vm.resetMashup() }
    AlertDialog(
        onDismissRequest = { if (!working) onClose() },
        title = {
            Text(
                "Mashup avec « ${track.title} »",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (val s = state) {
                    is MashupRenderer.State.Working -> {
                        Text(s.message, style = MaterialTheme.typography.labelMedium)
                        LinearProgressIndicator(
                            progress = { s.pct / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    is MashupRenderer.State.Done -> {
                        Text(
                            s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    is MashupRenderer.State.Error -> {
                        Text(
                            s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    MashupRenderer.State.Idle -> Unit
                }
                if (candidates.isEmpty()) {
                    Text(
                        if (!track.analyzed || track.bpm <= 0f || track.camelot == "--")
                            "Ce morceau n'est pas analysé (tempo ou tonalité inconnus) : " +
                                "pas de mashup possible pour l'instant."
                        else
                            "Aucun morceau compatible : il faut un tempo à ±8 % " +
                                "(octave près), une tonalité voisine sur la roue de " +
                                "Camelot, et de quoi tenir une trentaine de mesures.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        "${track.bpm.toInt()} BPM · ${track.camelot}. Les deux morceaux " +
                            "jouent ensemble sur un tempo commun : les basses de l'un " +
                            "sous les voix de l'autre, puis les rôles s'échangent. " +
                            "Le résultat est ajouté à la bibliothèque.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(6.dp))
                    for (c in candidates) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    c.track.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    buildString {
                                        if (c.track.artist.isNotBlank()) {
                                            append(c.track.artist).append(" · ")
                                        }
                                        append(c.track.bpm.toInt()).append(" BPM · ")
                                        append(c.track.camelot).append(" · ")
                                        append("compatibilité ")
                                        append((c.score * 100f).toInt()).append(" %")
                                        if (c.tempo.factorB != 1f) append(" · demi/double tempo")
                                        append(" · ")
                                        append(
                                            if (c.baseTopFirst) "il pose les basses"
                                            else "il pose les voix"
                                        )
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(
                                enabled = !working,
                                onClick = { vm.generateMashup(track, c) }
                            ) { Text("Générer") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (working) {
                TextButton(onClick = { vm.stopMashup() }) { Text("Stop") }
            } else {
                TextButton(onClick = onClose) { Text("Fermer") }
            }
        }
    )
}
