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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.pulsemix.app.data.Track
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
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    val sleepRemaining by vm.sleepRemainingMs.collectAsStateWithLifecycle()

    // Pas de défilement vertical : tout l'écran lecteur tient à l'écran
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "PulseMix",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))

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

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------- morceau courant
        TrackArtwork(
            uri = track?.uri,
            modifier = Modifier.size(120.dp),
            corner = 18.dp,
            targetPx = 512,
            fallback = if (mode == PlayerMode.DJ) "🎧" else "🎵",
            fallbackStyle = MaterialTheme.typography.displayMedium
        )
        Spacer(Modifier.height(10.dp))

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
            Spacer(Modifier.height(8.dp))
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

        Spacer(Modifier.height(10.dp))

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

            IconButton(
                onClick = { if (track != null) showDeleteDialog = true },
                enabled = track != null
            ) {
                Icon(
                    Icons.Rounded.Delete, "Supprimer le morceau",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }

        // ------------------------------------------------ file & minuterie
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = { showQueueSheet = true }) {
                Icon(
                    Icons.Rounded.QueueMusic, "File d'attente",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            IconButton(onClick = { showSleepDialog = true }) {
                Icon(
                    Icons.Rounded.Timer, "Minuterie de sommeil",
                    tint = if (sleepRemaining != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            sleepRemaining?.let {
                Text(
                    "pause dans ${(it / 60_000) + 1} min",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (mode == PlayerMode.DJ) {
                val recording by vm.djRecording.collectAsStateWithLifecycle()
                IconButton(onClick = { vm.toggleDjRecording() }) {
                    Icon(
                        Icons.Rounded.FiberManualRecord, "Enregistrer le set",
                        tint = if (recording) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                IconButton(onClick = { vm.markBadTransition() }) {
                    Icon(
                        Icons.Rounded.ThumbDown, "Transition ratée",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }

        if (tracks.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onGoLibrary) {
                Text("Choisir un dossier de musique")
            }
        }
    }

    // -------------------------------------------------- confirmation suppression
    val toDelete = track
    if (showDeleteDialog && toDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Supprimer ce morceau ?") },
            text = {
                Text(
                    "« ${toDelete.title} » sera supprimé du téléphone et de la " +
                        "bibliothèque. Cette action est définitive."
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.deleteTrack(toDelete)
                    showDeleteDialog = false
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Annuler") }
            }
        )
    }

    // ------------------------------------------------------- dialogue Douce
    if (showDouceDialog) {
        // Défaut très doux : le quart le plus calme de la bibliothèque
        var softness by remember { mutableFloatStateOf(0.25f) }
        val matching = vm.softCount(softness)
        val label = when {
            softness <= 0.20f -> "très doux"
            softness <= 0.35f -> "doux"
            softness <= 0.50f -> "modéré"
            else -> "permissif"
        }
        AlertDialog(
            onDismissRequest = { showDouceDialog = false },
            title = { Text("Musique douce") },
            text = {
                Column {
                    Text(
                        "Ne garde que les morceaux vraiment calmes : énergie, " +
                            "brillance, attaques et BPM bas par rapport au reste " +
                            "de ta bibliothèque."
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Douceur : ${(softness * 100).toInt()} % — $label")
                    Slider(
                        value = softness,
                        onValueChange = { softness = it },
                        valueRange = 0.10f..0.60f
                    )
                    Text(
                        if (matching > 0) "$matching morceau(x) correspondant(s)"
                        else "Aucun morceau assez doux — monte le curseur.",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = matching > 0,
                    onClick = {
                        vm.playDouce(softness)
                        showDouceDialog = false
                    }
                ) { Text("Lancer") }
            },
            dismissButton = {
                TextButton(onClick = { showDouceDialog = false }) { Text("Annuler") }
            }
        )
    }

    // ------------------------------------------------------- file d'attente
    if (showQueueSheet) {
        val queue by vm.queue.collectAsStateWithLifecycle()
        var showSavePlaylist by remember { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { showQueueSheet = false }) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "File d'attente",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (queue.isNotEmpty()) {
                        TextButton(onClick = { showSavePlaylist = true }) {
                            Text("Enregistrer…")
                        }
                    }
                }
                if (showSavePlaylist) {
                    var name by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showSavePlaylist = false },
                        title = { Text("Enregistrer comme playlist") },
                        text = {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Nom") },
                                singleLine = true
                            )
                        },
                        confirmButton = {
                            Button(onClick = {
                                vm.savePlaylistFromQueue(name.trim())
                                showSavePlaylist = false
                            }) { Text("Enregistrer") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSavePlaylist = false }) {
                                Text("Annuler")
                            }
                        }
                    )
                }
                if (mode == PlayerMode.DJ) {
                    Text(
                        "En mode DJ, la file n'est pas éditable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (queue.isEmpty()) {
                    Text("Rien en cours de lecture.")
                    Spacer(Modifier.height(24.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 480.dp)) {
                        itemsIndexed(queue) { i, t ->
                            val isCurrent = t.uri == track?.uri
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = mode != PlayerMode.DJ) {
                                        vm.playQueueItem(i)
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${i + 1}.",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.width(28.dp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        t.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        "${t.bpm} BPM · ${t.camelot}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                if (mode != PlayerMode.DJ) {
                                    IconButton(
                                        onClick = { vm.moveQueueItem(i, i - 1) },
                                        enabled = i > 0,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.KeyboardArrowUp, "Monter",
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                    IconButton(
                                        onClick = { vm.moveQueueItem(i, i + 1) },
                                        enabled = i < queue.size - 1,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.KeyboardArrowDown, "Descendre",
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                    IconButton(
                                        onClick = { vm.removeFromQueue(i) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Close, "Retirer",
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    // --------------------------------------------------- minuterie de sommeil
    if (showSleepDialog) {
        AlertDialog(
            onDismissRequest = { showSleepDialog = false },
            title = { Text("Minuterie de sommeil") },
            text = {
                Column {
                    Text("La lecture s'arrête en fondu après le délai choisi.")
                    Spacer(Modifier.height(8.dp))
                    for (minutes in listOf(15, 30, 60, 90)) {
                        TextButton(onClick = {
                            vm.setSleepTimer(minutes)
                            showSleepDialog = false
                        }) { Text("$minutes minutes") }
                    }
                    if (sleepRemaining != null) {
                        TextButton(onClick = {
                            vm.setSleepTimer(null)
                            showSleepDialog = false
                        }) { Text("Désactiver", color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepDialog = false }) { Text("Fermer") }
            }
        )
    }

    // ----------------------------------------------------- feuille des mix
    if (showMixSheet) {
        // Plans figés à l'ouverture et calculés en arrière-plan : pas de
        // recalcul sur le thread UI à chaque morceau analysé pendant le scroll.
        var targetMin by remember { mutableStateOf<Int?>(null) }
        var selectedGenre by remember { mutableStateOf<String?>(null) }
        var refreshKey by remember { mutableStateOf(0) }
        var plans by remember { mutableStateOf<List<MixEngine.MixPlan>?>(null) }
        // Tous les types existants de la bibliothèque, triés par effectif
        val genres = remember(refreshKey) { vm.genres() }
        LaunchedEffect(targetMin, refreshKey, selectedGenre) {
            plans = null
            plans = vm.proposeMixes(mixSheetDj, targetMin, selectedGenre)
        }
        ModalBottomSheet(onDismissRequest = { showMixSheet = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (mixSheetDj) "Mode DJ — choisis ton mix"
                        else "Choisis ton mix",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Rounded.Refresh, "Regénérer les propositions")
                    }
                }
                if (mixSheetDj) {
                    Text(
                        "Seul le meilleur passage de chaque morceau est joué, " +
                            "avec des transitions calées sur le beat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = targetMin == null,
                        onClick = { targetMin = null },
                        label = { Text("Auto") }
                    )
                    for ((label, minutes) in listOf("30 min" to 30, "1 h" to 60, "2 h" to 120)) {
                        FilterChip(
                            selected = targetMin == minutes,
                            onClick = { targetMin = minutes },
                            label = { Text(label) }
                        )
                    }
                }
                if (genres.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedGenre == null,
                            onClick = { selectedGenre = null },
                            label = { Text("Tous genres") }
                        )
                        for ((g, count) in genres) {
                            FilterChip(
                                selected = selectedGenre == g,
                                onClick = {
                                    selectedGenre = if (selectedGenre == g) null else g
                                },
                                label = { Text("$g ($count)") }
                            )
                        }
                    }
                    Text(
                        "Un genre sélectionné inclut aussi les genres au profil proche.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                val currentPlans = plans
                when {
                    currentPlans == null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Préparation des mix…")
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                    currentPlans.isEmpty() -> {
                        Text(
                            "Pas assez de morceaux analysés. Ajoute un dossier dans " +
                                "la bibliothèque et laisse l'analyse se terminer."
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                    else -> {
                        currentPlans.forEach { plan ->
                            MixPlanCard(
                                plan, mixSheetDj,
                                onRemoveTrack = { tr ->
                                    plans = currentPlans.map { p ->
                                        if (p === plan) removeTrackFromPlan(p, tr) else p
                                    }
                                },
                                onRehearse = {
                                    vm.rehearseTransitions(plan)
                                    showMixSheet = false
                                },
                                onStart = {
                                    vm.startMix(plan, mixSheetDj)
                                    showMixSheet = false
                                }
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

private fun removeTrackFromPlan(plan: MixEngine.MixPlan, track: Track): MixEngine.MixPlan =
    MixEngine.MixPlan(
        plan.id, plan.name, plan.description,
        plan.phases.mapNotNull { ph ->
            val remaining = ph.tracks.filter { it.uri != track.uri }
            if (remaining.isEmpty()) null else MixEngine.Phase(ph.name, remaining)
        }
    )

@Composable
private fun MixPlanCard(
    plan: MixEngine.MixPlan,
    dj: Boolean,
    onRemoveTrack: (Track) -> Unit,
    onRehearse: () -> Unit,
    onStart: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
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
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        "Voir les morceaux"
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                plan.phases.joinToString(" · ") { "${it.name} ${it.tracks.size}" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                for (phase in plan.phases) {
                    Text(
                        phase.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    for (t in phase.tracks) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${t.title} · ${t.bpm} BPM",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onRemoveTrack(t) }) {
                                Icon(
                                    Icons.Rounded.Close, "Retirer",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(if (dj) "Lancer en DJ" else "Lancer le mix")
            }
            if (dj) {
                TextButton(onClick = onRehearse, modifier = Modifier.fillMaxWidth()) {
                    Text("Répéter les transitions (jonctions seules)")
                }
            }
        }
    }
}
