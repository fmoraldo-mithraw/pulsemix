package com.pulsemix.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulsemix.app.PlayerViewModel

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: PlayerViewModel, modifier: Modifier = Modifier) {
    val skipIntros by vm.skipIntros.collectAsStateWithLifecycle()
    val normalize by vm.normalizeVolume.collectAsStateWithLifecycle()
    val eq by vm.eqBands.collectAsStateWithLifecycle()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            "Réglages",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))

        SettingSwitch(
            title = "Sauter les intros parlées",
            subtitle = "Démarre au début de la musique quand un sketch précède " +
                "le morceau (s'applique aux prochaines lectures).",
            checked = skipIntros,
            onChange = { vm.setSkipIntros(it) }
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        val crossfade by vm.crossfade.collectAsStateWithLifecycle()
        SettingSwitch(
            title = "Fondu entre les morceaux",
            subtitle = "Les morceaux se chevauchent vraiment au lieu de " +
                "s'enchaîner sec, en lecture classique comme en mix. " +
                "S'applique au bouton « suivant » et aux déplacements sur la " +
                "barre. Le mode DJ a ses propres transitions et n'est pas " +
                "concerné.",
            checked = crossfade,
            onChange = { vm.setCrossfade(it) }
        )
        if (crossfade) {
            val seconds by vm.crossfadeSeconds.collectAsStateWithLifecycle()
            Spacer(Modifier.height(8.dp))
            Text(
                "Durée du fondu : $seconds s",
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = seconds.toFloat(),
                onValueChange = { vm.setCrossfadeSeconds(kotlin.math.round(it).toInt()) },
                valueRange = 3f..15f,
                steps = 11
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        SettingSwitch(
            title = "Normaliser le volume",
            subtitle = "Atténue les morceaux masterisés fort pour un niveau " +
                "homogène entre les morceaux et dans les transitions DJ.",
            checked = normalize,
            onChange = { vm.setNormalizeVolume(it) }
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // ------------------------------------------- tags dans les fichiers
        val writeTags by vm.writeTagsToFiles.collectAsStateWithLifecycle()
        val tagWriteMsg by vm.tagWriteMessage.collectAsStateWithLifecycle()
        val writeProg by vm.tagWriteProgress.collectAsStateWithLifecycle()
        SettingSwitch(
            title = "Écrire les tags dans les fichiers",
            subtitle = "Par défaut, les corrections de titre et d'artiste ne " +
                "vivent que dans PulseMix. Activé, chaque correction est aussi " +
                "écrite dans le fichier audio — les autres lecteurs la verront. " +
                "L'audio n'est pas réencodé, la qualité est intacte.",
            checked = writeTags,
            onChange = { vm.setWriteTagsToFiles(it) }
        )
        if (writeTags) {
            // Rattrapage de TOUT ce qui a été corrigé avant d'activer
            // l'option : la bibliothèque fait foi, chaque fichier est lu et
            // seuls ceux dont les tags diffèrent sont réécrits. Continue en
            // arrière-plan, appli fermée.
            val coverProg by vm.coverProgress.collectAsStateWithLifecycle()
            androidx.compose.material3.OutlinedButton(
                onClick = { vm.writeAllTagsToFiles() },
                // Un seul passage à la fois : un appui pendant le passage
                // jaquettes serait silencieusement perdu par le service
                enabled = writeProg == null && coverProg == null
            ) {
                Text(
                    writeProg?.let { (done, total) -> "Écriture $done/$total…" }
                        ?: "Reporter tous les tags de la bibliothèque"
                )
            }
        }
        tagWriteMsg?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // ------------------------------------------------------ réveil matin
        val context = androidx.compose.ui.platform.LocalContext.current
        val alarmOn by vm.alarmEnabled.collectAsStateWithLifecycle()
        val alarmH by vm.alarmHour.collectAsStateWithLifecycle()
        val alarmM by vm.alarmMinute.collectAsStateWithLifecycle()
        val alarmMix by vm.alarmMixId.collectAsStateWithLifecycle()
        val alarmRamp by vm.alarmRamp.collectAsStateWithLifecycle()
        var showTimePicker by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(false)
        }

        Text("Réveil matin", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        SettingSwitch(
            title = "Réveil musical",
            subtitle = "La musique se lance à l'heure choisie et le volume " +
                "monte petit à petit jusqu'au maximum du téléphone.",
            checked = alarmOn,
            onChange = { vm.setAlarm(it, alarmH, alarmM, alarmMix, alarmRamp) }
        )
        if (alarmOn) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%02d:%02d".format(alarmH, alarmM),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(12.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = { showTimePicker = true }
                ) { Text("Changer l'heure") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Type de mix au réveil :",
                style = MaterialTheme.typography.bodyMedium
            )
            com.pulsemix.app.player.AlarmClock.MIX_CHOICES.forEach { (id, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        vm.setAlarm(true, alarmH, alarmM, id, alarmRamp)
                    }
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = id == alarmMix,
                        onClick = {
                            vm.setAlarm(true, alarmH, alarmM, id, alarmRamp)
                        }
                    )
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Montée du volume : $alarmRamp min",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = alarmRamp.toFloat(),
                onValueChange = {
                    vm.setAlarm(true, alarmH, alarmM, alarmMix, it.toInt())
                },
                valueRange = 1f..10f,
                steps = 8
            )
            // Android 12 : la permission « alarmes exactes » peut être
            // révoquée — le réveil deviendrait approximatif (± 10 min)
            val alarmMgr = context.getSystemService(
                android.app.AlarmManager::class.java
            )
            if (android.os.Build.VERSION.SDK_INT >= 31 &&
                alarmMgr != null && !alarmMgr.canScheduleExactAlarms()
            ) {
                Text(
                    "Les alarmes exactes sont désactivées pour PulseMix : le " +
                        "réveil pourra dériver de plusieurs minutes. Autorise-" +
                        "les dans les réglages Android.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                androidx.compose.material3.OutlinedButton(onClick = {
                    try {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings
                                    .ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                android.net.Uri.parse(
                                    "package:${context.packageName}"
                                )
                            )
                        )
                    } catch (_: Exception) {
                    }
                }) { Text("Autoriser les alarmes exactes") }
            }
        }
        if (showTimePicker) {
            val state = androidx.compose.material3.rememberTimePickerState(
                initialHour = alarmH, initialMinute = alarmM, is24Hour = true
            )
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showTimePicker = false },
                title = { Text("Heure du réveil") },
                text = { androidx.compose.material3.TimePicker(state = state) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        vm.setAlarm(
                            true, state.hour, state.minute, alarmMix, alarmRamp
                        )
                        showTimePicker = false
                    }) { Text("OK") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showTimePicker = false }
                    ) { Text("Annuler") }
                }
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("Arrière-plan", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Pour que la lecture et l'analyse ne soient pas coupées par " +
                "l'économiseur de batterie, exclus PulseMix de l'optimisation.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        androidx.compose.material3.OutlinedButton(onClick = {
            try {
                val pm = context.getSystemService(android.os.PowerManager::class.java)
                if (pm != null && pm.isIgnoringBatteryOptimizations(context.packageName)) {
                    return@OutlinedButton
                }
                context.startActivity(
                    android.content.Intent(
                        android.provider.Settings
                            .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${context.packageName}")
                    )
                )
            } catch (_: Exception) {
                try {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings
                                .ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }) { Text("Autoriser PulseMix en arrière-plan") }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // Notifications coupées (permission refusée au premier lancement ?) :
        // la notification de lecture ne peut alors jamais s'afficher.
        if (!androidx.core.app.NotificationManagerCompat.from(context)
                .areNotificationsEnabled()
        ) {
            Text("Notifications", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Les notifications de PulseMix sont désactivées : la " +
                    "notification de lecture (titre, play/pause, suivant, " +
                    "précédent) ne peut pas s'afficher. Active-les dans les " +
                    "réglages Android.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
            androidx.compose.material3.OutlinedButton(onClick = {
                try {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings
                                .ACTION_APP_NOTIFICATION_SETTINGS
                        ).putExtra(
                            android.provider.Settings.EXTRA_APP_PACKAGE,
                            context.packageName
                        )
                    )
                } catch (_: Exception) {
                }
            }) { Text("Activer les notifications") }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
        }

        Text("Égaliseur", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        EqSlider("Graves", eq.first) { vm.setEq(it, eq.second, eq.third) }
        EqSlider("Médiums", eq.second) { vm.setEq(eq.first, it, eq.third) }
        EqSlider("Aigus", eq.third) { vm.setEq(eq.first, eq.second, it) }
        Text(
            "S'applique à la lecture classique et au moteur DJ.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun EqSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label ${if (value >= 0) "+" else ""}${value.toInt()} dB",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(130.dp)
        )
        Slider(
            value = value,
            onValueChange = { onChange(it) },
            valueRange = -6f..6f,
            steps = 11
        )
    }
}
