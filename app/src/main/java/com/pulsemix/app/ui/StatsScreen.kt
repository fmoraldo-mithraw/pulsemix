package com.pulsemix.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulsemix.app.PlayerViewModel
import com.pulsemix.app.data.PlayHistory
import com.pulsemix.app.data.Track
import com.pulsemix.app.mix.MixEngine

/**
 * Sous-écran « Statistiques » : chiffres clés de la bibliothèque, tops de
 * lecture (via [PlayHistory]), répartitions BPM/tonalités/genres, et accès
 * aux morceaux oubliés. Tout est agrégé une fois par changement de
 * bibliothèque ([computeStats] dans un remember) : rien de recalculé à
 * chaque recomposition.
 */
@Composable
fun StatsScreen(
    vm: PlayerViewModel,
    onBack: () -> Unit,
    onOpenKeys: () -> Unit,
    onOpenDuplicates: () -> Unit
) {
    BackHandler { onBack() }
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val stats = remember(tracks) { computeStats(tracks) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SubHeader("Statistiques", onBack)
        Spacer(Modifier.height(8.dp))

        // ------------------------------------------------------ vue d'ensemble
        val h = stats.totalMs / 3_600_000L
        val m = (stats.totalMs / 60_000L) % 60
        Text(
            "${tracks.size} morceaux · ${stats.analyzedCount} analysés · " +
                "%d h %02d min de musique".format(h, m),
            style = MaterialTheme.typography.bodyMedium
        )
        // Export de la liste des titres : sélecteur système « Enregistrer
        // sous » (repris de l'ancien dialogue de stats, remplacé par cet écran)
        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain")
        ) { uri -> if (uri != null) vm.exportTitleList(uri) }
        val exportMsg by vm.exportMessage.collectAsStateWithLifecycle()
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onOpenKeys) { Text("Roue des tonalités") }
            OutlinedButton(onClick = onOpenDuplicates) { Text("Doublons") }
            OutlinedButton(onClick = {
                exportLauncher.launch("PulseMix.titres.txt")
            }) { Text("Titres (.txt)") }
        }
        exportMsg?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // -------------------------------------------------- morceaux oubliés
        Text("Jamais joués", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "${stats.neverPlayed} morceaux n'ont jamais été joués.",
            style = MaterialTheme.typography.bodyMedium
        )
        if (stats.neverPlayed > 0) {
            OutlinedButton(onClick = { vm.playForgotten() }) {
                Text("▶ Écouter les oubliés")
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // --------------------------------------------------- tops de lecture
        Text("Les plus joués", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (stats.topTracks.isEmpty()) {
            Text(
                "Aucune lecture comptée pour l'instant.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            for ((i, e) in stats.topTracks.withIndex()) {
                val (t, n) = e
                Text(
                    "${i + 1}. ${t.title}" +
                        (if (t.artist.isNotBlank()) " — ${t.artist}" else "") +
                        " · $n lectures",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (stats.topArtists.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Artistes les plus joués",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            for ((i, e) in stats.topArtists.withIndex()) {
                Text(
                    "${i + 1}. ${e.first} · ${e.second} lectures",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // ------------------------------------------------- répartition des BPM
        Text("Répartition des BPM", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (stats.bpmBuckets.isEmpty()) {
            Text(
                "Analyse la bibliothèque pour voir les tempos.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            val maxBpm = stats.bpmBuckets.maxOf { it.second }
            for ((label, count) in stats.bpmBuckets) {
                StatBar(label, count, maxBpm)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // ------------------------------------------- tonalités Camelot présentes
        Text(
            "Tonalités (Camelot)",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        if (stats.keyCounts.isEmpty()) {
            Text(
                "Aucune tonalité détectée pour l'instant.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            val maxKey = stats.keyCounts.maxOf { it.second }
            for ((key, count) in stats.keyCounts) {
                StatBar(key, count, maxKey)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // ------------------------------------------------------------- genres
        Text("Genres", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (stats.topGenres.isEmpty()) {
            Text(
                "Aucun genre renseigné dans les fichiers.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            val maxGenre = stats.topGenres.maxOf { it.second }
            for ((genre, count) in stats.topGenres) {
                StatBar(genre, count, maxGenre)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Une barre horizontale sobre : libellé + compte, remplissage proportionnel. */
@Composable
private fun StatBar(label: String, count: Int, max: Int) {
    Text(
        "$label — $count",
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    LinearProgressIndicator(
        progress = { count.toFloat() / max.coerceAtLeast(1) },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(4.dp))
}

/** Agrégats de l'écran, calculés une fois par changement de bibliothèque. */
private class LibraryStats(
    val analyzedCount: Int,
    val totalMs: Long,
    val topTracks: List<Pair<Track, Int>>,
    val topArtists: List<Pair<String, Int>>,
    val bpmBuckets: List<Pair<String, Int>>,
    val keyCounts: List<Pair<String, Int>>,
    val topGenres: List<Pair<String, Int>>,
    val neverPlayed: Int
)

private fun computeStats(tracks: List<Track>): LibraryStats {
    // Compteurs figés une fois : PlayHistory est interrogé N fois ici,
    // pas une fois par recomposition ni par tri.
    val counts = HashMap<String, Int>(tracks.size)
    for (t in tracks) counts[t.uri] = PlayHistory.count(t.uri)

    val topTracks = tracks
        .filter { (counts[it.uri] ?: 0) > 0 }
        .sortedByDescending { counts[it.uri] }
        .take(10)
        .map { it to (counts[it.uri] ?: 0) }

    val topArtists = tracks
        .filter { it.artist.isNotBlank() }
        .groupBy { it.artist }
        .map { (artist, ts) -> artist to ts.sumOf { counts[it.uri] ?: 0 } }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .take(10)

    val analyzed = tracks.filter { it.analyzed && it.bpm > 0f }
    val bpmBuckets = analyzed
        .groupBy { (it.bpm / 10f).toInt() * 10 }
        .toSortedMap()
        .map { (bucket, ts) -> "$bucket–${bucket + 9} BPM" to ts.size }

    val keyCounts = analyzed
        .filter { it.camelot != "--" }
        .groupBy { it.camelot }
        .map { it.key to it.value.size }
        .sortedBy { camelotRank(it.first) }

    return LibraryStats(
        analyzedCount = tracks.count { it.analyzed },
        totalMs = tracks.sumOf { it.durationMs },
        topTracks = topTracks,
        topArtists = topArtists,
        bpmBuckets = bpmBuckets,
        keyCounts = keyCounts,
        topGenres = MixEngine.genresOf(tracks).take(8),
        neverPlayed = tracks.count { (counts[it.uri] ?: 0) == 0 }
    )
}

/** 1A, 1B, 2A… dans l'ordre de la roue (numéro puis lettre). */
private fun camelotRank(camelot: String): Int {
    if (camelot.length < 2) return 99
    val n = camelot.dropLast(1).toIntOrNull() ?: return 99
    return n * 2 + if (camelot.last() == 'B') 1 else 0
}
