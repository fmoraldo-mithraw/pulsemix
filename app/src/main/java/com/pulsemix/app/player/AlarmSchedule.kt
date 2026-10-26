package com.pulsemix.app.player

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Un réveil de l'écran « Réveils ».
 *
 * [days] : jours de répétition (1 = lundi … 7 = dimanche, comme
 * java.time.DayOfWeek). Vide = réveil unique, désactivé après sa sonnerie.
 *
 * [source] : ce qui joue à la sonnerie — « mix:<id> » (un des
 * [AlarmClock.MIX_CHOICES]), « playlist:<nom> » ou « smart:<nom> »
 * (playlist intelligente, évaluée au moment de sonner).
 *
 * [skipAt] : instant (ms) d'une occurrence sautée — « désactiver la
 * prochaine sonnerie » d'un réveil à répétition. Une fois cet instant passé,
 * la valeur n'a plus d'effet : pas besoin de la remettre à zéro.
 */
data class Alarm(
    val id: Int,
    val hour: Int = 7,
    val minute: Int = 0,
    val days: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    val source: String = "mix:douce",
    val skipAt: Long = 0L,
    val label: String = ""
) {
    val repeating: Boolean get() = days.isNotEmpty()
}

/** Un déclenchement à venir tiré de l'agenda. [key] identifie la sonnerie
 *  (événement + occurrence + rappel) pour ne jamais la jouer deux fois. */
data class CalendarTrigger(
    val at: Long,
    val key: String,
    val title: String,
    val source: String
)

/** Une occurrence d'événement de l'agenda, telle que lue dans le
 *  fournisseur Android (CalendarContract.Instances + Reminders). */
data class CalendarInstance(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val begin: Long,
    val allDay: Boolean,
    val reminderMinutes: List<Int>
)

/** Réglage d'un agenda : sonner au début de chaque événement
 *  ([MODE_EVENT]) ou à chacune de ses notifications ([MODE_REMINDER]). */
data class CalendarConfig(
    val calendarId: Long,
    val enabled: Boolean = false,
    val mode: String = MODE_REMINDER,
    val source: String = "mix:douce"
) {
    companion object {
        const val MODE_EVENT = "event"
        const val MODE_REMINDER = "reminder"
    }
}

/**
 * Calculs PURS des réveils (testables sur la JVM) : prochaine occurrence,
 * saut d'une sonnerie, déclenchements tirés de l'agenda, sérialisation.
 */
object AlarmSchedule {

    /** Prochaine occurrence STRICTEMENT après [after], sans tenir compte
     *  de l'activation ni du saut. */
    fun occurrenceAfter(a: Alarm, after: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val start = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        for (d in 0..8L) {
            val date = start.plusDays(d)
            if (a.days.isNotEmpty() && date.dayOfWeek.value !in a.days) continue
            val t = date.atTime(a.hour, a.minute).atZone(zone).toInstant().toEpochMilli()
            if (t > after) return t
        }
        // Inatteignable (un jour de la semaine revient en 7 jours)
        return start.plusDays(1).atTime(a.hour, a.minute).atZone(zone).toInstant().toEpochMilli()
    }

    /** La prochaine sonnerie est-elle sautée ? */
    fun isSkipping(a: Alarm, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        a.repeating && a.skipAt > now && occurrenceAfter(a, now, zone) == a.skipAt

    /** Prochaine sonnerie effective, null si le réveil est désactivé. */
    fun nextFire(a: Alarm, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!a.enabled) return null
        val t = occurrenceAfter(a, now, zone)
        return if (isSkipping(a, now, zone)) occurrenceAfter(a, t, zone) else t
    }

    /**
     * Déclenchements tirés des occurrences d'agenda : au début de
     * l'événement, ou à chacun de ses rappels, selon le réglage de son
     * agenda. Ceux déjà joués ([fired]) et ceux échus depuis plus de
     * [graceMs] sont écartés. Triés par heure.
     *
     * Événements « toute la journée » : le fournisseur les date à minuit
     * UTC ; ramenés à minuit local. Ils ne sonnent qu'en mode rappels —
     * sonner à minuit pile pour « au début » n'aurait aucun sens.
     */
    fun calendarTriggers(
        instances: List<CalendarInstance>,
        configs: Map<Long, CalendarConfig>,
        now: Long,
        fired: Set<String>,
        graceMs: Long = 2 * 60_000L,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<CalendarTrigger> {
        val out = ArrayList<CalendarTrigger>()
        for (inst in instances) {
            val cfg = configs[inst.calendarId] ?: continue
            if (!cfg.enabled) continue
            val begin = if (inst.allDay) {
                Instant.ofEpochMilli(inst.begin).atZone(ZoneId.of("UTC")).toLocalDate()
                    .atStartOfDay(zone).toInstant().toEpochMilli()
            } else inst.begin
            val title = inst.title.ifBlank { "Événement" }
            when (cfg.mode) {
                CalendarConfig.MODE_EVENT -> if (!inst.allDay) {
                    out.add(CalendarTrigger(begin, "${inst.eventId}:${inst.begin}:start", title, cfg.source))
                }
                else -> for (m in inst.reminderMinutes.distinct()) {
                    out.add(
                        CalendarTrigger(
                            begin - m * 60_000L, "${inst.eventId}:${inst.begin}:r$m", title, cfg.source
                        )
                    )
                }
            }
        }
        return out.filter { it.at >= now - graceMs && it.key !in fired }.sortedBy { it.at }
    }

    // ------------------------------------------------------- sérialisation

    fun writeAlarms(list: List<Alarm>): String {
        val arr = JSONArray()
        for (a in list) {
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("hour", a.hour)
                    .put("minute", a.minute)
                    .put("days", JSONArray(a.days.sorted()))
                    .put("enabled", a.enabled)
                    .put("source", a.source)
                    .put("skipAt", a.skipAt)
                    .put("label", a.label)
            )
        }
        return arr.toString()
    }

    fun readAlarms(text: String): List<Alarm> {
        val out = ArrayList<Alarm>()
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val days = HashSet<Int>()
                val da = o.optJSONArray("days") ?: JSONArray()
                for (j in 0 until da.length()) da.optInt(j).takeIf { it in 1..7 }?.let(days::add)
                out.add(
                    Alarm(
                        id = o.optInt("id", i),
                        hour = o.optInt("hour", 7).coerceIn(0, 23),
                        minute = o.optInt("minute", 0).coerceIn(0, 59),
                        days = days,
                        enabled = o.optBoolean("enabled", true),
                        source = o.optString("source", "mix:douce").ifBlank { "mix:douce" },
                        skipAt = o.optLong("skipAt", 0L),
                        label = o.optString("label", "")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun writeCalendarConfigs(list: Collection<CalendarConfig>): String {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("calendarId", c.calendarId)
                    .put("enabled", c.enabled)
                    .put("mode", c.mode)
                    .put("source", c.source)
            )
        }
        return arr.toString()
    }

    fun readCalendarConfigs(text: String): Map<Long, CalendarConfig> {
        val out = LinkedHashMap<Long, CalendarConfig>()
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("calendarId", -1L)
                if (id < 0) continue
                out[id] = CalendarConfig(
                    calendarId = id,
                    enabled = o.optBoolean("enabled", false),
                    mode = o.optString("mode", CalendarConfig.MODE_REMINDER),
                    source = o.optString("source", "mix:douce").ifBlank { "mix:douce" }
                )
            }
        } catch (_: Exception) {
        }
        return out
    }
}
