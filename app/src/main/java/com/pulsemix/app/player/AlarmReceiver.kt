package com.pulsemix.app.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Déclenche le réveil matin ([AlarmClock.fire]) à l'heure programmée, et
 * ré-arme l'alarme après un redémarrage du téléphone (BOOT_COMPLETED).
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmClock.ACTION_FIRE -> {
                // goAsync : la lecture démarre après un chargement asynchrone
                // de la bibliothèque, au-delà du onReceive synchrone
                val result = goAsync()
                AlarmClock.fire(context) { result.finish() }
            }
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                AlarmClock.init(context)
            }
        }
    }
}
