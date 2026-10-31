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
 * Réveils musicaux : à l'heure choisie, la musique se lance (type de mix,
 * playlist ou playlist intelligente, au choix pour chaque réveil) et le
 * volume monte petit à petit jusqu'au maximum du téléphone.
 *
 * Plusieurs réveils, chacun avec ses jours de répétition (aucun = réveil
 * unique), activable à part, et dont on peut sauter la prochaine sonnerie.
 * L'agenda ([CalendarAlarms]) ajoute ses propres sonneries.
 *
 * Une seule alarme système est tenue : la plus proche de toutes. Elle
 * utilise setAlarmClock (icône réveil dans la barre d'état, fiable même en
 * veille profonde) et se ré-arme à chaque sonnerie, à chaque modification,
 * après un redémarrage du téléphone et une mise à jour ([AlarmReceiver]).
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

    /** Libellé lisible d'une source de réveil (« mix:… », « playlist:… »,
     *  « smart:… »). */
    fun sourceLabel(source: String): String = when {
        source.startsWith("playlist:") -> "Playlist « ${source.removePrefix("playlist:")} »"
        source.startsWith("smart:") -> "Playlist intelligente « ${source.removePrefix("smart:")} »"
        else -> {
            val id = source.removePrefix("mix:")
            MIX_CHOICES.firstOrNull { it.first == id }?.second ?: id
        }
    }

    /** Tous les réveils, dans l'ordre de création. */
    val alarms = MutableStateFlow<List<Alarm>>(emptyList())

    /** Durée de la montée du volume (commune à tous les réveils). */
    val rampMinutes = MutableStateFlow(3)

    /** Réveil progressif : la file du réveil réordonnée du calme (faible
     *  energyMean) vers l'énergique, quel que soit le type de mix choisi. */
    val progressive = MutableStateFlow(false)

    /** Durées de répétition proposées, en minutes. */
    val SNOOZE_CHOICES = listOf(10, 15, 20)

    const val ACTION_FIRE = "com.pulsemix.app.ALARM_FIRE"
    /** Ré-armement périodique (agenda modifié appli fermée). */
    const val ACTION_REFRESH = "com.pulsemix.app.ALARM_REFRESH"
    private const val PREFS = "alarm"
    private const val KEY_ALARMS = "alarms"
    private const val REQ_FIRE = 4242
    private const val REQ_SNOOZE = 4243
    private const val REQ_REFRESH = 4244

    // Ce qui a programmé la sonnerie, porté par l'intent de l'alarme
    private const val EXTRA_KIND = "kind"
    private const val EXTRA_ALARM_ID = "alarmId"
    private const val EXTRA_KEY = "key"
    private const val EXTRA_AT = "at"
    private const val EXTRA_SOURCE = "source"
    private const val EXTRA_LABEL = "label"
    private const val KIND_ALARM = "alarm"
    private const val KIND_CALENDAR = "calendar"
    private const val KIND_SNOOZE = "snooze"

    /** Source et titre de la sonnerie en cours (copiés en prefs : le
     *  service ou l'écran du réveil peuvent lancer la musique dans un
     *  processus tout neuf). */
    private const val KEY_RING_SOURCE = "ringSource"
    private const val KEY_RING_LABEL = "ringLabel"

    /** Titre de la sonnerie en cours (notification, écran du réveil). */
    val ringLabel = MutableStateFlow("Réveil PulseMix")
    private var ringSource = "mix:douce"

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

    /** État de l'armement, lisible dans l'écran des réveils : « Prochaine
     *  sonnerie : … », ou l'anomalie constatée. */
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
            alarms.value = if (p.contains(KEY_ALARMS)) {
                AlarmSchedule.readAlarms(p.getString(KEY_ALARMS, "[]") ?: "[]")
            } else if (p.contains("hour")) {
                // Ancien réveil unique (réglages) : devient le premier réveil
                // de la liste, quotidien comme avant.
                val legacy = Alarm(
                    id = 1,
                    hour = p.getInt("hour", 7),
                    minute = p.getInt("minute", 0),
                    days = (1..7).toSet(),
                    enabled = p.getBoolean("enabled", false),
                    source = "mix:" + (p.getString("mixId", "douce") ?: "douce")
                )
                p.edit().putString(KEY_ALARMS, AlarmSchedule.writeAlarms(listOf(legacy))).apply()
                listOf(legacy)
            } else emptyList()
            rampMinutes.value = p.getInt("ramp", 3)
            progressive.value = p.getBoolean("alarmProgressive", false)
            ringSource = p.getString(KEY_RING_SOURCE, "mix:douce") ?: "mix:douce"
            ringLabel.value = p.getString(KEY_RING_LABEL, "Réveil PulseMix") ?: "Réveil PulseMix"
            CalendarAlarms.load(context)
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
            if (scheduledFor > 0L &&
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
        CalendarAlarms.observe(context)
        schedule(context)
    }

    /** Ré-armement explicite (redémarrage, mise à jour de l'appli, agenda
     *  modifié…) : journalisé, puis même chemin que le démarrage. */
    fun rearm(context: Context, reason: String) {
        log("ré-armement demandé ($reason)")
        init(context)
    }

    /** Dernier réveil manqué constaté (pour l'écran), sinon null. */
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

    // ------------------------------------------------ édition des réveils

    private fun persistAlarms(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ALARMS, AlarmSchedule.writeAlarms(alarms.value))
            .apply()
    }

    /** Ajoute un réveil (7:00, unique, mix doux, activé) et rend son id. */
    fun addAlarm(context: Context): Int {
        val id = (alarms.value.maxOfOrNull { it.id } ?: 0) + 1
        alarms.value = alarms.value + Alarm(id = id)
        persistAlarms(context)
        schedule(context)
        return id
    }

    /** Remplace le réveil de même id, persiste et ré-arme. Une heure ou
     *  des jours changés annulent le saut de la prochaine sonnerie (elle
     *  ne désigne plus la même occurrence). */
    fun updateAlarm(context: Context, alarm: Alarm) {
        val old = alarms.value.firstOrNull { it.id == alarm.id } ?: return
        val fixed = alarm.copy(
            hour = alarm.hour.coerceIn(0, 23),
            minute = alarm.minute.coerceIn(0, 59),
            skipAt = if (old.hour != alarm.hour || old.minute != alarm.minute ||
                old.days != alarm.days
            ) 0L else alarm.skipAt
        )
        alarms.value = alarms.value.map { if (it.id == alarm.id) fixed else it }
        persistAlarms(context)
        schedule(context)
    }

    /** Réveil dicté à l'Assistant (« mets une alarme à 18h ») : réactive
     *  le réveil identique s'il existe, sinon en crée un. */
    fun setFromVoice(
        context: Context,
        hour: Int,
        minute: Int,
        days: Set<Int>,
        label: String
    ): Alarm {
        init(context)
        val (list, alarm) = AlarmSchedule.upsertVoice(alarms.value, hour, minute, days, label)
        alarms.value = list
        persistAlarms(context)
        schedule(context)
        log("réveil dicté : ${"%02d:%02d".format(alarm.hour, alarm.minute)}" +
            if (alarm.repeating) " (jours ${alarm.days.sorted()})" else "")
        return alarm
    }

    /** « Supprime l'alarme de 18h » : désactive les réveils à cette heure.
     *  Rend le nombre de réveils désactivés. */
    fun disableAt(context: Context, hour: Int, minute: Int): Int {
        init(context)
        val hit = alarms.value.filter { it.enabled && it.hour == hour && it.minute == minute }
        hit.forEach { updateAlarm(context, it.copy(enabled = false)) }
        return hit.size
    }

    /** Un réveil sonne-t-il en ce moment ? */
    val isRinging: Boolean get() = PlayerCore.alarmRinging

    /** Copie d'un réveil, insérée juste après lui ; rend l'id de la copie. */
    fun duplicateAlarm(context: Context, id: Int): Int? {
        val list = alarms.value
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return null
        val newId = (list.maxOfOrNull { it.id } ?: 0) + 1
        alarms.value = list.toMutableList().apply { add(i + 1, list[i].copy(id = newId, skipAt = 0L)) }
        persistAlarms(context)
        schedule(context)
        return newId
    }

    /** Remet un réveil supprimé à sa place (annulation de la suppression). */
    fun restoreAlarm(context: Context, alarm: Alarm, index: Int) {
        if (alarms.value.any { it.id == alarm.id }) return
        alarms.value = alarms.value.toMutableList().apply {
            add(index.coerceIn(0, size), alarm)
        }
        persistAlarms(context)
        schedule(context)
    }

    fun deleteAlarm(context: Context, id: Int) {
        alarms.value = alarms.value.filter { it.id != id }
        persistAlarms(context)
        schedule(context)
    }

    /** Désactive la prochaine sonnerie d'un réveil à répétition — ou la
     *  réactive si elle l'était déjà. */
    fun toggleSkipNext(context: Context, id: Int) {
        val a = alarms.value.firstOrNull { it.id == id } ?: return
        if (!a.repeating) return
        val now = System.currentTimeMillis()
        val skip = if (AlarmSchedule.isSkipping(a, now)) 0L
        else AlarmSchedule.occurrenceAfter(a, now)
        updateAlarm(context, a.copy(skipAt = skip))
        log(if (skip > 0L) "prochaine sonnerie sautée (${fmt(skip)})" else "prochaine sonnerie réactivée")
    }

    /** Durée de la montée du volume (1-15 min, commune à tous les réveils). */
    fun setRamp(context: Context, minutes: Int) {
        rampMinutes.value = minutes.coerceIn(1, 15)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("ramp", rampMinutes.value).apply()
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
            MixEngine.Phase(ph.name, slice, ph.mood)
        }
        return MixEngine.MixPlan(plan.id, plan.name, plan.description, phases)
    }

    // ------------------------------------------------------ programmation

    private fun alarmLabel(a: Alarm) =
        a.label.ifBlank { "Réveil %02d:%02d".format(a.hour, a.minute) }

    /** Intent de sonnerie : FLAG_UPDATE_CURRENT remplace les extras, la
     *  même PendingIntent porte toujours la prochaine sonnerie. */
    private fun firePending(context: Context, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQ_FIRE,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE).apply(extras),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun refreshPending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQ_REFRESH,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * (Ré)arme l'alarme système sur la sonnerie la plus proche : réveils
     * actifs (saut de la prochaine sonnerie compris) et agenda. Rien à
     * venir : l'alarme est annulée.
     */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val nextAlarm = alarms.value
            .mapNotNull { a -> AlarmSchedule.nextFire(a, now)?.let { a to it } }
            .minByOrNull { it.second }
        val cal = CalendarAlarms.nextTrigger(context, now)

        // Agenda : ré-armement horaire, pour voir les événements ajoutés
        // appli fermée (non réveillant : passe au prochain réveil du CPU).
        try {
            if (CalendarAlarms.active(context)) {
                am.setInexactRepeating(
                    AlarmManager.RTC, now + AlarmManager.INTERVAL_HOUR,
                    AlarmManager.INTERVAL_HOUR, refreshPending(context)
                )
            } else am.cancel(refreshPending(context))
        } catch (_: Exception) {
        }

        val useCal = cal != null && (nextAlarm == null || cal.at < nextAlarm.second)
        if (nextAlarm == null && cal == null) {
            unschedule(context)
            return
        }
        // Une sonnerie d'agenda tout juste échue (dans la marge de grâce)
        // part dans la seconde.
        val at: Long
        val description: String
        val pending = if (useCal) {
            at = maxOf(cal!!.at, now + 1_000L)
            description = "agenda « ${cal.title} »"
            firePending(context) {
                putExtra(EXTRA_KIND, KIND_CALENDAR)
                putExtra(EXTRA_KEY, cal.key)
                putExtra(EXTRA_AT, cal.at)
                putExtra(EXTRA_SOURCE, cal.source)
                putExtra(EXTRA_LABEL, cal.title)
            }
        } else {
            val (a, t) = nextAlarm!!
            at = t
            description = alarmLabel(a)
            firePending(context) {
                putExtra(EXTRA_KIND, KIND_ALARM)
                putExtra(EXTRA_ALARM_ID, a.id)
                putExtra(EXTRA_AT, t)
            }
        }
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        var exact = true
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending)
        } catch (_: SecurityException) {
            // Permission « alarmes exactes » révoquée : réveil approximatif
            exact = false
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, pending)
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
            "alarme programmée pour ${fmt(at)} — $description " +
                "(${if (exact) "exacte" else "approximative"}) ; " +
                "prochaine alarme-réveil connue du système : $nextText" +
                (if (ours) " (la nôtre)" else "")
        )
        armedInfo.value = "Prochaine sonnerie : ${fmt(at)} — $description" +
            (if (exact) "" else " (approximative : alarmes exactes refusées)") +
            (lastMissed?.let { "\n⚠ $it" } ?: "")
    }

    private fun unschedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(firePending(context))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_SCHEDULED_FOR).apply()
        armedInfo.value = ""
        log("alarme annulée (aucun réveil actif)")
    }

    private fun snoozePending(context: Context, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQ_SNOOZE,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE).apply(extras),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * Répéter : coupe la musique et reprogramme la sonnerie dans
     * [minutes] minutes (sans toucher aux réveils programmés).
     */
    fun snooze(context: Context, minutes: Int) {
        stopRinging(context)
        AlarmService.cancelNotification(context)
        val at = System.currentTimeMillis() + minutes * 60_000L
        val am = context.getSystemService(AlarmManager::class.java)
        if (am != null) {
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // La répétition rejoue la même chose (source et titre)
            val pending = snoozePending(context) {
                putExtra(EXTRA_KIND, KIND_SNOOZE)
                putExtra(EXTRA_SOURCE, ringSource)
                putExtra(EXTRA_LABEL, ringLabel.value)
            }
            try {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending)
            } catch (_: SecurityException) {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pending)
            }
        }
        AlarmService.stop(context)
    }

    /** Arrêter le réveil : musique coupée, notification retirée. */
    fun dismiss(context: Context) {
        stopRinging(context)
        AlarmService.cancelNotification(context)
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
        watchdogJob?.cancel()
        watchdogJob = null
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
     * éventuellement fermée. Identifie ce qui sonne (réveil, agenda,
     * répétition), ré-arme sur la sonnerie suivante puis lance la musique
     * (voir plus bas : notification, lancement direct, service).
     */
    fun fire(context: Context, intent: Intent, onDone: () -> Unit) {
        com.pulsemix.app.Graph.init(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_FIRED, System.currentTimeMillis()).apply()
        lastMissed = null
        val kind = intent.getStringExtra(EXTRA_KIND)
        log("alarme reçue (${kind ?: "sans détail"})")
        val source: String
        val label: String
        when (kind) {
            KIND_ALARM -> {
                val id = intent.getIntExtra(EXTRA_ALARM_ID, -1)
                val a = alarms.value.firstOrNull { it.id == id }
                if (a == null || !a.enabled) {
                    log("réveil $id supprimé ou désactivé entre-temps : rien à jouer")
                    schedule(context)
                    onDone()
                    return
                }
                // Réveil unique : il a sonné, il se désactive
                if (!a.repeating) updateAlarm(context, a.copy(enabled = false))
                source = a.source
                label = alarmLabel(a)
            }
            KIND_CALENDAR -> {
                val key = intent.getStringExtra(EXTRA_KEY) ?: ""
                CalendarAlarms.markFired(context, key, intent.getLongExtra(EXTRA_AT, 0L))
                if (!CalendarAlarms.active(context)) {
                    log("agenda désactivé entre-temps : rien à jouer")
                    schedule(context)
                    onDone()
                    return
                }
                source = intent.getStringExtra(EXTRA_SOURCE) ?: "mix:douce"
                label = intent.getStringExtra(EXTRA_LABEL) ?: "Agenda"
            }
            KIND_SNOOZE -> {
                source = intent.getStringExtra(EXTRA_SOURCE) ?: ringSource
                label = intent.getStringExtra(EXTRA_LABEL) ?: ringLabel.value
            }
            else -> {
                // Alarme posée par une version précédente (sans détail) :
                // on sonne avec le premier réveil actif, s'il y en a un.
                val a = alarms.value.firstOrNull { it.enabled }
                if (a == null) {
                    schedule(context)
                    onDone()
                    return
                }
                source = a.source
                label = alarmLabel(a)
            }
        }
        ringSource = source
        ringLabel.value = label
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RING_SOURCE, source)
            .putString(KEY_RING_LABEL, label)
            .apply()
        schedule(context) // sonnerie suivante
        log("état : ${backgroundState(context)}")
        // Journal 14 : cinq matins de suite, « alarme reçue », « alarme
        // programmée »… puis PLUS RIEN — le service de réveil en
        // avant-plan était refusé (Android 12+ interdit un service en
        // avant-plan lancé depuis l'arrière-plan ; l'appli fonctionnait le
        // premier jour, tant qu'elle venait d'être utilisée). La sonnerie
        // ne DÉPEND plus du service :
        //  1. la notification plein écran est postée directement (sans
        //     service) — sur écran verrouillé, l'écran du réveil s'ouvre,
        //     et l'appli passe au premier plan ;
        //  2. la musique est lancée directement depuis le broadcast, le
        //     receveur tenu (goAsync) jusqu'à la demande de lecture ;
        //  3. le service est demandé EN PLUS, pour protéger le processus
        //     s'il est accepté — refusé, il n'est qu'une ligne de journal.
        AlarmService.postNotification(context)
        launchOnce(context, "broadcast", onDone)
        try {
            AlarmService.start(context)
            log("service de réveil demandé (protection du processus)")
        } catch (e: Exception) {
            log("service de réveil refusé : ${e::class.java.simpleName} ${e.message?.take(160)}")
        }
    }

    /** Instant du dernier lancement (elapsedRealtime) : le broadcast, le
     *  service et l'écran du réveil peuvent tous demander le lancement —
     *  un seul compte, dans une fenêtre de 2 min. */
    @Volatile private var lastLaunchAt = 0L

    /** Lance la musique une seule fois par sonnerie, d'où que vienne la
     *  demande ([from] : journal). [onDone] est toujours rappelé. */
    fun launchOnce(context: Context, from: String, onDone: () -> Unit) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLaunchAt < 2 * 60_000L) {
            log("lancement déjà en cours ($from ignoré)")
            onDone()
            return
        }
        lastLaunchAt = now
        launchNow(context, onDone)
    }

    /** Ce que le système dit de l'appli au moment de la sonnerie : la
     *  clé des refus de services en arrière-plan (journal). */
    private fun backgroundState(context: Context): String {
        val sb = StringBuilder()
        sb.append("optimisation batterie ignorée : ${batteryIgnored(context)}")
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                val am = context.getSystemService(android.app.ActivityManager::class.java)
                sb.append(", arrière-plan restreint : ${am?.isBackgroundRestricted}")
            } catch (_: Exception) {
            }
            try {
                val usm = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
                val b = usm?.appStandbyBucket
                sb.append(
                    ", veille : " + when (b) {
                        android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
                        android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working set"
                        android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
                        android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
                        45 -> "RESTRICTED"
                        else -> "$b"
                    }
                )
            } catch (_: Exception) {
            }
        }
        return sb.toString()
    }

    /** L'écran du réveil est au premier plan (fullScreenIntent) : de là,
     *  tout est permis — si la musique n'est pas partie, on la lance. */
    fun ensureLaunched(context: Context) {
        val recent = android.os.SystemClock.elapsedRealtime() - lastLaunchAt < 2 * 60_000L
        if (recent || PlayerCore.isPlaying.value) return
        launchOnce(context, "écran du réveil") {}
    }

    /**
     * Charge la bibliothèque, met le volume au plancher, lance le mix
     * configuré et laisse [startRamp] monter le son. Appelé par
     * [AlarmService] ; onDone est invoqué une fois la lecture lancée.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun launchNow(context: Context, onDone: () -> Unit) {
        // Trace SYNCHRONE, avant tout : si rien ne suit, c'est que le
        // processus est mort avant que la coroutine ne tourne.
        log("lancement demandé depuis ${context::class.java.simpleName}")
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
                log("sonnerie « ${ringLabel.value} » : ${sourceLabel(ringSource)}, rampe ${rampMinutes.value} min")
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
                val src = ringSource
                val playlist: List<com.pulsemix.app.data.Track>? = when {
                    src.startsWith("playlist:") -> {
                        val name = src.removePrefix("playlist:")
                        kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                            com.pulsemix.app.data.PlaylistStore.loaded.first { it }
                        }
                        val byUri = all.associateBy { it.uri }
                        com.pulsemix.app.data.PlaylistStore.playlists.value
                            .firstOrNull { it.name == name }
                            ?.uris?.mapNotNull { byUri[it] }.orEmpty()
                    }
                    src.startsWith("smart:") -> {
                        val name = src.removePrefix("smart:")
                        kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                            com.pulsemix.app.data.SmartPlaylists.loaded.first { it }
                        }
                        val sp = com.pulsemix.app.data.SmartPlaylists.playlists.value
                            .firstOrNull { it.name == name }
                        if (sp == null) emptyList() else withContext(Dispatchers.Default) {
                            com.pulsemix.app.data.SmartPlaylists.evaluate(
                                sp.rule, all, System.currentTimeMillis()
                            ) { uri -> com.pulsemix.app.data.PlayHistory.lastPlayed(uri) }
                        }
                    }
                    else -> null
                }
                if (playlist != null) {
                    // Playlist supprimée, vide ou morceaux introuvables : le
                    // réveil sonne quand même, sur toute la bibliothèque.
                    if (playlist.isEmpty()) {
                        log("${sourceLabel(src)} vide ou introuvable : lecture aléatoire")
                        PlayerCore.playNormal(wakeOrder(all.shuffled()), 0)
                    } else {
                        log("${sourceLabel(src)} : ${playlist.size} morceau(x)")
                        PlayerCore.playNormal(wakeOrder(playlist), 0)
                    }
                } else when (val id = src.removePrefix("mix:")) {
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
                log("lancement demandé ($ringSource)")
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
            // Filet sonore : si rien n'a JAMAIS joué 20 s après le lancement
            // (focus audio refusé, fichier illisible, plan vide), on sonne
            // quand même. Un réveil muet est le pire des échecs pour un
            // réveil. Mais une musique qui a démarré puis que l'utilisateur
            // a coupée n'est pas un échec : l'ancien filet ne regardait que
            // « joue-t-elle à la 20e seconde ? » et lançait la sonnerie de
            // secours sur un arrêt volontaire (journal du 9 septembre : la
            // musique a démarré, arrêtée à la main, puis une sonnerie
            // inarrêtable). On surveille en continu : le premier son
            // entendu ou le premier geste désarme le filet.
            if (launched) startWatchdog(context)
        }
    }

    /** Filet sonore du lancement (voir launchNow) ; annulé par stopRinging
     *  et par tout geste sur le lecteur (noteUserGesture). */
    private var watchdogJob: Job? = null

    /** L'utilisateur a agi sur le lecteur depuis le lancement du réveil. */
    @Volatile private var userActed = false

    @OptIn(DelicateCoroutinesApi::class)
    private fun startWatchdog(context: Context) {
        watchdogJob?.cancel()
        userActed = false
        watchdogJob = GlobalScope.launch(Dispatchers.Main) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            var played = false
            while (android.os.SystemClock.elapsedRealtime() - t0 < 20_000L) {
                delay(500L)
                if (PlayerCore.isPlaying.value) {
                    played = true
                    break
                }
                if (userActed) break
            }
            val am = context.getSystemService(AudioManager::class.java)
            val vols = "volumes alarme ${streamVol(am, AudioManager.STREAM_ALARM)}, média " +
                "${streamVol(am, AudioManager.STREAM_MUSIC)}" +
                (if (am?.isMusicActive == true) ", son actif" else ", aucun son actif")
            when {
                played -> log(
                    "lecture démarrée ${(android.os.SystemClock.elapsedRealtime() - t0) / 1000} s " +
                        "après le lancement ; $vols"
                )
                userActed -> log("rien n'a joué mais l'utilisateur est intervenu : pas de sonnerie de secours")
                fallback == null -> {
                    log("rien n'a joué 20 s après le lancement ($vols) : sonnerie de secours")
                    startFallbackRingtone(context)
                }
            }
        }
    }

    /** Geste de l'utilisateur sur le lecteur : le filet sonore est désarmé
     *  (une musique coupée à la main n'est pas un réveil muet). */
    fun noteUserGesture(reason: String) {
        if (watchdogJob?.isActive == true) log("geste ($reason) : filet sonore désarmé")
        userActed = true
        watchdogJob?.cancel()
        watchdogJob = null
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
     *  casques et écouteurs filaires, USB, aide auditive, BLE (31+).
     *  Lu aussi par PlayerCore (fin du canal alarme à la connexion). */
    internal val EXTERNAL_OUTPUT_TYPES = setOf(
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
            // L'ancienne rampe s'arrêtait au PREMIER écart entre le volume
            // relevé et celui qu'elle venait de poser (« touché à la
            // main ») — or certains appareils rapportent un cran différent
            // de celui posé (Bluetooth à volume absolu, arrondis
            // constructeur) : la montée s'arrêtait au premier pas, sans
            // trace, et le réveil restait à 3/15 (« le son ne monte pas
            // assez haut »). Désormais : seule une BAISSE nette à la main
            // (deux crans sous ce qu'on a posé) arrête la montée ; une
            // hausse à la main est adoptée et la montée continue au-dessus ;
            // un écart d'un cran est ignoré. Tout est journalisé.
            var posed = start
            val t0 = android.os.SystemClock.elapsedRealtime()
            for (s in 1..steps) {
                delay(stepMs)
                val cur = try {
                    am.getStreamVolume(wakeStream)
                } catch (e: Exception) {
                    log("rampe : volume illisible (${e.message}), arrêt à $posed/$max")
                    return@launch
                }
                if (cur < posed - 1) {
                    log("rampe : volume baissé à la main ($posed -> $cur/$max), arrêt")
                    return@launch
                }
                val next = (maxOf(cur, posed) + 1).coerceAtMost(max)
                try {
                    am.setStreamVolume(wakeStream, next, 0)
                } catch (e: Exception) {
                    log("rampe : réglage impossible (${e.message}), arrêt à $posed/$max")
                    return@launch
                }
                posed = next
                if (posed >= max) break
            }
            val finalVol = try {
                am.getStreamVolume(wakeStream)
            } catch (_: Exception) {
                -1
            }
            log(
                "rampe terminée en ${(android.os.SystemClock.elapsedRealtime() - t0) / 1000} s : " +
                    "posé $posed/$max, relevé $finalVol/$max"
            )
        }
    }
}
