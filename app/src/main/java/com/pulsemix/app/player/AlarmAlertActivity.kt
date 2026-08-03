package com.pulsemix.app.player

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val AlarmColors = darkColorScheme(
    primary = Color(0xFFB497FF),
    onPrimary = Color(0xFF1B1730),
    background = Color(0xFF14111F),
    surface = Color(0xFF1D1930),
    onBackground = Color(0xFFEDE9F7),
    onSurface = Color(0xFFEDE9F7)
)

/**
 * Écran plein écran du réveil, affiché par-dessus l'écran verrouillé
 * (fullScreenIntent de la notification d'alarme). Gros boutons :
 * répéter dans 10/15/20 min, ou arrêter le réveil. Tant qu'aucun des
 * deux n'est touché, l'écran et la notification restent là.
 */
class AlarmAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        setContent {
            MaterialTheme(colorScheme = AlarmColors) {
                val track by PlayerCore.currentTrack.collectAsStateWithLifecycle()
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Heure courante (après une répétition, ce n'est plus
                    // l'heure configurée du réveil)
                    val now = java.util.Calendar.getInstance()
                    Text(
                        "%02d:%02d".format(
                            now.get(java.util.Calendar.HOUR_OF_DAY),
                            now.get(java.util.Calendar.MINUTE)
                        ),
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Réveil PulseMix",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(24.dp))
                    track?.let {
                        Text(
                            it.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (it.artist.isNotBlank()) {
                            Text(
                                it.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.7f)
                            )
                        }
                    }
                    Spacer(Modifier.height(40.dp))

                    Text("Répéter dans…", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AlarmClock.SNOOZE_CHOICES.forEach { min ->
                            OutlinedButton(onClick = {
                                AlarmClock.snooze(this@AlarmAlertActivity, min)
                                finish()
                            }) { Text("$min min") }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    Button(
                        onClick = {
                            AlarmClock.dismiss(this@AlarmAlertActivity)
                            finish()
                        },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            "Arrêter le réveil",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    /** Allume l'écran et s'affiche par-dessus le verrouillage. */
    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Retour arrière ignoré : il faut choisir snooze ou arrêter. */
    @Deprecated("Retour neutralisé volontairement")
    override fun onBackPressed() {
        // rien : le réveil ne se ferme que par un bouton
    }
}
