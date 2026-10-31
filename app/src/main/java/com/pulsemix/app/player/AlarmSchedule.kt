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
 *  ([MODE_EVENT]), à chacune de ses notifications ([MODE_REMINDER]), ou
 *  les deux ([MODE_BOTH]). */
data class CalendarConfig(
    val calendarId: Long,
    val enabled: Boolean = false,
    val mode: String = MODE_REMINDER,
    val source: String = "mix:douce"
) {
    companion object {
        const val MODE_EVENT = "event"
        const val MODE_REMINDER = "reminder"
        const val MODE_BOTH = "both"
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
     * l'événement, à chacun de ses rappels, ou les deux, selon le réglage
     * de son agenda. Ceux déjà joués ([fired]) et ceux échus depuis plus de
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
            val atStart = cfg.mode == CalendarConfig.MODE_EVENT || cfg.mode == CalendarConfig.MODE_BOTH
            val atReminders = cfg.mode != CalendarConfig.MODE_EVENT
            val startRings = atStart && !inst.allDay
            if (startRings) {
                out.add(CalendarTrigger(begin, "${inst.eventId}:${inst.begin}:start", title, cfg.source))
            }
            if (atReminders) {
                for (m in inst.reminderMinutes.distinct()) {
                    // Rappel « à l'heure de l'événement » : le début sonne déjà
                    if (m == 0 && startRings) continue
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

    // --------------------------------------- réveils dictés (Assistant)

    /** Jours de l'intent SET_ALARM (java.util.Calendar : 1 = dimanche …
     *  7 = samedi) → jours d'[Alarm] (1 = lundi … 7 = dimanche). */
    fun daysFromCalendar(days: Collection<Int>): Set<Int> =
        days.filter { it in 1..7 }.map { if (it == 1) 7 else it - 1 }.toSet()

    /**
     * « Mets une alarme à 18h » : réactive un réveil existant à la même
     * heure et aux mêmes jours plutôt que d'en empiler un doublon ; sinon
     * en ajoute un. Rend la nouvelle liste et le réveil concerné.
     */
    fun upsertVoice(
        list: List<Alarm>,
        hour: Int,
        minute: Int,
        days: Set<Int>,
        label: String
    ): Pair<List<Alarm>, Alarm> {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val same = list.firstOrNull { it.hour == h && it.minute == m && it.days == days }
        if (same != null) {
            val updated = same.copy(
                enabled = true,
                skipAt = 0L,
                label = label.ifBlank { same.label }
            )
            return list.map { if (it.id == same.id) updated else it } to updated
        }
        val id = (list.maxOfOrNull { it.id } ?: 0) + 1
        val added = Alarm(id = id, hour = h, minute = m, days = days, label = label)
        return (list + added) to added
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
