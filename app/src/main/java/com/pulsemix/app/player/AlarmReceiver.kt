package com.pulsemix.app.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Déclenche le réveil matin ([AlarmClock.fire]) à l'heure programmée.
 *
 * Ce receveur n'est PAS exporté : l'alarme lui parvient par un
 * PendingIntent, que le système délivre au nom de l'application. Le rendre
 * public laisserait n'importe quelle autre appli du téléphone déclencher le
 * réveil en émettant l'action à la main. Le ré-armement après redémarrage,
 * lui, exige d'être exporté : il vit dans [AlarmBootReceiver].
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmClock.ACTION_FIRE) return
        // goAsync : la lecture démarre après un chargement asynchrone
        // de la bibliothèque, au-delà du onReceive synchrone
        val result = goAsync()
        AlarmClock.fire(context) { result.finish() }
    }
}

/**
 * Ré-arme l'alarme quotidienne après un redémarrage du téléphone — et après
 * une MISE À JOUR de l'appli : selon les constructeurs, les alarmes d'une
 * appli réinstallée par-dessus ne survivent pas, et l'utilisateur qui
 * installe une version par jour se retrouvait sans réveil tant qu'il
 * n'avait pas rouvert l'appli.
 */
class AlarmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                com.pulsemix.app.Graph.init(context)
                AlarmClock.rearm(context, intent.action ?: "?")
            }
        }
    }
}
