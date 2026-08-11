package com.pulsemix.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulsemix.app.PlayerViewModel
import com.pulsemix.app.data.Track
import com.pulsemix.app.mix.MixEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sous-écran « Doublons » : groupes de morceaux au titre normalisé
 * identique (même normalisation que la déduplication des mix,
 * [MixEngine.normTitle]) ET aux durées voisines (±7 s) — très
 * probablement le même enregistrement en plusieurs copies. Pour chaque
 * copie : pré-écoute, exclusion des mix, ou suppression (confirmée).
 */
@Composable
fun DuplicatesScreen(vm: PlayerViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val groups = remember(tracks) { duplicateGroups(tracks) }
    var deleteTarget by remember { mutableStateOf<Track?>(null) }

    // Bitrate approximatif = taille du fichier / durée. La taille se lit via
    // le contentResolver (I/O) : calculée hors du thread UI, affichée quand
    // elle arrive — la liste ne l'attend pas.
    val context = LocalContext.current
    val bitrates by produceState(emptyMap<String, Int>(), groups) {
        value = withContext(Dispatchers.IO) {
            buildMap {
                for (g in groups) for (t in g) {
                    approxBitrateKbps(context, t)?.let { put(t.uri, it) }
                }
            }
        }
    }

    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Supprimer ce morceau ?") },
            text = {
                Text(
                    "« ${t.title} » sera supprimé du téléphone et de la " +
                        "bibliothèque. Cette action est définitive."
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.deleteTrack(t)
                    deleteTarget = null
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Annuler") }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SubHeader("Doublons", onBack)
        Spacer(Modifier.height(4.dp))
        if (groups.isEmpty()) {
            Text(
                "Aucun doublon détecté : aucun couple de morceaux au même " +
                    "titre (normalisé) et à durée voisine.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        } else {
            Text(
                "${groups.size} groupes — même titre et durées à ±7 s. " +
                    "Écoute pour comparer, garde la meilleure copie.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(4.dp))
            LazyColumn {
                items(groups, key = { it.first().uri }) { group ->
                    Text(
                        group.first().title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    for (t in group) {
                        DuplicateRow(
                            vm, t, bitrates[t.uri],
                            onDelete = { deleteTarget = t }
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DuplicateRow(
    vm: PlayerViewModel,
    t: Track,
    bitrateKbps: Int?,
    onDelete: () -> Unit
) {
    val alpha = if (t.excluded) 0.45f else 1f
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            buildString {
                append(t.artist.ifBlank { "artiste inconnu" })
                if (t.durationMs > 0) {
                    append(
                        " · %d:%02d".format(
                            t.durationMs / 60_000, (t.durationMs / 1000) % 60
                        )
                    )
                }
                if (bitrateKbps != null) append(" · ~$bitrateKbps kb/s")
                if (t.excluded) append(" · exclu des mix")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row {
            TextButton(onClick = { vm.preview(t) }) { Text("Écouter") }
            TextButton(onClick = { vm.toggleExcluded(t) }) {
                Text(if (t.excluded) "Réinclure" else "Exclure")
            }
            TextButton(onClick = onDelete) {
                Text("Supprimer", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * Groupes de copies probables : même titre normalisé, durées à ±7 s de la
 * première du groupe (balayage trié — deux encodages du même enregistrement
 * ne diffèrent que de quelques secondes de silence).
 */
private fun duplicateGroups(tracks: List<Track>): List<List<Track>> {
    val out = ArrayList<List<Track>>()
    val byTitle = tracks.groupBy { MixEngine.normTitle(it.title) }
    for ((title, same) in byTitle) {
        if (title.isEmpty() || same.size < 2) continue
        val sorted = same.sortedBy { it.durationMs }
        var cluster = ArrayList<Track>()
        for (t in sorted) {
            if (cluster.isEmpty() ||
                t.durationMs - cluster.first().durationMs <= 7_000L
            ) {
                cluster.add(t)
            } else {
                if (cluster.size >= 2) out.add(cluster)
                cluster = arrayListOf(t)
            }
        }
        if (cluster.size >= 2) out.add(cluster)
    }
    return out.sortedBy { it.first().title.lowercase() }
}

/** kb/s ≈ taille (octets) × 8 / durée (ms). null si taille ou durée inconnues. */
private fun approxBitrateKbps(
    context: android.content.Context,
    t: Track
): Int? {
    if (t.durationMs <= 0) return null
    return try {
        context.contentResolver
            .openAssetFileDescriptor(android.net.Uri.parse(t.uri), "r")
            ?.use { fd ->
                val len = fd.length
                if (len <= 0) null else ((len * 8) / t.durationMs).toInt()
            }
    } catch (_: Exception) {
        null
    }
}
