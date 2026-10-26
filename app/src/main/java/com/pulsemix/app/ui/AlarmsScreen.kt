package com.pulsemix.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulsemix.app.PlayerViewModel
import com.pulsemix.app.player.Alarm
import com.pulsemix.app.player.AlarmClock
import com.pulsemix.app.player.AlarmSchedule
import com.pulsemix.app.player.CalendarAlarms
import com.pulsemix.app.player.CalendarConfig
import kotlinx.coroutines.delay

/** Jours de la semaine (java.time : 1 = lundi) → initiale, nom court. */
private val DAYS = listOf(
    1 to ("L" to "lun."), 2 to ("M" to "mar."), 3 to ("M" to "mer."),
    4 to ("J" to "jeu."), 5 to ("V" to "ven."), 6 to ("S" to "sam."),
    7 to ("D" to "dim.")
)

private fun fmtDate(ms: Long): String =
    java.text.SimpleDateFormat("EEE dd/MM HH:mm", java.util.Locale.FRANCE)
        .format(java.util.Date(ms))

/** « dans 7 h 12 min », « dans 2 j 3 h ». */
private fun inText(ms: Long, now: Long): String {
    val min = ((ms - now + 59_999L) / 60_000L).coerceAtLeast(0L)
    val d = min / 1440
    val h = (min % 1440) / 60
    val m = min % 60
    return when {
        d > 0 -> "dans $d j $h h"
        h > 0 -> "dans $h h $m min"
        else -> "dans $m min"
    }
}

private fun daysText(days: Set<Int>): String = when (days) {
    emptySet<Int>() -> "Une seule fois"
    (1..7).toSet() -> "Tous les jours"
    (1..5).toSet() -> "En semaine"
    setOf(6, 7) -> "Le week-end"
    else -> DAYS.filter { it.first in days }.joinToString(", ") { it.second.second }
}

/**
 * Onglet « Réveils » : la liste des réveils (bouton + pour en ajouter un,
 * ouvert déplié), leurs réglages communs, et les sonneries de l'agenda.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmsScreen(vm: PlayerViewModel, modifier: Modifier = Modifier) {
    val alarms by vm.alarms.collectAsStateWithLifecycle()
    val armed by vm.alarmArmedInfo.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val smart by vm.smartPlaylists.collectAsStateWithLifecycle()

    // Permissions et états système relus à chaque retour à l'écran (voir
    // SettingsScreen) ; l'horloge avance toutes les 30 s pour les « dans… ».
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(resumeTick) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    val sources = remember(playlists, smart) {
        AlarmClock.MIX_CHOICES.map { "mix:${it.first}" to it.second } +
            playlists.map { "playlist:${it.name}" to "Playlist « ${it.name} »" } +
            smart.map { "smart:${it.name}" to "Intelligente « ${it.name} »" }
    }

    var expanded by remember { mutableStateOf(setOf<Int>()) }
    var scrollTo by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(scrollTo, alarms) {
        val id = scrollTo ?: return@LaunchedEffect
        val idx = alarms.indexOfFirst { it.id == id }
        if (idx >= 0) {
            listState.animateScrollToItem(1 + idx)
            scrollTo = null
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column {
                    Text(
                        "Réveils",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (armed.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            armed,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (armed.contains("⚠")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    ReliabilityWarnings(resumeTick)
                    if (alarms.isEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Aucun réveil. Touche + pour en ajouter un.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            items(alarms, key = { it.id }) { a ->
                AlarmCard(
                    alarm = a,
                    now = now,
                    expanded = a.id in expanded,
                    sources = sources,
                    onToggleExpand = {
                        expanded = if (a.id in expanded) expanded - a.id else expanded + a.id
                    },
                    onUpdate = { vm.updateAlarm(it) },
                    onDelete = {
                        expanded = expanded - a.id
                        vm.deleteAlarm(a.id)
                    },
                    onToggleSkip = { vm.toggleSkipNext(a.id) }
                )
            }
            item { CommonSettingsCard(vm) }
            item { CalendarCard(vm, sources, resumeTick, now) }
        }
        FloatingActionButton(
            onClick = {
                val id = vm.addAlarm()
                expanded = expanded + id
                scrollTo = id
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) { Icon(Icons.Rounded.Add, contentDescription = "Ajouter un réveil") }
    }
}

/** Batterie et alarmes exactes : ce qui peut empêcher un réveil de sonner. */
@Composable
private fun ReliabilityWarnings(resumeTick: Int) {
    val context = LocalContext.current
    val batteryOk = remember(resumeTick) {
        context.getSystemService(android.os.PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    val exactDenied = remember(resumeTick) {
        !AlarmClock.exactAlarmsAllowed(context)
    }
    if (!batteryOk) {
        Spacer(Modifier.height(6.dp))
        Text(
            "⚠ Réveil peu fiable : Android peut empêcher PulseMix de lancer la " +
                "musique depuis l'arrière-plan.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
        OutlinedButton(onClick = {
            try {
                context.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${context.packageName}")
                    )
                )
            } catch (_: Exception) {
            }
        }) { Text("Autoriser PulseMix en arrière-plan") }
    }
    if (exactDenied) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Les alarmes exactes sont désactivées pour PulseMix : les réveils " +
                "pourront dériver de plusieurs minutes.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
        OutlinedButton(onClick = {
            try {
                context.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:${context.packageName}")
                    )
                )
            } catch (_: Exception) {
            }
        }) { Text("Autoriser les alarmes exactes") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AlarmCard(
    alarm: Alarm,
    now: Long,
    expanded: Boolean,
    sources: List<Pair<String, String>>,
    onToggleExpand: () -> Unit,
    onUpdate: (Alarm) -> Unit,
    onDelete: () -> Unit,
    onToggleSkip: () -> Unit
) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    var showTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val skipping = AlarmSchedule.isSkipping(alarm, now)
    val next = AlarmSchedule.nextFire(alarm, now)

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "%02d:%02d".format(alarm.hour, alarm.minute),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else dim
                    )
                    Text(
                        listOf(alarm.label, daysText(alarm.days))
                            .filter(String::isNotBlank).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        when {
                            !alarm.enabled -> "Désactivé"
                            skipping && next != null ->
                                "Prochaine sonnerie sautée — reprend ${fmtDate(next)}"
                            next != null -> "Sonne ${inText(next, now)} · ${fmtDate(next)}"
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (skipping && alarm.enabled) MaterialTheme.colorScheme.secondary
                        else dim
                    )
                    if (!expanded) {
                        Text(
                            AlarmClock.sourceLabel(alarm.source),
                            style = MaterialTheme.typography.labelSmall,
                            color = dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Switch(
                    checked = alarm.enabled,
                    // Réactivé : repart sans saut en attente
                    onCheckedChange = { onUpdate(alarm.copy(enabled = it, skipAt = 0L)) }
                )
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Replier" else "Déplier"
                )
            }

            AnimatedVisibility(expanded) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { showTime = true }) { Text("Changer l'heure") }

                    Spacer(Modifier.height(8.dp))
                    var label by remember(alarm.id) { mutableStateOf(alarm.label) }
                    OutlinedTextField(
                        value = label,
                        onValueChange = {
                            label = it
                            onUpdate(alarm.copy(label = it))
                        },
                        label = { Text("Nom (facultatif)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))
                    Text("Répéter", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DAYS.forEach { (num, names) ->
                            val on = num in alarm.days
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(CircleShape)
                                    .background(
                                        if (on) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable {
                                        onUpdate(
                                            alarm.copy(
                                                days = if (on) alarm.days - num else alarm.days + num
                                            )
                                        )
                                    }
                            ) {
                                Text(
                                    names.first,
                                    fontWeight = FontWeight.Bold,
                                    color = if (on) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "Une fois" to emptySet(),
                            "Semaine" to (1..5).toSet(),
                            "Week-end" to setOf(6, 7),
                            "Tous les jours" to (1..7).toSet()
                        ).forEach { (name, set) ->
                            FilterChip(
                                selected = alarm.days == set,
                                onClick = { onUpdate(alarm.copy(days = set)) },
                                label = { Text(name) }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("Musique du réveil", style = MaterialTheme.typography.bodyMedium)
                    SourcePicker(alarm.source, sources) { onUpdate(alarm.copy(source = it)) }

                    if (alarm.repeating && alarm.enabled) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onToggleSkip) {
                            Text(
                                if (skipping) "Réactiver la prochaine sonnerie"
                                else "Désactiver la prochaine sonnerie" +
                                    (next?.let { " (${fmtDate(it)})" } ?: "")
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Rounded.Delete, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Supprimer ce réveil")
                    }
                }
            }
        }
    }

    if (showTime) {
        val state = rememberTimePickerState(
            initialHour = alarm.hour, initialMinute = alarm.minute, is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("Heure du réveil") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onUpdate(alarm.copy(hour = state.hour, minute = state.minute))
                    showTime = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) { Text("Annuler") }
            }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer ce réveil ?") },
            text = { Text("%02d:%02d — ${daysText(alarm.days)}".format(alarm.hour, alarm.minute)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Annuler") }
            }
        )
    }
}

/** Choix de ce qui joue : un type de mix / DJ, une playlist, ou une
 *  playlist intelligente. */
@Composable
private fun SourcePicker(
    current: String,
    sources: List<Pair<String, String>>,
    onPick: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                AlarmClock.sourceLabel(current),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(Icons.Rounded.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            var lastGroup = ""
            sources.forEach { (id, label) ->
                val group = when {
                    id.startsWith("playlist:") -> "Playlists"
                    id.startsWith("smart:") -> "Playlists intelligentes"
                    else -> "Mix / DJ"
                }
                if (group != lastGroup) {
                    lastGroup = group
                    Text(
                        group,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            fontWeight = if (id == current) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        open = false
                        onPick(id)
                    }
                )
            }
        }
    }
}

/** Réglages communs à tous les réveils. */
@Composable
private fun CommonSettingsCard(vm: PlayerViewModel) {
    val ramp by vm.alarmRamp.collectAsStateWithLifecycle()
    val progressive by vm.alarmProgressive.collectAsStateWithLifecycle()
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Pour tous les réveils", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Montée du volume : $ramp min", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = ramp.toFloat(),
                onValueChange = { vm.setAlarmRamp(it.toInt()) },
                valueRange = 1f..10f,
                steps = 8
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = progressive, onCheckedChange = { vm.setAlarmProgressive(it) })
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Réveil progressif", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Du calme vers l'énergique : la file du réveil est " +
                            "réordonnée par énergie croissante avant de jouer.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

/** Sonneries de l'agenda Google (fournisseur d'agenda d'Android). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarCard(
    vm: PlayerViewModel,
    sources: List<Pair<String, String>>,
    resumeTick: Int,
    now: Long
) {
    val context = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    var granted by remember(resumeTick) { mutableStateOf(vm.calendarPermission()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        if (ok) {
            vm.setCalendarEnabled(true)
            vm.onCalendarPermissionGranted()
        }
    }
    val enabled by vm.calendarEnabled.collectAsStateWithLifecycle()
    val configs by vm.calendarConfigs.collectAsStateWithLifecycle()
    val next by vm.calendarNext.collectAsStateWithLifecycle()
    var calendars by remember { mutableStateOf<List<CalendarAlarms.CalendarInfo>?>(null) }
    LaunchedEffect(granted, resumeTick) {
        calendars = if (granted) vm.listCalendars() else null
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Event, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Agenda Google", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Une sonnerie musicale pour tes événements : à chacune de leurs " +
                    "notifications, ou au début de chaque événement — au choix, " +
                    "agenda par agenda.",
                style = MaterialTheme.typography.labelSmall,
                color = dim
            )
            Spacer(Modifier.height(8.dp))
            if (!granted) {
                Button(onClick = { launcher.launch(Manifest.permission.READ_CALENDAR) }) {
                    Text("Se connecter à Google Agenda")
                }
                Text(
                    "PulseMix lit l'agenda Google synchronisé sur ce téléphone " +
                        "(compte Google des paramètres Android). Rien n'est envoyé " +
                        "nulle part.",
                    style = MaterialTheme.typography.labelSmall,
                    color = dim
                )
            } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = { vm.setCalendarEnabled(it) })
                Spacer(Modifier.width(10.dp))
                Text("Sonner pour l'agenda", style = MaterialTheme.typography.bodyLarge)
            }
            if (enabled) {
            Spacer(Modifier.height(4.dp))
            Text(
                next?.let { "Prochaine sonnerie agenda : ${fmtDate(it.at)} " +
                    "(${inText(it.at, now)}) — ${it.title}" }
                    ?: "Aucune sonnerie d'agenda dans les 8 prochains jours.",
                style = MaterialTheme.typography.labelSmall,
                color = dim
            )
            val list = calendars
            when {
                list == null -> Text("Lecture des agendas…", style = MaterialTheme.typography.bodyMedium)
                list.isEmpty() -> {
                    Text(
                        "Aucun agenda sur ce téléphone. Ajoute ton compte Google " +
                            "dans les paramètres Android (Comptes) et active la " +
                            "synchronisation de l'agenda.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(onClick = {
                        try {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_ADD_ACCOUNT)
                                    .putExtra(
                                        android.provider.Settings.EXTRA_ACCOUNT_TYPES,
                                        arrayOf("com.google")
                                    )
                            )
                        } catch (_: Exception) {
                        }
                    }) { Text("Ajouter un compte Google") }
                }
                else -> {
                    var lastAccount = ""
                    list.forEach { cal ->
                        if (cal.account != lastAccount) {
                            lastAccount = cal.account
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Text(
                                cal.account.ifBlank { "Sur le téléphone" },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        val cfg = configs[cal.id] ?: CalendarConfig(cal.id)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(cal.color or 0xFF000000.toInt()))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                cal.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Switch(
                                checked = cfg.enabled,
                                onCheckedChange = { vm.setCalendarConfig(cfg.copy(enabled = it)) }
                            )
                        }
                        if (cfg.enabled) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = cfg.mode == CalendarConfig.MODE_REMINDER,
                                    onClick = {
                                        vm.setCalendarConfig(cfg.copy(mode = CalendarConfig.MODE_REMINDER))
                                    },
                                    label = { Text("À chaque notification") }
                                )
                                FilterChip(
                                    selected = cfg.mode == CalendarConfig.MODE_EVENT,
                                    onClick = {
                                        vm.setCalendarConfig(cfg.copy(mode = CalendarConfig.MODE_EVENT))
                                    },
                                    label = { Text("Au début de l'événement") }
                                )
                            }
                            SourcePicker(cfg.source, sources) {
                                vm.setCalendarConfig(cfg.copy(source = it))
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
            }
            }
        }
    }
}
