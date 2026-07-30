package com.pulsemix.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Speed
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
    var showFxSheet by remember { mutableStateOf(false) }
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

        // Échec de lancement (plan vide, aucun morceau analysé...) : dire
        // pourquoi rien ne se passe au lieu de laisser des boutons muets.
        val launchMsg by vm.launchMessage.collectAsStateWithLifecycle()
        launchMsg?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---------------------------------------------------- morceau courant
        // Un tap sur la pochette bascule vers la vue waveform (onde du
        // morceau + position + transitions + morceau suivant), et inversement.
        var showWave by remember { mutableStateOf(false) }
        val nextTrack by vm.nextTrack.collectAsStateWithLifecycle()
        if (showWave) {
            WaveformPanel(
                track = track,
                next = nextTrack,
                dj = mode == PlayerMode.DJ,
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clickable { showWave = false }
            )
        } else {
            TrackArtwork(
                uri = track?.uri,
                modifier = Modifier
                    .size(120.dp)
                    .clickable { showWave = true },
                corner = 18.dp,
                targetPx = 512,
                fallback = if (mode == PlayerMode.DJ) "🎧" else "🎵",
                fallbackStyle = MaterialTheme.typography.displayMedium
            )
        }
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
            // Panneau « Effets » : tous les contrôles à crans y ont déménagé
            val bassLevel by vm.bassLevel.collectAsStateWithLifecycle()
            val speedLevel by vm.speedLevel.collectAsStateWithLifecycle()
            val trebleLevel by vm.trebleLevel.collectAsStateWithLifecycle()
            val filterLevel by vm.filterLevel.collectAsStateWithLifecycle()
            val echoLevel by vm.echoLevel.collectAsStateWithLifecycle()
            val panLevel by vm.panLevel.collectAsStateWithLifecycle()
            val gateLevel by vm.gateLevel.collectAsStateWithLifecycle()
            val fxActive = bassLevel != 0 || speedLevel != 0 || trebleLevel != 0 ||
                filterLevel != 0 || echoLevel != 0 || panLevel != 0 || gateLevel != 0
            IconButton(onClick = { showFxSheet = true }) {
                Icon(
                    Icons.Rounded.Tune, "Effets",
                    tint = if (fxActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
    if (showFxSheet) {
        FxSheet(vm, dj = mode == PlayerMode.DJ) { showFxSheet = false }
    }

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

/**
 * Bouton de boost à crans (-3..+3).
 * - Tap : bascule 0 ↔ +2 (boost standard).
 * - Appui long puis glisser : chaque glissade d'un seuil (~40 dp) vers le
 *   haut monte d'un cran, vers le bas descend d'un cran (jusqu'en négatif :
 *   ralentir / couper les basses).
 */
@Composable
private fun BoostButton(
    icon: ImageVector,
    label: String,
    level: Int,
    onLevel: (Int) -> Unit,
    minLevel: Int = -3,
    enabled: Boolean = true
) {
    // Course courte : les boutons sont bas sur l'écran, il faut peu de place
    // vers le bas pour descendre d'un cran.
    val stepPx = with(LocalDensity.current) { 32.dp.toPx() }
    val curLevel by rememberUpdatedState(level)
    val setLevel by rememberUpdatedState(onLevel)
    val en by rememberUpdatedState(enabled)
    val minL by rememberUpdatedState(minLevel)
    val haptics = LocalHapticFeedback.current
    // Jauge de glissade : visible pendant l'appui long, se remplit vers le
    // haut ou le bas ; pleine = le prochain cran est franchi.
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .size(48.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    // Tap : +1 cran, puis retour à 0 après le maximum
                    onTap = {
                        if (en) {
                            setLevel(if (curLevel >= 3) 0 else curLevel + 1)
                        }
                    },
                    onLongPress = {
                        if (en) haptics.performHapticFeedback(
                            HapticFeedbackType.LongPress
                        )
                    }
                )
            }
            .pointerInput(Unit) {
                var startLevel = 0
                var lastSent = 0
                var totalDy = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        if (en) {
                            startLevel = curLevel
                            lastSent = curLevel
                            totalDy = 0f
                            dragging = true
                            dragProgress = 0f
                        }
                    },
                    onDragEnd = {
                        dragging = false
                        dragProgress = 0f
                    },
                    onDragCancel = {
                        dragging = false
                        dragProgress = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        if (dragging) {
                            // Mapping sur le déplacement TOTAL depuis l'appui :
                            // symétrique haut/bas, et on peut monter puis
                            // redescendre dans le même geste sans à-coup.
                            totalDy -= amount.y
                            val steps = (totalDy / stepPx).toInt()
                            val newLevel = (startLevel + steps).coerceIn(minL, 3)
                            if (newLevel != lastSent) {
                                lastSent = newLevel
                                setLevel(newLevel)
                                haptics.performHapticFeedback(
                                    HapticFeedbackType.LongPress
                                )
                            }
                            dragProgress =
                                (totalDy / stepPx - steps).coerceIn(-1f, 1f)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (dragging) {
            BoostDragGauge(
                level = level,
                progress = dragProgress,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-92).dp)
            )
        }
        Icon(
            icon, label,
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                level > 0 -> MaterialTheme.colorScheme.primary
                level < 0 -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            }
        )
        if (level != 0) {
            Text(
                if (level > 0) "+$level" else "$level",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (level > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
            )
        }
    }
}

/**
 * Jauge affichée pendant l'appui long sur un bouton de boost : le niveau
 * courant au centre, et un remplissage vers le haut ou le bas qui montre si
 * la glissade a suffi pour franchir le prochain cran (jauge pleine = cran).
 */
@Composable
private fun BoostDragGauge(level: Int, progress: Float, modifier: Modifier = Modifier) {
    val up = MaterialTheme.colorScheme.primary
    val down = MaterialTheme.colorScheme.tertiary
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val bg = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = modifier
            .background(bg, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (level > 0) "+$level" else "$level",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = when {
                level > 0 -> up
                level < 0 -> down
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
        Canvas(Modifier.size(12.dp, 56.dp)) {
            val w = size.width
            val h = size.height
            val mid = h / 2f
            drawRoundRect(trackColor, cornerRadius = CornerRadius(w / 2f, w / 2f))
            // Repère central
            drawLine(
                trackColor,
                Offset(-2f, mid),
                Offset(w + 2f, mid),
                strokeWidth = 2f
            )
            val p = progress.coerceIn(-1f, 1f)
            if (p > 0f) {
                val fh = mid * p
                drawRoundRect(
                    up,
                    topLeft = Offset(0f, mid - fh),
                    size = Size(w, fh),
                    cornerRadius = CornerRadius(w / 2f, w / 2f)
                )
            } else if (p < 0f) {
                val fh = mid * (-p)
                drawRoundRect(
                    down,
                    topLeft = Offset(0f, mid),
                    size = Size(w, fh),
                    cornerRadius = CornerRadius(w / 2f, w / 2f)
                )
            }
        }
    }
}

/**
 * Panneau « Effets » : tous les contrôles live à crans (tap = on/off,
 * appui long + glisser = régler), plus la boucle live (maintenir).
 * Écho, auto-pan, gate et boucle sont rendus par le moteur DJ uniquement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FxSheet(vm: PlayerViewModel, dj: Boolean, onDismiss: () -> Unit) {
    val bass by vm.bassLevel.collectAsStateWithLifecycle()
    val treble by vm.trebleLevel.collectAsStateWithLifecycle()
    val filter by vm.filterLevel.collectAsStateWithLifecycle()
    val speed by vm.speedLevel.collectAsStateWithLifecycle()
    val echo by vm.echoLevel.collectAsStateWithLifecycle()
    val pan by vm.panLevel.collectAsStateWithLifecycle()
    val gate by vm.gateLevel.collectAsStateWithLifecycle()
    val loop by vm.liveLoop.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Effets",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.resetEffects() }) { Text("Réinitialiser") }
            }
            Text(
                "Tap : activer/couper. Appui long puis glisser : régler par crans.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(56.dp)) // place pour les jauges de glissade
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FxControl("Basses", Icons.Rounded.GraphicEq, bass,
                    onLevel = { vm.setBassLevel(it) })
                FxControl("Aigus", Icons.Rounded.Audiotrack, treble,
                    onLevel = { vm.setTrebleLevel(it) })
                FxControl("Filtre", Icons.Rounded.FilterAlt, filter,
                    onLevel = { vm.setFilterLevel(it) })
                FxControl("Vitesse", Icons.Rounded.Speed, speed,
                    onLevel = { vm.setSpeedLevel(it) })
            }
            Spacer(Modifier.height(64.dp)) // place pour les jauges de glissade
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FxControl("Écho", Icons.Rounded.Waves, echo,
                    minLevel = 0, enabled = dj,
                    onLevel = { vm.setEchoLevel(it) })
                FxControl("Auto-pan", Icons.Rounded.Autorenew, pan,
                    enabled = dj,
                    onLevel = { vm.setPanLevel(it) })
                FxControl("Gate", Icons.Rounded.BarChart, gate,
                    minLevel = 0, enabled = dj,
                    onLevel = { vm.setGateLevel(it) })
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val loopBeats by vm.liveLoopBeats.collectAsStateWithLifecycle()
                    HoldLoopButton(
                        active = loop,
                        beats = loopBeats,
                        enabled = dj,
                        onHold = { vm.setLiveLoop(it) },
                        onToggleSize = { vm.toggleLiveLoopSize() }
                    )
                    Text(
                        "Boucle ($loopBeats t.)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dj) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }
            }
            if (!dj) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Écho, auto-pan, gate et boucle ne sont actifs qu'en mode DJ.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun FxControl(
    label: String,
    icon: ImageVector,
    level: Int,
    minLevel: Int = -3,
    enabled: Boolean = true,
    onLevel: (Int) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BoostButton(
            icon = icon,
            label = label,
            level = level,
            onLevel = onLevel,
            minLevel = minLevel,
            enabled = enabled
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        )
    }
}

/** Boucle live : tap = taille 4 ↔ 8 temps ; maintenir = les derniers temps
 *  tournent en boucle ; relâcher = un dernier passage complet de la boucle,
 *  puis le morceau reprend là où il serait arrivé (slip). */
@Composable
private fun HoldLoopButton(
    active: Boolean,
    beats: Int,
    enabled: Boolean,
    onHold: (Boolean) -> Unit,
    onToggleSize: () -> Unit
) {
    val hold by rememberUpdatedState(onHold)
    val toggleSize by rememberUpdatedState(onToggleSize)
    val en by rememberUpdatedState(enabled)
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .pointerInput(Unit) {
                var holding = false
                detectTapGestures(
                    // Tap : change la taille de la prochaine boucle
                    onTap = { if (en) toggleSize() },
                    // Appui long : la boucle démarre...
                    onLongPress = {
                        if (en) {
                            haptics.performHapticFeedback(
                                HapticFeedbackType.LongPress
                            )
                            hold(true)
                            holding = true
                        }
                    },
                    // ...et s'arrête au relâchement (dernier passage)
                    onPress = {
                        tryAwaitRelease()
                        if (holding) {
                            holding = false
                            hold(false)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.Loop, "Boucle live (maintenir)",
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                active -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            }
        )
        if (beats == 8 && enabled) {
            Text(
                "×2",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
            )
        }
    }
}
