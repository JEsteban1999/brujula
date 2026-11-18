package app.brujula.core.time

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Test
import org.junit.Assert.assertEquals

class FormattingTest {
    @Test fun spanish() {
        assertEquals("Miércoles 18 de noviembre", formatDayEs(LocalDate.of(2026, 11, 18)))
        assertEquals("2 h 30", formatMinutes(150)); assertEquals("45 min", formatMinutes(45)); assertEquals("6 h", formatMinutes(360))
        assertEquals("9:05", formatTime(LocalTime.of(9, 5))); assertEquals("19:00", formatTime(LocalTime.of(19, 0)))
    }
}
