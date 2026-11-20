package app.brujula.week

import app.brujula.activities.Activity
import app.brujula.activities.ActivityType
import app.brujula.activities.OccurrenceOverride
import app.brujula.activities.Repeat
import app.brujula.activities.WorkStatus
import app.brujula.calendar.ExternalEvent
import app.brujula.core.time.TimeSpan
import app.brujula.planning.SnapshotInputs
import app.brujula.routine.Routine
import app.brujula.routine.logicalDay
import app.brujula.sleep.SleepRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class WeekModelTest {
    private val mon = LocalDate.of(2026, 11, 16) // festivo (Independencia de Cartagena trasladado)
    private val tue = mon.plusDays(1)
    private val wed = mon.plusDays(2)
    private val now = wed.atTime(10, 24)
    private val routine = Routine()

    private fun ext(id: Long, title: String, s: LocalDateTime, e: LocalDateTime, source: String = "Outlook", allDay: Boolean = false) =
        ExternalEvent(id, 0, 1, "Cal", source, title, TimeSpan(s, e), allDay, null)

    private fun inputs(
        activities: List<Activity> = emptyList(),
        done: Set<Pair<Long, LocalDate>> = emptySet(),
        overrides: Map<Pair<Long, LocalDate>, OccurrenceOverride> = emptyMap(),
        external: List<ExternalEvent> = emptyList(),
        work: Set<Long> = emptySet(),
    ) = SnapshotInputs(routine, activities, done, overrides, external) { it.eventId in work }

    private fun work(id: Long, day: LocalDate, from: Int, minutes: Int, repeat: Repeat = Repeat.NONE) =
        Activity(id = id, title = "Trabajo $id", type = ActivityType.WORK, date = day, start = LocalTime.of(from, 0), durationMin = minutes, repeat = repeat)

    private fun build(inputs: SnapshotInputs, monday: LocalDate = mon, at: LocalDateTime = now, sleep: Map<LocalDate, SleepRecord> = emptyMap()) =
        buildWeek(monday, at, inputs, sleep)

    @Test fun statesPastTodayPlanned() {
        val w = build(inputs())
        assertEquals(
            listOf(WeekDayState.PAST, WeekDayState.PAST, WeekDayState.TODAY) + List(4) { WeekDayState.PLANNED },
            w.days.map { it.state },
        )
        assertEquals("L M X J V S D", w.days.joinToString(" ") { it.letter })
    }

    @Test fun holidayMondayIsRest() {
        val w = build(inputs(listOf(work(1, mon, 8, 120), work(2, tue, 8, 420))))
        assertEquals(listOf(true, false, false, false, false, true, true), w.days.map { it.rest })
        assertEquals(120, w.days[0].workMinutes)
        // Solo martes y miércoles entran al promedio: (420 + 0) / 2
        assertEquals(210, w.totals.workAverage)
    }

    @Test fun workCountsOwnWorkAndExternalWorkWithOverrides() {
        val act = work(1, tue, 8, 240)
        val e = ext(10, "Daily", tue.atTime(15, 0), tue.atTime(16, 0))
        assertEquals(300, build(inputs(listOf(act), external = listOf(e), work = setOf(10))).days[1].workMinutes)
        val moved = OccurrenceOverride(1, tue, tue.atTime(9, 0), null, false)
        assertEquals(300, build(inputs(listOf(act), overrides = mapOf((1L to tue) to moved), external = listOf(e), work = setOf(10))).days[1].workMinutes)
        val skipped = OccurrenceOverride(1, tue, null, null, true)
        assertEquals(60, build(inputs(listOf(act), overrides = mapOf((1L to tue) to skipped), external = listOf(e), work = setOf(10))).days[1].workMinutes)
    }

    @Test fun averageOnlyWorkdaysUpToToday() {
        val w = build(inputs(listOf(work(1, tue, 8, 420), work(2, wed, 8, 150), work(3, wed.plusDays(1), 8, 600))))
        assertEquals(285, w.totals.workAverage)
        assertEquals("Muy por debajo", w.totals.workLabel)
    }

    @Test fun futureWeekAveragesPlanned() {
        val next = mon.plusDays(7) // lunes 23: cinco laborales
        val w = build(inputs(listOf(work(1, next.plusDays(1), 8, 400), work(2, next.plusDays(2), 8, 440))), monday = next)
        assertEquals("Planeado", w.totals.workLabel)
        assertEquals(168, w.totals.workAverage)
    }

    @Test fun exerciseDoneOverScheduled() {
        val gym = Activity(id = 5, title = "Gimnasio", type = ActivityType.EXERCISE, date = mon, start = LocalTime.of(18, 30), durationMin = 60, repeat = Repeat.WORKDAYS)
        val w = build(inputs(listOf(gym), done = setOf(5L to tue)))
        assertEquals(1, w.totals.exerciseDone)
        assertEquals(4, w.totals.exerciseTotal)
        assertEquals(1, w.days[1].exerciseDone)
        assertEquals(1, w.days[1].exerciseTotal)
    }

    @Test fun sleepAverageNightsWithMorningUpToToday() {
        assertEquals(480, build(inputs()).totals.sleepAverage)
        val bed = SleepRecord(wed.minusDays(1), wed.atTime(1, 30), null)
        assertEquals(460, build(inputs(), sleep = mapOf(bed.night to bed)).totals.sleepAverage)
        assertEquals(488, build(inputs(), at = wed.atTime(7, 0)).totals.sleepAverage)
        assertNull(build(inputs(), monday = mon.plusDays(7)).totals.sleepAverage)
    }

    @Test fun eventsColoredByOrigin() {
        val own = work(1, tue, 9, 60)
        val events = listOf(
            ext(1, "Reunión", tue.atTime(10, 0), tue.atTime(11, 0), "Google"),
            ext(2, "Daily", tue.atTime(12, 0), tue.atTime(13, 0), "Outlook"),
            ext(3, "Clase", tue.atTime(14, 0), tue.atTime(15, 0), "Exchange"),
            ext(4, "Feriado de la empresa", tue.atStartOfDay(), tue.plusDays(1).atStartOfDay(), "Google", allDay = true),
        )
        val day = build(inputs(listOf(own), external = events, work = setOf(2))).days[1]
        assertEquals(listOf("Feriado de la empresa", "Trabajo 1", "Reunión", "Daily", "Clase"), day.events.map { it.title })
        assertEquals(
            listOf(EventOrigin.GOOGLE, EventOrigin.BRUJULA, EventOrigin.GOOGLE, EventOrigin.OUTLOOK, EventOrigin.OTHER),
            day.events.map { it.origin },
        )
        assertEquals(listOf("Todo el día", "9:00", "10:00", "12:00", "14:00"), day.events.map { it.timeLabel })
        assertEquals("Exchange", day.events.last().sourceLabel)
        assertEquals("Brújula", day.events[1].sourceLabel)
        assertEquals(listOf(false, false, true, false), day.events.filterIsInstance<WeekEvent.External>().map { it.countsAsWork })
    }

    @Test fun canSkipOnlyRecurringWithoutCopyFromToday() {
        val acts = listOf(
            Activity(id = 1, title = "Serie", type = ActivityType.PERSONAL, date = mon, start = LocalTime.of(20, 0), durationMin = 30, repeat = Repeat.DAILY, copyToGoogle = false),
            Activity(id = 2, title = "Serie con copia", type = ActivityType.PERSONAL, date = mon, start = LocalTime.of(21, 0), durationMin = 30, repeat = Repeat.DAILY, copyToGoogle = true),
            Activity(id = 3, title = "Una vez", type = ActivityType.PERSONAL, date = wed, start = LocalTime.of(19, 0), durationMin = 30),
        )
        val w = build(inputs(acts))
        fun skip(dayIndex: Int, title: String) = (w.days[dayIndex].events.first { it.title == title } as WeekEvent.Own).canSkip
        assertFalse(skip(1, "Serie")) // martes: pasado
        assertTrue(skip(2, "Serie")) // hoy
        assertTrue(skip(3, "Serie")) // futuro
        assertFalse(skip(3, "Serie con copia"))
        assertFalse(skip(2, "Una vez"))
    }

    @Test fun logicalSundayAt0130StillSameWeek() {
        assertEquals(mon, mondayOf(logicalDay(mon.plusDays(7).atTime(1, 30))))
        assertEquals(mon, mondayOf(wed))
        assertEquals(mon, mondayOf(mon.plusDays(6)))
    }

    @Test fun barLabelsAndAccessibleText() {
        val w = build(inputs(listOf(work(1, tue, 8, 390), work(2, wed.plusDays(1), 8, 390))))
        assertEquals("6.5h", w.days[1].barLabel)
        assertEquals("Martes 17: 6 h 30 de trabajo", w.days[1].a11y)
        assertEquals("Jueves 19: 6 h 30 de trabajo planeado", w.days[3].a11y)
        assertEquals("Viernes 20: sin trabajo", w.days[4].a11y)
        assertEquals("–", w.days[4].barLabel)
        assertEquals(360..420, w.goal)
        assertEquals("Tu semana", w.title)
        assertEquals("16 – 22 de noviembre", w.rangeLabel)
    }

    @Test fun labelsMatchMockup() {
        assertEquals("16 – 22 de noviembre", weekRangeLabel(mon))
        assertEquals("30 de noviembre – 6 de diciembre", weekRangeLabel(LocalDate.of(2026, 11, 30)))
        assertEquals("28 de diciembre de 2026 – 3 de enero de 2027", weekRangeLabel(LocalDate.of(2026, 12, 28)))
        assertEquals("Tu semana", weekTitle(mon, mon))
        assertEquals("Semana pasada", weekTitle(mon.minusDays(7), mon))
        assertEquals("Próxima semana", weekTitle(mon.plusDays(7), mon))
        assertEquals("Semana", weekTitle(mon.plusDays(14), mon))
        assertEquals("6.5h", barLabel(390))
        assertEquals("7.2h", barLabel(432))
        assertEquals("6h", barLabel(360))
        assertEquals("–", barLabel(0))
        assertEquals("Meta: 6–7 h por día", goalLabel(360..420))
        assertEquals("Hoy, miércoles 18", dayDetailTitle(wed, wed))
        assertEquals("Jueves 19", dayDetailTitle(wed.plusDays(1), wed))
        assertEquals("Muy por debajo", workStatusLabel(WorkStatus.LOW))
        assertEquals("Debajo de tu meta", workStatusLabel(WorkStatus.BELOW_GOAL))
        assertEquals("En tu meta", workStatusLabel(WorkStatus.IN_GOAL))
        assertEquals("Sobre tu meta", workStatusLabel(WorkStatus.ABOVE_GOAL))
        assertEquals(EventOrigin.GOOGLE, originOf("Google"))
        assertEquals(EventOrigin.OUTLOOK, originOf("Outlook"))
        assertEquals(EventOrigin.OTHER, originOf("a@b.com"))
    }
}
