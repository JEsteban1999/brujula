package app.brujula.routine

import app.brujula.core.time.TimeSpan
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class RoutineTest {
    private val r = Routine()
    private val d = LocalDate.of(2026, 11, 18)
    @Test fun awakeWindowCrossesMidnight() {
        assertEquals(TimeSpan(d.atTime(8, 15), d.plusDays(1).atTime(0, 30)), r.awakeWindow(d))
    }
    @Test fun isAwakeAroundMidnight() {
        assertTrue(r.isAwake(d.atTime(23, 50)))
        assertTrue(r.isAwake(d.plusDays(1).atTime(0, 20)))
        assertFalse(r.isAwake(d.plusDays(1).atTime(0, 31)))
        assertFalse(r.isAwake(d.plusDays(1).atTime(8, 0)))
    }
    @Test fun logicalDaySwitchesAt4am() {
        assertEquals(d, logicalDay(d.plusDays(1).atTime(0, 20)))
        assertEquals(d, logicalDay(d.plusDays(1).atTime(3, 59)))
        assertEquals(d.plusDays(1), logicalDay(d.plusDays(1).atTime(4, 0)))
    }
}
