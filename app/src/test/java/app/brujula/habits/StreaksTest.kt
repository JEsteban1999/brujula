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

    private val mon16 = LocalDate.of(2026, 11, 16)
    private val workWeek = (0L..4L).map { mon16.plusDays(it) }.toSet()
    private val nextMon = LocalDate.of(2026, 11, 23)

    @Test fun weekendWithoutExerciseDoesNotBreakStreak() {
        assertEquals(5, exerciseStreak(nextMon, workWeek))
    }

    @Test fun weekendExerciseAddsToStreak() {
        assertEquals(6, exerciseStreak(nextMon, workWeek + LocalDate.of(2026, 11, 21)))
    }

    @Test fun missedWorkdayBreaksStreakAcrossWeekend() {
        assertEquals(0, exerciseStreak(nextMon, (0L..3L).map { mon16.plusDays(it) }.toSet()))
    }
}
