package app.brujula.activities

import app.brujula.core.time.TimeSpan
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

class ConflictsTest {
    private val d = LocalDate.of(2026, 11, 18)
    private fun occ(id: Long, h: Int, m: Int, dur: Long, day: LocalDate = d) =
        Occurrence(Activity(id, "A$id", ActivityType.WORK, day, LocalTime.of(h, m), dur.toInt()), day,
            TimeSpan(day.atTime(h, m), day.atTime(h, m).plusMinutes(dur)), false)
    @Test fun touchingEdgesDoNotConflict() {
        assertNull(findConflict(TimeSpan(d.atTime(10, 0), d.atTime(11, 0)), listOf(occ(1, 9, 0, 60))))
    }
    @Test fun overlapIsDetected() {
        assertEquals(1L, findConflict(TimeSpan(d.atTime(9, 30), d.atTime(10, 30)), listOf(occ(1, 9, 0, 60)))?.activity?.id)
    }
    @Test fun lateActivityConflictsWithNextDay() {
        val nextMorning = occ(2, 0, 0, 30, d.plusDays(1))
        assertNotNull(findConflict(TimeSpan(d.atTime(23, 30), d.plusDays(1).atTime(0, 30)), listOf(nextMorning)))
    }
    @Test fun editingIgnoresItself() {
        assertNull(findConflict(TimeSpan(d.atTime(9, 0), d.atTime(10, 0)), listOf(occ(1, 9, 0, 60)), ignoreActivityId = 1))
    }
}
