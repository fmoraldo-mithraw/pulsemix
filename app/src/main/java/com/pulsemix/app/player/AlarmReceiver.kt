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
        // de la bibliothèque, au-delà du onReceive synchrone. Filet :
        // le receveur est de toute façon relâché au bout de 25 s (le
        // système ne tolère pas plus), même si le lancement n'a pas
        // rappelé.
        val result = goAsync()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish() {
            if (done.compareAndSet(false, true)) {
                try {
                    result.finish()
                } catch (_: Exception) {
                }
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, 25_000L)
        try {
            AlarmClock.fire(context) { finish() }
        } catch (e: Exception) {
            try {
                com.pulsemix.app.player.PlayerCore.engineLog(
                    "Réveil", "fire a levé ${e::class.java.simpleName} ${e.message?.take(160)}"
                )
            } catch (_: Exception) {
            }
            finish()
        }
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
