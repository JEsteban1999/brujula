package app.brujula.routine

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HolidaysTest {
    @Test fun easter() {
        assertEquals(LocalDate.of(2025, 4, 20), easterSunday(2025))
        assertEquals(LocalDate.of(2026, 4, 5), easterSunday(2026))
        assertEquals(LocalDate.of(2027, 3, 28), easterSunday(2027))
    }

    @Test fun holidays2026() {
        val h = colombianHolidays(2026)
        assertEquals(18, h.size)
        val expected = listOf("01-01","01-12","03-23","04-02","04-03","05-01","05-18","06-08","06-15",
            "06-29","07-20","08-07","08-17","10-12","11-02","11-16","12-08","12-25")
            .map { LocalDate.parse("2026-$it") }
        assertEquals(expected.toSet(), h.keys)
        assertEquals("Reyes Magos", h[LocalDate.of(2026, 1, 12)])
        assertEquals("Día de la Independencia", h[LocalDate.of(2026, 7, 20)])
    }

    @Test fun dayTypes() {
        assertEquals(DayKind.WORKDAY, dayTypeOf(LocalDate.of(2026, 11, 18)).kind)        // miércoles
        assertEquals(DayKind.WEEKEND, dayTypeOf(LocalDate.of(2026, 11, 21)).kind)        // sábado
        val tue = dayTypeOf(LocalDate.of(2026, 12, 8))                                    // martes festivo
        assertEquals(DayKind.HOLIDAY, tue.kind); assertEquals("Inmaculada Concepción", tue.holidayName); assertTrue(tue.isRest)
        assertEquals(DayKind.HOLIDAY, dayTypeOf(LocalDate.of(2026, 11, 1).plusDays(1)).kind) // lunes trasladado
    }
}
