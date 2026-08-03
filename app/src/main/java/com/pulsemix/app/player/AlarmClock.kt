package com.pulsemix.app.player

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.PowerManager
import com.pulsemix.app.MainActivity
import com.pulsemix.app.mix.MixEngine
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Réveil matin : à l'heure choisie, la musique se lance (type de mix
 * configuré dans les réglages) et le volume média monte petit à petit
 * jusqu'au maximum du téléphone.
 *
 * L'alarme utilise setAlarmClock (icône réveil dans la barre d'état,
 * fiable même en veille profonde) et se ré-arme chaque jour ainsi
 * qu'après un redémarrage du téléphone ([AlarmReceiver]).
 */
object AlarmClock {

    /** Types de mix proposés au réveil : id stable → libellé. */
    val MIX_CHOICES = listOf(
        "douce" to "Douce — réveil tout doux",
        "montee" to "Montée progressive (DJ)",
        "chill" to "Chill (DJ)",
        "flow" to "Flow continu (DJ)",
        "peak" to "Peak time (DJ) — réveil brutal",
        "shuffle" to "Aléatoire (toute la bibliothèque)"
    )

    val enabled = MutableStateFlow(false)
    val hour = MutableStateFlow(7)
    val minute = MutableStateFlow(0)
    val mixId = MutableStateFlow("douce")
    val rampMinutes = MutableStateFlow(3)

    /** Durées de répétition proposées, en minutes. */
    val SNOOZE_CHOICES = listOf(10, 15, 20)

    const val ACTION_FIRE = "com.pulsemix.app.ALARM_FIRE"
    private const val PREFS = "alarm"
    private const val REQ_FIRE = 4242
    private const val REQ_SNOOZE = 4243

    private var loaded = false
    private var rampJob: Job? = null

    fun init(context: Context) {
        if (!loaded) {
            loaded = true
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            enabled.value = p.getBoolean("enabled", false)
            hour.value = p.getInt("hour", 7)
            minute.value = p.getInt("minute", 0)
            mixId.value = p.getString("mixId", "douce") ?: "douce"
            rampMinutes.value = p.getInt("ramp", 3)
        }
        if (enabled.value) schedule(context)
    }

    /** Applique et persiste la configuration, puis (ré)arme ou annule. */
    fun configure(
        context: Context,
        en: Boolean,
        h: Int,
        m: Int,
        mix: String,
        ramp: Int
    ) {
        enabled.value = en
        hour.value = h.coerceIn(0, 23)
        minute.value = m.coerceIn(0, 59)
        mixId.value = mix
        rampMinutes.value = ramp.coerceIn(1, 15)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled.value)
            .putInt("hour", hour.value)
            .putInt("minute", minute.value)
            .putString("mixId", mixId.value)
            .putInt("ramp", rampMinutes.value)
            .apply()
        if (en) schedule(context) else cancel(context)
    }

    /** Prochain déclenchement : aujourd'hui si l'heure n'est pas passée. */
    fun nextTriggerMillis(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour.value)
            set(Calendar.MINUTE, minute.value)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    private fun firePending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQ_FIRE,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(nextTriggerMillis(), show),
                firePending(context)
            )
        } catch (_: SecurityException) {
            // Permission « alarmes exactes » révoquée : réveil approximatif
            am.setWindow(
                AlarmManager.RTC_WAKEUP, nextTriggerMillis(), 10 * 60_000L,
                firePending(context)
            )
        }
    }

    private fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(firePending(context))
        am.cancel(snoozePending(context))
    }

    private fun snoozePending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQ_SNOOZE,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * Répéter : coupe la musique et reprogramme la sonnerie dans
     * [minutes] minutes (sans toucher à l'alarme quotidienne).
     */
    fun snooze(context: Context, minutes: Int) {
        stopRinging()
        val at = System.currentTimeMillis() + minutes * 60_000L
        val am = context.getSystemService(AlarmManager::class.java)
        if (am != null) {
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                am.setAlarmClock(
                    AlarmManager.AlarmClockInfo(at, show), snoozePending(context)
                )
            } catch (_: SecurityException) {
                am.setWindow(
                    AlarmManager.RTC_WAKEUP, at, 60_000L, snoozePending(context)
                )
            }
        }
        AlarmService.stop(context)
    }

    /** Arrêter le réveil : musique coupée, notification retirée. */
    fun dismiss(context: Context) {
        stopRinging()
        context.getSystemService(AlarmManager::class.java)
            ?.cancel(snoozePending(context))
        AlarmService.stop(context)
    }

    /** Coupe la musique et la montée du volume en cours. */
    private fun stopRinging() {
        rampJob?.cancel()
        rampJob = null
        try {
            PlayerCore.stopPlayback()
        } catch (_: Exception) {
        }
    }

    /**
     * Sonnerie — appelé par [AlarmReceiver] à l'heure dite, appli
     * éventuellement fermée. Ré-arme pour demain puis délègue le
     * lancement à [AlarmService] (service en avant-plan : le processus
     * est protégé pendant tout le chargement, un broadcast seul n'est
     * garanti que ~10 s).
     */
    fun fire(context: Context, onDone: () -> Unit) {
        com.pulsemix.app.Graph.init(context)
        if (!enabled.value) {
            onDone()
            return
        }
        schedule(context) // demain, même heure
        try {
            AlarmService.start(context)
        } catch (_: Exception) {
            // Dernier recours (démarrage de service refusé) : lancement
            // direct depuis le broadcast
            launchNow(context) {}
        }
        onDone()
    }

    /**
     * Charge la bibliothèque, met le volume au plancher, lance le mix
     * configuré et laisse [startRamp] monter le son. Appelé par
     * [AlarmService] ; onDone est invoqué une fois la lecture lancée.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun launchNow(context: Context, onDone: () -> Unit) {
        // Ceinture + bretelles : le CPU reste éveillé même si le
        // service se fait arrêter avant la fin du chargement
        val wl = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pulsemix:alarm")
        try {
            wl.acquire(2 * 60_000L)
        } catch (_: Exception) {
        }

        GlobalScope.launch(Dispatchers.Main) {
            try {
                val store = com.pulsemix.app.Graph.store
                store.loaded.first { it }
                val all = store.tracks.value.filter { !it.excluded }
                if (all.isEmpty()) return@launch
                startRamp(context)
                when (val id = mixId.value) {
                    "douce" -> {
                        PlayerCore.playDouce(all, 0.35f)
                        // Aucun morceau assez doux : réveil quand même
                        if (PlayerCore.launchMessage.value != null) {
                            PlayerCore.playNormal(all.shuffled(), 0)
                        }
                    }
                    "shuffle" -> PlayerCore.playNormal(all.shuffled(), 0)
                    else -> {
                        val plan = withContext(Dispatchers.Default) {
                            MixEngine.proposeMixes(all, dj = true)
                                .find { it.id == id }
                        }
                        if (plan != null) {
                            PlayerCore.startDj(plan)
                        } else {
                            PlayerCore.playNormal(all.shuffled(), 0)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                onDone()
                try {
                    wl.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Monte le volume média d'un cran à la fois, du plancher (~1/8 du
     * max) jusqu'au maximum, sur [rampMinutes] minutes. Si l'utilisateur
     * touche au volume entre-temps, on le laisse maître et on arrête.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun startRamp(context: Context) {
        rampJob?.cancel()
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val start = (max / 8).coerceAtLeast(1)
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, start, 0)
        } catch (_: Exception) {
            return
        }
        val steps = max - start
        if (steps <= 0) return
        val stepMs = rampMinutes.value.coerceIn(1, 15) * 60_000L / steps
        rampJob = GlobalScope.launch(Dispatchers.Default) {
            var expected = start
            for (s in 1..steps) {
                delay(stepMs)
                val cur = try {
                    am.getStreamVolume(AudioManager.STREAM_MUSIC)
                } catch (_: Exception) {
                    return@launch
                }
                if (cur != expected) return@launch // volume touché à la main
                expected = start + s
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, expected, 0)
                } catch (_: Exception) {
                    return@launch
                }
            }
        }
    }
}
