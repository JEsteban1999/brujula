package app.brujula.habits

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class StreaksTest {
    private val wed = LocalDate.of(2026, 11, 18)

    @Test fun streaks() {
        assertEquals(3, exerciseStreak(wed, setOf(wed, wed.minusDays(1), wed.minusDays(2))))
        assertEquals(2, exerciseStreak(wed, setOf(wed.minusDays(1), wed.minusDays(2)))) // hoy aún no
        assertEquals(0, exerciseStreak(wed, setOf(wed.minusDays(2))))
    }

    @Test fun gapBreaksStreak() {
        assertEquals(1, exerciseStreak(wed, setOf(wed, wed.minusDays(2))))
    }
}