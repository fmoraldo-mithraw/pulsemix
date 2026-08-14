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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Notes
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
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import com.pulsemix.app.library.Lyrics
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
    // Le morceau à supprimer est FIGÉ à l'ouverture du dialogue : viser le
    // morceau courant « en direct » supprimait le suivant si la lecture
    // enchaînait pendant que le dialogue était ouvert — action définitive.
    var deleteTarget by remember { mutableStateOf<com.pulsemix.app.data.Track?>(null) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showFxSheet by remember { mutableStateOf(false) }
    var showPerfSheet by remember { mutableStateOf(false) }
    var showTrackOptions by remember { mutableStateOf(false) }
    var showSuggestions by remember { mutableStateOf(false) }
    var showLyricsSheet by remember { mutableStateOf(false) }
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
        // Export du meilleur passage lancé depuis le menu ⋮ d'un morceau
        // d'ICI : le résultat (chemin du fichier, ou échec) ne s'affichait
        // que dans Bibliothèque — invisible depuis le lecteur.
        val segmentMsg by vm.segmentExportMessage.collectAsStateWithLifecycle()
        segmentMsg?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        // Fin d'un mix : décompte avant l'enchaînement sur un mix du même
        // type. Il démarre pendant les dernières secondes du dernier
        // morceau (la lecture ne s'arrête jamais, même écran éteint). Un
        // tap arrête là — sans ça, la seule échappatoire serait de laisser
        // démarrer le suivant pour le couper aussitôt.
        val autoNextIn by vm.autoNextIn.collectAsStateWithLifecycle()
        autoNextIn?.let { n ->
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    )
                    .clickable { vm.cancelAutoNext() }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "$n",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Fin du mix — le suivant arrive (touche pour arrêter là)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
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
                // Purement informatifs : cliquables, ils ondulaient sous le
                // doigt sans rien faire — un bouton qui ment.
                AssistChip(onClick = {}, enabled = false, label = { Text("${t.bpm} BPM") })
                AssistChip(
                    onClick = {}, enabled = false,
                    label = { Text("${t.keyName} · ${t.camelot}") }
                )
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
                        // Indicateur de phase, pas commande (le saut passe
                        // par ⏭) : désactivé pour ne pas mentir au doigt
                        enabled = false,
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
        // La barre suit la lecture, sauf pendant qu'on la déplace : le
        // doigt garde la main, et rien ne bouge tant qu'il n'est pas
        // relâché. Le déplacement se fait alors en fondu (en DJ, par une
        // vraie transition vers le morceau repris à cet endroit).
        var seeking by remember { mutableStateOf(false) }
        var seekValue by remember { mutableFloatStateOf(0f) }
        // Position demandée, gardée affichée jusqu'à ce que la lecture l'ait
        // rejointe : la transition dure plusieurs secondes, et voir la barre
        // revenir en arrière puis sauter donnerait le sentiment que le geste
        // n'a pas été pris en compte.
        var wanted by remember { mutableStateOf<Float?>(null) }
        var wantedSince by remember { mutableLongStateOf(0L) }
        LaunchedEffect(progress, wanted) {
            val w = wanted ?: return@LaunchedEffect
            // Rejointe, ou garde-fou si la lecture n'y arrive jamais
            if (kotlin.math.abs(progress - w) < 0.02f ||
                System.currentTimeMillis() - wantedSince > 20_000
            ) wanted = null
        }
        Slider(
            value = if (seeking) seekValue else wanted ?: progress,
            onValueChange = {
                seeking = true
                seekValue = it
            },
            onValueChangeFinished = {
                seeking = false
                wanted = seekValue
                wantedSince = System.currentTimeMillis()
                vm.seekToSmooth(seekValue)
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (mode == PlayerMode.DJ) {
            Text(
                "Meilleure minute en cours…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }

        Spacer(Modifier.height(8.dp))

        // ---------------------------------------------------------- transport
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Aléatoire : n'agit qu'en NORMAL/DOUCE (en Mix/DJ la file
            // suit le plan) — affiché ailleurs, il s'allumait sans rien
            // changer. Même tri que le bouton répétition.
            IconButton(
                onClick = { vm.setShuffle(!shuffle) },
                enabled = mode == PlayerMode.NORMAL || mode == PlayerMode.DOUCE
            ) {
                Icon(
                    Icons.Rounded.Shuffle, "Aléatoire",
                    tint = if (shuffle &&
                        (mode == PlayerMode.NORMAL || mode == PlayerMode.DOUCE)
                    ) MaterialTheme.colorScheme.primary
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
                onClick = { deleteTarget = track },
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
            // Répétition (lecture normale) : un appui = la liste en boucle,
            // deux = le morceau en boucle, trois = retour à la normale.
            if (mode == PlayerMode.NORMAL || mode == PlayerMode.DOUCE) {
                val repeat by vm.repeatMode.collectAsStateWithLifecycle()
                IconButton(onClick = { vm.cycleRepeat() }) {
                    Icon(
                        if (repeat == 2) Icons.Rounded.RepeatOne
                        else Icons.Rounded.Repeat,
                        when (repeat) {
                            1 -> "Répéter la liste"
                            2 -> "Répéter le morceau"
                            else -> "Répétition coupée"
                        },
                        tint = if (repeat != 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
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
            // Paroles synchronisées du morceau en cours
            IconButton(
                onClick = { showLyricsSheet = true },
                enabled = track != null
            ) {
                Icon(
                    Icons.Rounded.Notes, "Paroles",
                    tint = if (showLyricsSheet) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (track != null) 0.6f else 0.25f
                    )
                )
            }
            // Menu du morceau en cours (le même que dans la bibliothèque)
            IconButton(
                onClick = { showTrackOptions = true },
                enabled = track != null
            ) {
                Icon(
                    Icons.Rounded.MoreVert, "Options du morceau",
                    tint = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (track != null) 0.6f else 0.25f
                    )
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
                // Panneau « Performance » : commandes DJ manuelles
                // (crossfader, kills basses, boucle, nudge) par-dessus le
                // mixage automatique. Allumé tant qu'une commande est prise.
                val manualFadeOn by vm.manualFadeOn.collectAsStateWithLifecycle()
                val perfKillA by vm.bassKillA.collectAsStateWithLifecycle()
                val perfKillB by vm.bassKillB.collectAsStateWithLifecycle()
                val perfLoop by vm.exitLoopBeats.collectAsStateWithLifecycle()
                val perfActive = manualFadeOn || perfKillA || perfKillB ||
                    perfLoop != 0
                IconButton(onClick = { showPerfSheet = true }) {
                    Icon(
                        Icons.Rounded.GraphicEq, "Performance (contrôles manuels)",
                        tint = if (perfActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // ------------------------------------------------ « set sauvegardé »
        // Après un set DJ enregistré : dire où il est parti (playlist +
        // tracklist), avec un OK pour l'acquitter.
        val setSavedMsg by vm.lastSetSavedMessage.collectAsStateWithLifecycle()
        setSavedMsg?.let { msg ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.clearLastSetSavedMessage() }) {
                    Text("OK")
                }
            }
        }

        // ------------------------------------------------- « et ensuite ? »
        // Suggestions d'enchaînement (hors DJ : le set y est déjà décidé).
        if ((mode == PlayerMode.NORMAL || mode == PlayerMode.MIX) && track != null) {
            TextButton(onClick = { showSuggestions = !showSuggestions }) {
                Text(
                    "Et ensuite ?",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (showSuggestions) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            if (showSuggestions) {
                var suggestions by remember { mutableStateOf<List<Track>?>(null) }
                var added by remember { mutableStateOf(setOf<String>()) }
                // À l'ouverture, puis à chaque changement de morceau tant que
                // la section reste ouverte.
                LaunchedEffect(track?.uri) {
                    suggestions = null
                    added = emptySet()
                    track?.let { suggestions = vm.suggestionsFor(it) }
                }
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Ça enchaînerait bien :",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { showSuggestions = false },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Close, "Fermer",
                                    tint = MaterialTheme.colorScheme.onSurface
                                        .copy(alpha = 0.5f)
                                )
                            }
                        }
                        val sugg = suggestions
                        when {
                            sugg == null -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Recherche…",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            sugg.isEmpty() -> Text(
                                "Aucune suggestion : pas assez de morceaux analysés.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.6f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                            else -> for (s in sugg) {
                                val done = added.contains(s.uri)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            s.title,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurface
                                                .copy(alpha = if (done) 0.35f else 1f)
                                        )
                                        Text(
                                            listOfNotNull(
                                                s.artist.takeIf { it.isNotBlank() },
                                                "${s.bpm} BPM · ${s.camelot}"
                                            ).joinToString(" — "),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurface
                                                .copy(alpha = if (done) 0.3f else 0.6f)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            vm.playNext(s)
                                            added = added + s.uri
                                        },
                                        enabled = !done,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Add, "Jouer ensuite",
                                            tint = if (done)
                                                MaterialTheme.colorScheme.onSurface
                                                    .copy(alpha = 0.25f)
                                            else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
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
    val toDelete = deleteTarget
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
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
                    deleteTarget = null
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Annuler") }
            }
        )
    }

    // ------------------------------------------------------- dialogue Douce
    if (showDouceDialog) {
        // Défaut très doux : le quart le plus calme de la bibliothèque
        var softness by remember { mutableFloatStateOf(0.25f) }
        // Mémorisé sur une clé ARRONDIE (crans de 1/20) : ce décompte trie
        // quatre fois toute la bibliothèque sur le thread principal, et une
        // clé sur la valeur brute le relançait à chaque image tant que le
        // doigt glissait sur le curseur. Au cran près, l'affichage ne change
        // pas et le tri n'est refait qu'aux franchissements de cran.
        val softStep = (softness * 20).toInt()
        val matching = remember(softStep, tracks) { vm.softCount(softness) }
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

    if (showPerfSheet && mode == PlayerMode.DJ) {
        PerformanceSheet(vm) { showPerfSheet = false }
    }

    // Menu d'options du morceau en cours (partagé avec la bibliothèque) —
    // sur la version à jour de la bibliothèque si elle existe.
    if (showTrackOptions) {
        val cur = track
        if (cur != null) {
            val fresh = tracks.firstOrNull { it.uri == cur.uri } ?: cur
            TrackOptionsDialogs(vm, tracks, fresh) { showTrackOptions = false }
        } else {
            showTrackOptions = false
        }
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
                            // Désactivé tant que le nom est vide : une
                            // playlist sans nom serait injouable — même
                            // garde-fou que côté bibliothèque.
                            Button(
                                enabled = name.isNotBlank(),
                                onClick = {
                                    vm.savePlaylistFromQueue(name.trim())
                                    showSavePlaylist = false
                                }
                            ) { Text("Enregistrer") }
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
                        "Le déroulé du set : ce qui est passé et ce qui vient. " +
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
                    // Le rang réel du lecteur, pas une recherche par URI :
                    // une même chanson présente deux fois désignerait la
                    // mauvaise ligne. Repli sur l'URI si le rang manque.
                    val playing by vm.currentIndex.collectAsStateWithLifecycle()
                    val currentIndex =
                        if (playing in queue.indices) playing
                        else queue.indexOfFirst { it.uri == track?.uri }
                    val listState = rememberLazyListState()
                    // Ouvrir la file sur le morceau en cours plutôt qu'en tête :
                    // au bout d'une heure de set, il était à cinquante lignes
                    // de là et il fallait le chercher à la main. Un cran plus
                    // haut, pour qu'on voie d'où l'on vient.
                    //
                    // Une seule fois, dès que le rang est connu : recentrer à
                    // chaque changement de morceau arracherait la liste sous
                    // le doigt de qui la parcourt.
                    var positioned by remember { mutableStateOf(false) }
                    LaunchedEffect(currentIndex) {
                        if (!positioned && currentIndex >= 0) {
                            positioned = true
                            listState.scrollToItem((currentIndex - 1).coerceAtLeast(0))
                        }
                    }
                    // Clés par OCCURRENCE : l'URI seul plantait la liste dès
                    // qu'une même chanson figurait deux fois dans la file
                    // (clés dupliquées) ; l'index seul empêchait le nœud de
                    // suivre l'élément pendant un glisser. L'URI, suffixé
                    // seulement pour les doublons, donne les deux.
                    val queueKeys = remember(queue) {
                        val seen = HashMap<String, Int>()
                        queue.map { t ->
                            val n = (seen[t.uri] ?: 0) + 1
                            seen[t.uri] = n
                            if (n == 1) t.uri else "${t.uri}#$n"
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.heightIn(max = 480.dp)
                    ) {
                        // key : pendant un glisser, la ligne déménage dans la
                        // liste — le nœud (et le doigt posé dessus) doit
                        // suivre l'ÉLÉMENT, pas rester à la position.
                        itemsIndexed(queue, key = { i, _ -> queueKeys[i] }) { i, t ->
                            val isCurrent = i == currentIndex
                            // Déjà passé : estompé, pour que l'œil trouve tout
                            // de suite où en est la lecture.
                            val played = currentIndex >= 0 && i < currentIndex
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
                                        fontWeight = if (isCurrent) FontWeight.Bold else null,
                                        color = when {
                                            isCurrent -> MaterialTheme.colorScheme.primary
                                            played -> MaterialTheme.colorScheme.onSurface
                                                .copy(alpha = 0.45f)
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                    Text(
                                        "${t.bpm} BPM · ${t.camelot}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                            .copy(alpha = if (played) 0.35f else 0.6f)
                                    )
                                }
                                if (mode != PlayerMode.DJ) {
                                    // Poignée de réordonnancement : on la
                                    // tient et on glisse, la ligne échange sa
                                    // place à chaque rangée franchie. La
                                    // liste bouge sous le doigt, d'où
                                    // l'accumulateur remis à zéro à chaque
                                    // échange plutôt qu'un vrai survol.
                                    var dragAccum by remember { mutableFloatStateOf(0f) }
                                    var rowIndex by remember { mutableStateOf(i) }
                                    rowIndex = i
                                    Icon(
                                        Icons.Rounded.DragHandle, "Réordonner",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        modifier = Modifier
                                            .size(32.dp)
                                            .pointerInput(Unit) {
                                                detectDragGestures(
                                                    onDragStart = { dragAccum = 0f },
                                                    onDrag = { change, amount ->
                                                        change.consume()
                                                        dragAccum += amount.y
                                                        val step = 56.dp.toPx()
                                                        // Taille lue au moment
                                                        // du geste : la lambda
                                                        // est figée à la 1re
                                                        // composition
                                                        val n = vm.queue.value.size
                                                        while (dragAccum > step / 2 &&
                                                            rowIndex < n - 1
                                                        ) {
                                                            vm.moveQueueItem(rowIndex, rowIndex + 1)
                                                            rowIndex++
                                                            dragAccum -= step
                                                        }
                                                        while (dragAccum < -step / 2 &&
                                                            rowIndex > 0
                                                        ) {
                                                            vm.moveQueueItem(rowIndex, rowIndex - 1)
                                                            rowIndex--
                                                            dragAccum += step
                                                        }
                                                    }
                                                )
                                            }
                                    )
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

    // ------------------------------------------------- feuille des paroles
    if (showLyricsSheet) {
        val lyrics by vm.lyricsState.collectAsStateWithLifecycle()
        // Chargées à l'ouverture, rechargées si le morceau change pendant
        // que la feuille est ouverte.
        LaunchedEffect(track?.uri) {
            track?.let { vm.loadLyrics(it) }
        }
        ModalBottomSheet(onDismissRequest = {
            showLyricsSheet = false
            vm.clearLyrics()
        }) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Paroles",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        showLyricsSheet = false
                        vm.clearLyrics()
                    }) {
                        Icon(
                            Icons.Rounded.Close, "Fermer",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                when (val st = lyrics) {
                    is Lyrics.State.Loaded -> {
                        val lines = st.lines
                        // Toutes les lignes à 0 ms = paroles non
                        // synchronisées : rien à surligner ni à suivre.
                        // En DJ non plus : la progression est celle du
                        // SEGMENT joué par le deck, pas du morceau entier
                        // — le calage serait faux.
                        val synced = lines.any { it.first > 0L } &&
                            mode != PlayerMode.DJ
                        val durMs = track?.durationMs ?: 0L
                        val posMs = (progress * durMs).toLong()
                        val currentLine =
                            if (synced) lines.indexOfLast { it.first <= posMs }
                            else -1
                        val listState = rememberLazyListState()
                        // Suivi throttlé : on ne défile que quand la ligne
                        // courante change, jamais à chaque tick de position.
                        LaunchedEffect(currentLine) {
                            if (currentLine >= 0) {
                                listState.animateScrollToItem(
                                    (currentLine - 2).coerceAtLeast(0)
                                )
                            }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.heightIn(max = 480.dp)
                        ) {
                            itemsIndexed(lines) { i, line ->
                                val isCurrent = i == currentLine
                                Text(
                                    line.second,
                                    style = if (isCurrent)
                                        MaterialTheme.typography.titleMedium
                                    else MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isCurrent) FontWeight.Bold
                                    else null,
                                    color = if (isCurrent)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                        .copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                )
                            }
                            item { Spacer(Modifier.height(24.dp)) }
                        }
                    }
                    is Lyrics.State.None -> {
                        Text(
                            "Pas de paroles trouvées.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                                .copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Recherche des paroles…")
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
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
                    // Transitions pro : drop-swap de festival quand le
                    // morceau entrant a un vrai drop détecté. Réglage
                    // persistant, applicable à chaud (prochaine transition).
                    val proTransitions by
                        vm.proTransitions.collectAsStateWithLifecycle()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Transitions pro (drop-swap)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = proTransitions,
                            onCheckedChange = { vm.setProTransitions(it) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Un mix dure toujours au moins une heure : les catégories
                // commencent donc à 1 h (« Auto » = durée libre, jamais
                // sous l'heure non plus).
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = targetMin == null,
                        onClick = { targetMin = null },
                        label = { Text("Auto") }
                    )
                    for ((label, minutes) in listOf(
                        "1 h" to 60, "1 h 30" to 90, "2 h" to 120, "3 h" to 180
                    )) {
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
                                    // Les critères suivent le mix : ils
                                    // servent à en régénérer un semblable
                                    // quand celui-ci arrive au bout.
                                    vm.startMix(
                                        plan, mixSheetDj, targetMin, selectedGenre
                                    )
                                    showMixSheet = false
                                },
                                onStartEdited = { edited, dj ->
                                    vm.startMix(
                                        edited, dj, targetMin, selectedGenre
                                    )
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

// -------------------------------------------------- éditeur de plan de mix

/** Écart de tempo relatif ≤ 8 % (les deux BPM connus). */
private fun bpmClose(a: Float, b: Float): Boolean =
    a > 0f && b > 0f && kotlin.math.abs(a - b) / a <= 0.08f

/**
 * Qualité d'une transition a → b, fonction PURE :
 *  2 (vert)   = tonalité compatible (Camelot ≥ 0,8) ET tempo proche
 *               (écart relatif ≤ 8 %, double/moitié admis) ;
 *  1 (orange) = l'un des deux seulement ;
 *  0 (rouge)  = aucun.
 */
private fun transitionQuality(a: Track, b: Track): Int {
    val keyOk = MixEngine.camelotScore(a.camelot, b.camelot) >= 0.8f
    val bpmOk = bpmClose(a.bpm, b.bpm) ||
        bpmClose(a.bpm, b.bpm * 2f) ||
        bpmClose(a.bpm, b.bpm / 2f)
    return when {
        keyOk && bpmOk -> 2
        keyOk || bpmOk -> 1
        else -> 0
    }
}

/**
 * Reconstruit un plan à partir de la liste aplatie éditée. Tant que chaque
 * phase reste d'un seul tenant, les phases d'origine sont reconstituées
 * (avec leurs tailles restantes) ; un réordonnancement à travers les phases
 * donne une seule phase « Mix modifié » — plus simple et honnête.
 */
private fun rebuildPlan(
    plan: MixEngine.MixPlan,
    entries: List<Pair<String, Track>>
): MixEngine.MixPlan {
    val runs = ArrayList<Pair<String, MutableList<Track>>>()
    for ((phase, t) in entries) {
        val last = runs.lastOrNull()
        if (last != null && last.first == phase) last.second.add(t)
        else runs.add(phase to mutableListOf(t))
    }
    val names = runs.map { it.first }
    val phases =
        if (names.size == names.distinct().size)
            runs.map { MixEngine.Phase(it.first, it.second) }
        else listOf(MixEngine.Phase("Mix modifié", entries.map { it.second }))
    return MixEngine.MixPlan(plan.id, plan.name, plan.description, phases)
}

/**
 * Édition d'un plan proposé : morceaux supprimables (croix), déplaçables
 * (▲▼), et entre deux morceaux un badge ● vert/orange/rouge sur la qualité
 * de la transition. Lancement direct en mix ou en DJ.
 */
@Composable
private fun MixPlanEditorDialog(
    plan: MixEngine.MixPlan,
    onDismiss: () -> Unit,
    onStart: (MixEngine.MixPlan, Boolean) -> Unit
) {
    // Toutes les phases aplaties : (nom de phase, morceau)
    var entries by remember(plan) {
        mutableStateOf(
            plan.phases.flatMap { ph -> ph.tracks.map { ph.name to it } }
        )
    }
    fun swap(a: Int, b: Int) {
        if (a !in entries.indices || b !in entries.indices) return
        val m = entries.toMutableList()
        val tmp = m[a]
        m[a] = m[b]
        m[b] = tmp
        entries = m
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifier « ${plan.name} »") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "● vert : tonalité et tempo compatibles · orange : l'un " +
                        "des deux · rouge : aucun.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (entries.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Plus aucun morceau dans ce plan.")
                }
                entries.forEachIndexed { i, entry ->
                    val (phase, t) = entry
                    // Badge de transition depuis le morceau précédent
                    if (i > 0) {
                        val q = transitionQuality(entries[i - 1].second, t)
                        Text(
                            "●",
                            style = MaterialTheme.typography.labelSmall,
                            color = when (q) {
                                2 -> Color(0xFF43A047)
                                1 -> Color(0xFFFB8C00)
                                else -> Color(0xFFE53935)
                            },
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                    // En-tête de phase (au premier morceau de chaque suite)
                    if (i == 0 || entries[i - 1].first != phase) {
                        Text(
                            phase,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                t.title,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${t.bpm} BPM · ${t.camelot}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.6f)
                            )
                        }
                        IconButton(
                            onClick = { swap(i, i - 1) },
                            enabled = i > 0,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Text(
                                "▲",
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = if (i > 0) 0.7f else 0.25f)
                            )
                        }
                        IconButton(
                            onClick = { swap(i, i + 1) },
                            enabled = i < entries.size - 1,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Text(
                                "▼",
                                color = MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (i < entries.size - 1) 0.7f else 0.25f
                                )
                            )
                        }
                        IconButton(
                            onClick = {
                                entries = entries.toMutableList()
                                    .also { it.removeAt(i) }
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Close, "Retirer",
                                tint = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    enabled = entries.isNotEmpty(),
                    onClick = { onStart(rebuildPlan(plan, entries), false) }
                ) { Text("Lancer en mix") }
                Button(
                    enabled = entries.isNotEmpty(),
                    onClick = { onStart(rebuildPlan(plan, entries), true) }
                ) { Text("Lancer en DJ") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Fermer") }
        }
    )
}

@Composable
private fun MixPlanCard(
    plan: MixEngine.MixPlan,
    dj: Boolean,
    onRemoveTrack: (Track) -> Unit,
    onRehearse: () -> Unit,
    onStart: () -> Unit,
    onStartEdited: (MixEngine.MixPlan, Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    if (showEditor) {
        MixPlanEditorDialog(
            plan = plan,
            onDismiss = { showEditor = false },
            onStart = { edited, djMode ->
                showEditor = false
                onStartEdited(edited, djMode)
            }
        )
    }
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
            TextButton(
                onClick = { showEditor = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Modifier…") }
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
 * - Tap : monte d'un cran, jusqu'à +3 puis retour à zéro.
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
 * Panneau « Effets » : tous les contrôles live à crans (tap = +1 cran puis
 * retour à zéro, appui long + glisser = régler), plus la boucle live
 * (maintenir).
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
            // Aligné sur le geste réel (onTap) : chaque tap monte d'un cran
            // jusqu'à +3, le suivant revient à zéro — pas un simple on/off.
            Text(
                "Tap : monter d'un cran (puis retour à zéro). " +
                    "Appui long puis glisser : régler par crans.",
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

/**
 * Panneau « Performance » : commandes DJ MANUELLES par-dessus le moteur
 * automatique (qui reste le comportement par défaut). Crossfader A↔B —
 * actif pendant les transitions seulement, « Auto » rend la main au
 * moteur en douceur —, transition immédiate, nudge tempo, boucle de
 * sortie 4/8 temps et kills de basses par deck. Tout tient sans
 * défilement : c'est un panneau de jeu, une main sur l'écran.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PerformanceSheet(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val inTransition by vm.djTransition.collectAsStateWithLifecycle()
    val manualFadeOn by vm.manualFadeOn.collectAsStateWithLifecycle()
    val killA by vm.bassKillA.collectAsStateWithLifecycle()
    val killB by vm.bassKillB.collectAsStateWithLifecycle()
    val loopBeats by vm.exitLoopBeats.collectAsStateWithLifecycle()
    // Position locale du fader : le moteur ne publie pas sa progression,
    // le curseur ne bouge que sous le doigt.
    var faderPos by remember { mutableFloatStateOf(0f) }
    // Fermer la feuille rend la main au moteur : un fader resté saisi en
    // arrière-plan bloquerait la fin de la transition sans que rien à
    // l'écran ne le montre. Les kills et la boucle, eux, sont des
    // bascules assumées : ils restent.
    val dismiss = {
        if (manualFadeOn) vm.setManualFade(null)
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Performance",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // Transition immédiate : le même chemin que « suivant »
                // en DJ (fondu court, calé sur la mesure)
                Button(onClick = { vm.mixNow() }) { Text("Mixer maintenant") }
            }

            // ------------------------------------------------- crossfader
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "A",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Slider(
                    value = faderPos,
                    onValueChange = {
                        faderPos = it
                        vm.setManualFade(it)
                    },
                    enabled = inTransition,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    "B",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = { vm.setManualFade(null) },
                    enabled = manualFadeOn
                ) { Text("Auto") }
            }
            Text(
                when {
                    manualFadeOn ->
                        "Fader saisi : le fondu suit le doigt. « Auto » " +
                            "rend la main au moteur, en douceur."
                    inTransition ->
                        "Transition en cours : saisir le fader pour " +
                            "prendre la main sur le fondu."
                    else ->
                        "Le crossfader est actif pendant les transitions " +
                            "(deux decks en vol)."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(4.dp))

            // -------------------------------------------- nudge et boucle
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Nudge : ± momentané, façon pichenette sur le plateau —
                // le moteur résorbe la retouche de lui-même ensuite
                OutlinedButton(onClick = { vm.nudgeTempo(-0.002f) }) {
                    Text("− tempo")
                }
                OutlinedButton(onClick = { vm.nudgeTempo(+0.002f) }) {
                    Text("+ tempo")
                }
                Spacer(Modifier.weight(1f))
                // Boucle de sortie : tenir les derniers temps du morceau
                // en attendant de lancer le mix (se coupe toute seule à
                // la fin de la transition)
                FilterChip(
                    selected = loopBeats == 4,
                    onClick = { vm.setExitLoop(if (loopBeats == 4) 0 else 4) },
                    label = { Text("Boucle 4") }
                )
                FilterChip(
                    selected = loopBeats == 8,
                    onClick = { vm.setExitLoop(if (loopBeats == 8) 0 else 8) },
                    label = { Text("Boucle 8") }
                )
            }

            // ---------------------------------------------- kills basses
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = killA,
                    onClick = { vm.setBassKill(true, !killA) },
                    label = { Text("Kill basses A") }
                )
                FilterChip(
                    selected = killB,
                    onClick = { vm.setBassKill(false, !killB) },
                    enabled = inTransition,
                    label = { Text("Kill basses B") }
                )
                Spacer(Modifier.weight(1f))
            }
            Text(
                "A = morceau en cours, B = morceau entrant. Les kills " +
                    "priment sur l'échange de basses automatique.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
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
