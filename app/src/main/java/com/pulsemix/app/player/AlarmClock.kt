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

    /** Heure (ms) pour laquelle l'alarme a été programmée en dernier, et
     *  heure de la dernière sonnerie reçue : un réveil programmé, dépassé
     *  et jamais reçu est un réveil MANQUÉ (voir init). */
    private const val KEY_SCHEDULED_FOR = "scheduledFor"
    private const val KEY_LAST_FIRED = "lastFiredAt"

    /** État de l'armement, lisible dans les réglages : « Prochaine
     *  sonnerie : … (exacte) », ou l'anomalie constatée. */
    val armedInfo = MutableStateFlow("")

    /** Vrai si le système autorise les alarmes exactes (API 31+ ; toujours
     *  vrai avant). Sans elles, le réveil peut sonner avec dix minutes de
     *  retard, ou pas du tout en veille profonde. */
    fun exactAlarmsAllowed(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 31) return true
        return try {
            context.getSystemService(AlarmManager::class.java)
                ?.canScheduleExactAlarms() == true
        } catch (_: Exception) {
            false
        }
    }

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
            // Réveil MANQUÉ ? Programmé, heure dépassée, jamais reçu : c'est
            // l'anomalie « le réveil ne marche plus » — journalisée avec ce
            // que le système sait, pour ne plus la chercher à l'aveugle.
            val scheduledFor = p.getLong(KEY_SCHEDULED_FOR, 0L)
            val lastFired = p.getLong(KEY_LAST_FIRED, 0L)
            if (enabled.value && scheduledFor > 0L &&
                System.currentTimeMillis() > scheduledFor + 2 * 60_000L &&
                lastFired < scheduledFor
            ) {
                val msg = "réveil MANQUÉ : programmé pour ${fmt(scheduledFor)}, " +
                    "jamais reçu (dernière sonnerie : " +
                    (if (lastFired > 0L) fmt(lastFired) else "aucune") +
                    ", alarmes exactes : ${exactAlarmsAllowed(context)}, " +
                    "optimisation batterie ignorée : ${batteryIgnored(context)})"
                log(msg)
                lastMissed = msg
            }
        }
        if (enabled.value) schedule(context)
    }

    /** Ré-armement explicite (redémarrage, mise à jour de l'appli) :
     *  journalisé, puis même chemin que le démarrage. */
    fun rearm(context: Context, reason: String) {
        log("ré-armement demandé ($reason)")
        init(context)
    }

    /** Dernier réveil manqué constaté (pour les réglages), sinon null. */
    @Volatile private var lastMissed: String? = null

    private fun batteryIgnored(context: Context): Boolean = try {
        context.getSystemService(android.os.PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    } catch (_: Exception) {
        false
    }

    private fun fmt(ms: Long): String =
        java.text.SimpleDateFormat("EEE dd/MM HH:mm", java.util.Locale.FRANCE)
            .format(java.util.Date(ms))

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
        val at = nextTriggerMillis()
        var exact = true
        try {
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(at, show),
                firePending(context)
            )
        } catch (_: SecurityException) {
            // Permission « alarmes exactes » révoquée : réveil approximatif
            exact = false
            am.setWindow(
                AlarmManager.RTC_WAKEUP, at, 10 * 60_000L,
                firePending(context)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SCHEDULED_FOR, at).apply()
        // Vérification auprès du système : la prochaine alarme-réveil qu'il
        // connaît est-elle la nôtre ? (getNextAlarmClock couvre toutes les
        // applis ; si une autre sonne avant, on ne peut rien conclure.)
        val next = try {
            am.nextAlarmClock
        } catch (_: Exception) {
            null
        }
        val nextText = next?.let {
            "${fmt(it.triggerTime)} par ${it.showIntent?.creatorPackage ?: "?"}"
        } ?: "aucune"
        val ours = next != null && next.showIntent?.creatorPackage == context.packageName &&
            kotlin.math.abs(next.triggerTime - at) < 60_000L
        log(
            "alarme programmée pour ${fmt(at)} (${if (exact) "exacte" else "approximative"}) ; " +
                "prochaine alarme-réveil connue du système : $nextText" +
                (if (ours) " (la nôtre)" else "")
        )
        armedInfo.value = "Prochaine sonnerie : ${fmt(at)}" +
            (if (exact) "" else " (approximative : alarmes exactes refusées)") +
            (lastMissed?.let { "\n⚠ $it" } ?: "")
    }

    private fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(firePending(context))
        am.cancel(snoozePending(context))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_SCHEDULED_FOR).apply()
        armedInfo.value = ""
        log("alarme annulée (réveil désactivé)")
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

    /**
     * Sonnerie de secours : un MediaPlayer À NOUS, jamais un Ringtone. Le
     * Ringtone système peut déléguer la lecture au lecteur distant de
     * SystemUI : une sonnerie en boucle lancée là-bas survivait à la mort
     * de notre processus et devenait INARRÊTABLE (journal du 9 septembre :
     * « une alarme tourne en boucle et je ne peux pas l'arrêter »). Avec un
     * MediaPlayer local, elle meurt avec l'appli, on la coupe nous-mêmes,
     * une seule instance à la fois, et jamais plus de [FALLBACK_MAX_MS].
     */
    private var fallback: android.media.MediaPlayer? = null
    private var fallbackJob: Job? = null

    /** Durée maximale de la sonnerie de secours (5 min) : un réveil, pas
     *  une sirène sans fin. */
    private const val FALLBACK_MAX_MS = 5 * 60_000L

    /**
     * Bibliothèque vide, dossier devenu illisible, fichiers introuvables :
     * le réveil restait muet, précisément dans le cas où l'on compte le
     * plus dessus. On sonne alors avec l'alarme du système.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun startFallbackRingtone(context: Context) {
        if (fallback != null) {
            log("sonnerie de secours déjà en cours")
            return
        }
        val candidates = listOfNotNull(
            android.media.RingtoneManager.getActualDefaultRingtoneUri(
                context, android.media.RingtoneManager.TYPE_ALARM
            ),
            android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI,
            android.media.RingtoneManager.getActualDefaultRingtoneUri(
                context, android.media.RingtoneManager.TYPE_RINGTONE
            )
        )
        for (uri in candidates) {
            val mp = android.media.MediaPlayer()
            try {
                // Sur le canal « alarme » et non « média » : cette sonnerie
                // de secours ne doit dépendre ni de la montée progressive
                // ni du volume média, qui peut être au minimum.
                mp.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setDataSource(context, uri)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                fallback = mp
                log("sonnerie de secours démarrée ($uri), ${FALLBACK_MAX_MS / 60_000} min au plus")
                fallbackJob?.cancel()
                fallbackJob = GlobalScope.launch(Dispatchers.Main) {
                    delay(FALLBACK_MAX_MS)
                    if (fallback === mp) {
                        log("sonnerie de secours : durée maximale atteinte, arrêt")
                        stopFallbackRingtone()
                    }
                }
                return
            } catch (e: Exception) {
                log("sonnerie de secours impossible sur $uri : ${e.message}")
                try {
                    mp.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun stopFallbackRingtone() {
        fallbackJob?.cancel()
        fallbackJob = null
        val mp = fallback ?: return
        fallback = null
        try {
            mp.stop()
        } catch (_: Exception) {
        }
        try {
            mp.release()
        } catch (_: Exception) {
        }
        log("sonnerie de secours arrêtée")
    }

    /** Coupe la sonnerie de secours si elle tourne — tout geste sur le
     *  lecteur (pause, suivant, arrêt…) doit y suffire. */
    fun stopFallbackIfRinging(reason: String): Boolean {
        if (fallback == null) return false
        log("sonnerie de secours coupée ($reason)")
        stopFallbackRingtone()
        return true
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_FIRED, System.currentTimeMillis()).apply()
        lastMissed = null
        log("alarme reçue (réveil ${if (enabled.value) "actif" else "désactivé"})")
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
                // (PlayerCore), ou après la rampe + 30 min. Sortie externe
                // branchée (Bluetooth, casque) : canal alarme QUAND MÊME —
                // Android le diffuse sur le haut-parleur en plus de la
                // sortie externe, et c'est voulu : un réveil parti vers des
                // écouteurs restés connectés la nuit laissait le téléphone
                // muet (journal du 6 septembre).
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
                    val am = context.getSystemService(AudioManager::class.java)
                    log(
                        "lecture en cours 20 s après le lancement ; volumes alarme " +
                            "${streamVol(am, AudioManager.STREAM_ALARM)}, média " +
                            "${streamVol(am, AudioManager.STREAM_MUSIC)}" +
                            (if (am?.isMusicActive == true) ", son actif" else ", AUCUN son actif")
                    )
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
     * Canal du réveil : TOUJOURS l'alarme (vrai), et un relevé des sorties
     * audio et des volumes dans le journal — c'est ce qui permet de
     * comprendre un réveil muet.
     */
    private fun chooseChannel(context: Context): Boolean {
        val am = context.getSystemService(AudioManager::class.java)
        val outputs = try {
            am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().toList()
        } catch (_: Exception) {
            emptyList()
        }
        val external = outputs.filter { it.type in EXTERNAL_OUTPUT_TYPES }
        // TOUJOURS le canal alarme. La version précédente passait au canal
        // média dès qu'une sortie externe était connectée, pour éviter la
        // double diffusion haut-parleur + Bluetooth ; résultat : des
        // écouteurs ou une enceinte Bluetooth restés connectés la nuit
        // recevaient tout le réveil, et le téléphone restait MUET (« l'alarme
        // se déclenche mais ne produit aucun son »). Un réveil doit être
        // entendu : le canal alarme sort sur le haut-parleur (et sur la
        // sortie externe en plus, le temps du réveil) ; le canal média
        // revient au premier geste ou rampe + 30 min après.
        wakeStream = AudioManager.STREAM_ALARM
        log(
            "sorties audio : " + outputs.joinToString(", ") { "${it.productName} (type ${it.type})" }
                .ifBlank { "aucune" } +
                (if (external.isNotEmpty()) " ; externe(s) connectée(s) : " +
                    external.joinToString(", ") { "${it.productName}" } +
                    " — canal alarme quand même (haut-parleur + externe)" else "") +
                " ; volumes alarme ${streamVol(am, AudioManager.STREAM_ALARM)}, " +
                "média ${streamVol(am, AudioManager.STREAM_MUSIC)}"
        )
        return true
    }

    /** « v/max » d'un canal, pour le journal. */
    private fun streamVol(am: AudioManager?, stream: Int): String = try {
        "${am?.getStreamVolume(stream)}/${am?.getStreamMaxVolume(stream)}"
    } catch (_: Exception) {
        "?"
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
