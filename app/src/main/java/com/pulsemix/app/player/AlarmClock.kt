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

    /** Réveil progressif : la file du réveil réordonnée du calme (faible
     *  energyMean) vers l'énergique, quel que soit le type de mix choisi. */
    val progressive = MutableStateFlow(false)

    /** Durées de répétition proposées, en minutes. */
    val SNOOZE_CHOICES = listOf(10, 15, 20)

    const val ACTION_FIRE = "com.pulsemix.app.ALARM_FIRE"
    private const val PREFS = "alarm"
    private const val REQ_FIRE = 4242
    private const val REQ_SNOOZE = 4243

    /** Volume média d'avant le réveil, copié en prefs (voir restoreVolume). */
    private const val KEY_VOLUME_BEFORE = "volumeBeforeAlarm"

    /** Canal (stream) dont le volume a été touché, copié en prefs avec
     *  KEY_VOLUME_BEFORE : à rendre sur le même canal après la mort du
     *  processus. */
    private const val KEY_WAKE_STREAM = "wakeStream"

    /** Fin automatique du canal alarme après la rampe : au-delà, l'utilisateur
     *  est réveillé, la musique redevient du média (voir chooseChannel). */
    private const val ALARM_CHANNEL_GRACE_MS = 30 * 60_000L

    /** Retour automatique au canal média (voir launchNow). */
    private var channelJob: Job? = null

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
            progressive.value = p.getBoolean("alarmProgressive", false)
            // Un réveil interrompu par la mort du processus n'a jamais rendu
            // le volume : la clé traîne encore dans les prefs. On le rend
            // maintenant — sinon la première vidéo ou le premier morceau
            // après le redémarrage partait à fond. Sans risque de conflit :
            // ce bloc ne tourne qu'une fois par processus, toujours avant
            // que startRamp n'écrive la clé du réveil en cours.
            if (p.contains(KEY_VOLUME_BEFORE)) restoreVolume(context)
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

    /** Active/désactive le réveil progressif (réglage indépendant du reste :
     *  pas besoin de ré-armer l'alarme, seul l'ordre de la file change). */
    fun setProgressive(context: Context, en: Boolean) {
        progressive.value = en
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("alarmProgressive", en)
            .apply()
    }

    /** File du réveil : du calme vers l'énergique si le progressif est actif. */
    private fun wakeOrder(list: List<com.pulsemix.app.data.Track>) =
        if (progressive.value) list.sortedBy { it.energyMean } else list

    /**
     * Même chose pour un plan DJ : les morceaux sont réordonnés par énergie
     * croissante, redécoupés aux tailles des phases d'origine — le moteur DJ
     * garde ainsi une structure de phases valide.
     */
    private fun wakePlan(plan: MixEngine.MixPlan): MixEngine.MixPlan {
        if (!progressive.value) return plan
        val ordered = plan.phases.flatMap { it.tracks }.sortedBy { it.energyMean }
        var i = 0
        val phases = plan.phases.map { ph ->
            val end = (i + ph.tracks.size).coerceAtMost(ordered.size)
            val slice = ordered.subList(i, end).toList()
            i = end
            MixEngine.Phase(ph.name, slice)
        }
        return MixEngine.MixPlan(plan.id, plan.name, plan.description, phases)
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
        stopRinging(context)
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
        stopRinging(context)
        context.getSystemService(AlarmManager::class.java)
            ?.cancel(snoozePending(context))
        AlarmService.stop(context)
    }

    /** Coupe la musique, la sonnerie de secours et la montée du volume. */
    private fun stopRinging(context: Context) {
        rampJob?.cancel()
        rampJob = null
        channelJob?.cancel()
        channelJob = null
        stopFallbackRingtone()
        try {
            PlayerCore.stopPlayback()
            // Retour au canal média : le réveil est fini
            PlayerCore.setAlarmAudio(false)
        } catch (_: Exception) {
        }
        restoreVolume(context)
        log("réveil arrêté")
    }

    // ------------------------------------------------- volume média rendu

    /** Volume média d'avant le réveil, à rendre une fois celui-ci coupé. */
    private var volumeBeforeAlarm: Int? = null

    /**
     * Le réveil pousse le volume média jusqu'au maximum du téléphone. Sans
     * cette remise en état, la première vidéo ou le premier morceau joué
     * après l'avoir coupé partait à fond.
     *
     * La variable ne survit pas à la mort du processus (fréquente entre le
     * lever du réveil et son arrêt : l'app est en arrière-plan) : la copie
     * en prefs, écrite au moment de pousser le volume, fait foi quand la
     * variable a été perdue. La clé est effacée une fois le volume rendu.
     */
    private fun restoreVolume(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = volumeBeforeAlarm
            ?: p.getInt(KEY_VOLUME_BEFORE, -1).takeIf { it >= 0 }
        val stream = p.getInt(KEY_WAKE_STREAM, wakeStream)
        volumeBeforeAlarm = null
        p.edit().remove(KEY_VOLUME_BEFORE).remove(KEY_WAKE_STREAM).apply()
        if (v == null) return
        try {
            context.getSystemService(AudioManager::class.java)
                ?.setStreamVolume(stream, v, 0)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------ sonnerie de secours

    private var fallback: android.media.Ringtone? = null

    /**
     * Bibliothèque vide, dossier devenu illisible, fichiers introuvables :
     * le réveil restait muet, précisément dans le cas où l'on compte le
     * plus dessus. On sonne alors avec l'alarme du système.
     */
    private fun startFallbackRingtone(context: Context) {
        try {
            val uri = android.media.RingtoneManager.getActualDefaultRingtoneUri(
                context, android.media.RingtoneManager.TYPE_ALARM
            ) ?: android.media.RingtoneManager.getActualDefaultRingtoneUri(
                context, android.media.RingtoneManager.TYPE_RINGTONE
            ) ?: return
            val r = android.media.RingtoneManager.getRingtone(context, uri) ?: return
            // Sur le canal « alarme » et non « média » : cette sonnerie de
            // secours ne doit dépendre ni de la montée progressive ni du
            // volume média, qui peut être au minimum.
            r.audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (android.os.Build.VERSION.SDK_INT >= 28) r.isLooping = true
            r.play()
            fallback = r
        } catch (_: Exception) {
        }
    }

    private fun stopFallbackRingtone() {
        try {
            fallback?.stop()
        } catch (_: Exception) {
        }
        fallback = null
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
            var launched = false
            try {
                log("sonnerie : mix « ${mixId.value} », rampe ${rampMinutes.value} min")
                val store = com.pulsemix.app.Graph.store
                // Bibliothèque pas lue au bout de 30 s (disque lent, fichier
                // énorme) : on ne reste pas muet, la sonnerie de secours part.
                val loaded = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                    store.loaded.first { it }
                }
                if (loaded == null) {
                    log("bibliothèque pas chargée à temps : sonnerie de secours")
                    startFallbackRingtone(context)
                    return@launch
                }
                val all = store.tracks.value.filter { !it.excluded }
                log("bibliothèque chargée : ${all.size} morceau(x) jouable(s)")
                // La musique sort sur le canal ALARME pendant le réveil : il
                // ne dépend pas du volume média (souvent à zéro au coucher)
                // et passe à travers « ne pas déranger » / heure du coucher,
                // qui peuvent couper le canal média. Posé AVANT le lancement,
                // rendu au canal média au premier geste sur le lecteur, à
                // l'arrêt du réveil, quand l'utilisateur lance autre chose
                // (PlayerCore), ou après la rampe + 30 min. SAUF sortie
                // externe branchée (Bluetooth, casque, USB) : Android
                // diffuse le canal alarme sur le haut-parleur EN PLUS de la
                // sortie externe — on reste alors sur le canal média, dont
                // la rampe pousse le volume.
                val useAlarmChannel = chooseChannel(context)
                PlayerCore.alarmLaunching = true
                PlayerCore.setAlarmAudio(useAlarmChannel)
                startRamp(context)
                channelJob?.cancel()
                if (useAlarmChannel) {
                    channelJob = GlobalScope.launch(Dispatchers.Main) {
                        delay(rampMinutes.value.coerceIn(1, 15) * 60_000L + ALARM_CHANNEL_GRACE_MS)
                        log("rampe + 30 min : canal média rendu")
                        PlayerCore.setAlarmAudio(false)
                    }
                }
                if (all.isEmpty()) {
                    log("bibliothèque vide : sonnerie de secours")
                    startFallbackRingtone(context)
                    return@launch
                }
                when (val id = mixId.value) {
                    "douce" -> {
                        // La douce va déjà du plus doux au moins doux : le
                        // réveil progressif n'y changerait rien.
                        PlayerCore.playDouce(all, 0.35f)
                        // Aucun morceau assez doux : réveil quand même
                        if (PlayerCore.launchMessage.value != null) {
                            log("aucun morceau doux : lecture aléatoire")
                            PlayerCore.playNormal(wakeOrder(all.shuffled()), 0)
                        }
                    }
                    "shuffle" -> PlayerCore.playNormal(wakeOrder(all.shuffled()), 0)
                    else -> {
                        val plan = withContext(Dispatchers.Default) {
                            MixEngine.proposeMixes(all, dj = true)
                                .find { it.id == id }
                        }
                        if (plan != null) {
                            PlayerCore.startDj(wakePlan(plan))
                            // Le réveil ne doit pas s'arrêter au bout du set
                            PlayerCore.setMixSpec(
                                PlayerCore.MixSpec(plan.id, true, null, null)
                            )
                        } else {
                            log("plan « $id » introuvable : lecture aléatoire")
                            PlayerCore.playNormal(wakeOrder(all.shuffled()), 0)
                        }
                    }
                }
                launched = true
                log("lancement demandé (${mixId.value})")
            } catch (e: Exception) {
                // Une exception ici laissait la notification affichée et
                // le téléphone MUET, sans une trace : journalisée, et la
                // sonnerie de secours prend le relais.
                log("échec du lancement : ${e::class.java.simpleName} ${e.message}")
                startFallbackRingtone(context)
            } finally {
                PlayerCore.alarmLaunching = false
                onDone()
                try {
                    wl.release()
                } catch (_: Exception) {
                }
            }
            // Filet sonore : si rien ne joue 20 s après le lancement (focus
            // audio refusé, fichier illisible, plan vide), on sonne quand
            // même. Un réveil muet est le pire des échecs pour un réveil.
            if (launched) {
                delay(20_000L)
                if (!PlayerCore.isPlaying.value && fallback == null) {
                    log("rien ne joue 20 s après le lancement : sonnerie de secours")
                    startFallbackRingtone(context)
                } else if (PlayerCore.isPlaying.value) {
                    log("lecture en cours 20 s après le lancement")
                }
            }
        }
    }

    /** Journal du réveil (service_log.txt, tag [Réveil]) : chaque étape
     *  laisse une trace, pour ne plus jamais chercher pourquoi il est resté
     *  muet. */
    private fun log(message: String) {
        try {
            PlayerCore.engineLog("Réveil", message)
        } catch (_: Exception) {
        }
    }

    /**
     * Monte le volume média d'un cran à la fois, du plancher (~1/8 du
     * max) jusqu'au maximum, sur [rampMinutes] minutes. Si l'utilisateur
     * touche au volume entre-temps, on le laisse maître et on arrête.
     */
    /** Canal sonore du réveil : ALARME (voir launchNow) — ou MÉDIA quand une
     *  sortie externe est branchée (voir chooseChannel). */
    private var wakeStream = AudioManager.STREAM_ALARM

    /**
     * Choisit le canal du réveil : alarme par défaut ; média si une sortie
     * externe est branchée (Bluetooth, casque filaire, USB, aide
     * auditive) — sur le canal alarme, Android l'enverrait AUSSI sur le
     * haut-parleur du téléphone, les deux jouant en même temps. Vrai =
     * canal alarme.
     */
    private fun chooseChannel(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java)
        val external = try {
            am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.firstOrNull { it.type in EXTERNAL_OUTPUT_TYPES }
        } catch (_: Exception) {
            null
        }
        wakeStream = if (external != null) AudioManager.STREAM_MUSIC
        else AudioManager.STREAM_ALARM
        if (external != null) {
            log(
                "sortie externe branchée (${external.productName}, type " +
                    "${external.type}) : canal média, pas alarme"
            )
        }
        return external == null
    }

    /** Types AudioDeviceInfo d'une sortie externe : Bluetooth A2DP/SCO,
     *  casques et écouteurs filaires, USB, aide auditive, BLE (31+). */
    private val EXTERNAL_OUTPUT_TYPES = setOf(
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
        android.media.AudioDeviceInfo.TYPE_USB_ACCESSORY,
        android.media.AudioDeviceInfo.TYPE_HEARING_AID,
        26, // TYPE_BLE_HEADSET
        27, // TYPE_BLE_SPEAKER
        30 // TYPE_BLE_BROADCAST
    )

    @OptIn(DelicateCoroutinesApi::class)
    private fun startRamp(context: Context) {
        rampJob?.cancel()
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val max = am.getStreamMaxVolume(wakeStream)
        // Plancher à un quart du maximum (et jamais sous 2 crans) : à un
        // huitième, sur les 15 crans habituels, le réveil partait au cran
        // 1 — inaudible depuis la table de nuit, vécu comme « aucune
        // musique ». La rampe monte ensuite jusqu'au maximum.
        val start = (max / 4).coerceAtLeast(2).coerceAtMost(max)
        try {
            // Mémorisé avant d'y toucher : le réveil rendra ce volume.
            // Copié en prefs dans la foulée : si le processus meurt avant
            // stopRinging, la variable disparaît avec lui et c'est la copie
            // qui permettra de rendre le volume (init / restoreVolume).
            if (volumeBeforeAlarm == null) {
                val before = am.getStreamVolume(wakeStream)
                volumeBeforeAlarm = before
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_VOLUME_BEFORE, before)
                    .putInt(KEY_WAKE_STREAM, wakeStream).apply()
            }
            am.setStreamVolume(wakeStream, start, 0)
            log(
                "volume ${if (wakeStream == AudioManager.STREAM_ALARM) "alarme" else "média"} : " +
                    "$start/$max, montée vers $max"
            )
        } catch (e: Exception) {
            log("volume impossible à régler : ${e.message}")
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
                    am.getStreamVolume(wakeStream)
                } catch (_: Exception) {
                    return@launch
                }
                if (cur != expected) return@launch // volume touché à la main
                expected = start + s
                try {
                    am.setStreamVolume(wakeStream, expected, 0)
                } catch (_: Exception) {
                    return@launch
                }
            }
        }
    }
}
