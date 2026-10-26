package com.pulsemix.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Calculs purs des réveils : occurrences, saut, agenda, sérialisation. */
class AlarmScheduleTest {

    private val zone = ZoneId.of("Europe/Paris")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // 2026-10-26 est un lundi
    private val mondayNoon = ms(2026, 10, 26, 12, 0)

    @Test
    fun oneShotRingsTodayOrTomorrow() {
        val a = Alarm(id = 1, hour = 18, minute = 30)
        assertEquals(ms(2026, 10, 26, 18, 30), AlarmSchedule.nextFire(a, mondayNoon, zone))
        val b = Alarm(id = 2, hour = 7, minute = 0)
        assertEquals(ms(2026, 10, 27, 7, 0), AlarmSchedule.nextFire(b, mondayNoon, zone))
    }

    @Test
    fun repeatingPicksNextSelectedDay() {
        // Samedi et dimanche seulement
        val a = Alarm(id = 1, hour = 9, minute = 0, days = setOf(6, 7))
        assertEquals(ms(2026, 10, 31, 9, 0), AlarmSchedule.nextFire(a, mondayNoon, zone))
        // Lundi : déjà passé à midi → lundi suivant
        val b = Alarm(id = 2, hour = 7, minute = 0, days = setOf(1))
        assertEquals(ms(2026, 11, 2, 7, 0), AlarmSchedule.nextFire(b, mondayNoon, zone))
    }

    @Test
    fun disabledNeverRings() {
        assertNull(AlarmSchedule.nextFire(Alarm(id = 1, enabled = false), mondayNoon, zone))
    }

    @Test
    fun skipNextJumpsOneOccurrenceOnly() {
        val a = Alarm(id = 1, hour = 7, minute = 0, days = (1..5).toSet())
        val first = AlarmSchedule.occurrenceAfter(a, mondayNoon, zone)
        assertEquals(ms(2026, 10, 27, 7, 0), first)
        val skipped = a.copy(skipAt = first)
        assertTrue(AlarmSchedule.isSkipping(skipped, mondayNoon, zone))
        assertEquals(ms(2026, 10, 28, 7, 0), AlarmSchedule.nextFire(skipped, mondayNoon, zone))
        // Une fois l'occurrence sautée passée, le saut n'a plus d'effet
        val after = ms(2026, 10, 27, 8, 0)
        assertFalse(AlarmSchedule.isSkipping(skipped, after, zone))
        assertEquals(ms(2026, 10, 28, 7, 0), AlarmSchedule.nextFire(skipped, after, zone))
    }

    @Test
    fun calendarTriggersByMode() {
        val begin = ms(2026, 10, 26, 15, 0)
        val inst = listOf(
            CalendarInstance(10, 1, "Réunion", begin, false, listOf(10, 30)),
            CalendarInstance(11, 2, "Dentiste", begin, false, listOf(60))
        )
        val cfgs = mapOf(
            1L to CalendarConfig(1, enabled = true, mode = CalendarConfig.MODE_REMINDER),
            2L to CalendarConfig(2, enabled = true, mode = CalendarConfig.MODE_EVENT, source = "mix:peak")
        )
        val t = AlarmSchedule.calendarTriggers(inst, cfgs, mondayNoon, emptySet(), zone = zone)
        assertEquals(
            listOf(begin - 30 * 60_000L, begin - 10 * 60_000L, begin),
            t.map { it.at }
        )
        assertEquals("mix:peak", t.last().source)
        // Déjà jouée : écartée
        val t2 = AlarmSchedule.calendarTriggers(inst, cfgs, mondayNoon, setOf(t.first().key), zone = zone)
        assertEquals(2, t2.size)
        // Agenda désactivé : rien
        val off = cfgs.mapValues { it.value.copy(enabled = false) }
        assertTrue(AlarmSchedule.calendarTriggers(inst, off, mondayNoon, emptySet(), zone = zone).isEmpty())
    }

    @Test
    fun allDayEventsOnlyRingOnReminders() {
        // Toute la journée du 27/10 : minuit UTC dans le fournisseur
        val utcMidnight = LocalDateTime.of(2026, 10, 27, 0, 0).atZone(ZoneId.of("UTC"))
            .toInstant().toEpochMilli()
        val inst = listOf(CalendarInstance(5, 1, "Anniv", utcMidnight, true, listOf(15)))
        val event = mapOf(1L to CalendarConfig(1, true, CalendarConfig.MODE_EVENT))
        assertTrue(AlarmSchedule.calendarTriggers(inst, event, mondayNoon, emptySet(), zone = zone).isEmpty())
        val rem = mapOf(1L to CalendarConfig(1, true, CalendarConfig.MODE_REMINDER))
        val t = AlarmSchedule.calendarTriggers(inst, rem, mondayNoon, emptySet(), zone = zone)
        assertEquals(listOf(ms(2026, 10, 26, 23, 45)), t.map { it.at })
    }

    @Test
    fun serializationRoundTrip() {
        val list = listOf(
            Alarm(1, 6, 45, setOf(1, 3, 5), true, "playlist:Matin", 123L, "Sport"),
            Alarm(2, 22, 0, emptySet(), false, "smart:Calme")
        )
        assertEquals(list, AlarmSchedule.readAlarms(AlarmSchedule.writeAlarms(list)))
        val cfgs = listOf(CalendarConfig(7, true, CalendarConfig.MODE_EVENT, "mix:chill"))
        assertEquals(
            cfgs.associateBy { it.calendarId },
            AlarmSchedule.readCalendarConfigs(AlarmSchedule.writeCalendarConfigs(cfgs))
        )
    }
}
