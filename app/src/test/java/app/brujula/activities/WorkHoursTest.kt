package app.brujula.activities

import app.brujula.core.time.TimeSpan
import java.time.LocalDate
import java.time.LocalTime
import app.brujula.calendar.ExternalEvent
import org.junit.Test
import org.junit.Assert.assertEquals

class WorkHoursTest {
    @Test fun statusAgainstGoal() {
        assertEquals(WorkStatus.LOW, workStatus(299, 360..420))
        assertEquals(WorkStatus.BELOW_GOAL, workStatus(300, 360..420))
        assertEquals(WorkStatus.IN_GOAL, workStatus(360, 360..420))
        assertEquals(WorkStatus.IN_GOAL, workStatus(420, 360..420))
        assertEquals(WorkStatus.ABOVE_GOAL, workStatus(421, 360..420))
    }
    @Test fun workMinutesCountsOnlyWork() {
        val d = LocalDate.of(2026, 11, 18)
        fun occ(id: Long, type: ActivityType, dur: Int): Occurrence {
            val a = Activity(id, "A$id", type, d, LocalTime.of(9, 0), dur)
            return Occurrence(a, d, TimeSpan(d.atTime(9, 0), d.atTime(9, 0).plusMinutes(dur.toLong())), false)
        }
        val list = listOf(occ(1, ActivityType.WORK, 90), occ(2, ActivityType.WORK, 60), occ(3, ActivityType.EXERCISE, 30))
        assertEquals(150, workMinutes(list))
    }

    @Test fun workMinutesAddsMarkedExternalAndSkipsAllDay() {
        val d = LocalDate.of(2026, 11, 18)
        val a = Activity(1, "T", ActivityType.WORK, d, LocalTime.of(9, 0), 60)
        val own = Occurrence(a, d, TimeSpan(d.atTime(9, 0), d.atTime(10, 0)), false)
        fun ev(s: java.time.LocalDateTime, e: java.time.LocalDateTime, allDay: Boolean = false) =
            ExternalEvent(1, 0, 1, "C", "Google", "x", TimeSpan(s, e), allDay, null)
        val logical = TimeSpan(d.atTime(4, 0), d.plusDays(1).atTime(4, 0))
        val timed = ev(d.atTime(15, 0), d.atTime(16, 0))
        val allDay = ev(d.atStartOfDay(), d.plusDays(1).atStartOfDay(), allDay = true)
        val late = ev(d.plusDays(1).atTime(3, 30), d.plusDays(1).atTime(5, 0)) // 30 min dentro del dia logico
        assertEquals(60 + 60 + 30, workMinutes(listOf(own), listOf(timed, allDay, late), logical))
    }
}
