package app.brujula.activities

import app.brujula.core.time.TimeSpan
import java.time.LocalDate
import java.time.LocalTime
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
}
