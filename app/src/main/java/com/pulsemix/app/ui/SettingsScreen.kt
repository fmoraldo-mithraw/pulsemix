package com.pulsemix.app.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulsemix.app.PlayerViewModel

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

        SettingSwitch(
            title = "Normaliser le volume",
            subtitle = "Atténue les morceaux masterisés fort pour un niveau " +
                "homogène entre les morceaux et dans les transitions DJ.",
            checked = normalize,
            onChange = { vm.setNormalizeVolume(it) }
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

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
