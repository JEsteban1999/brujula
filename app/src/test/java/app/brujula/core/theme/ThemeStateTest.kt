package app.brujula.core.theme

import app.brujula.core.time.FixedClock
import app.brujula.routine.Routine
import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class ThemeStateTest {
    private val wake = LocalTime.of(8, 15)

    @Test fun autoFlipsAt2100WithoutRoutineChange() = runTest {
        val clock = FixedClock(LocalDateTime.of(2026, 11, 18, 20, 59))
        val routine = MutableStateFlow(Routine(wake = wake, themeMode = ThemeMode.AUTO))
        darkFlow(routine, clock, tickMs = 60_000).test {
            assertEquals(false, awaitItem())
            clock.current = LocalDateTime.of(2026, 11, 18, 21, 0)
            advanceTimeBy(60_001)
            assertEquals(true, awaitItem())
            clock.current = LocalDateTime.of(2026, 11, 19, 8, 15)
            advanceTimeBy(60_001)
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun routineChangeAppliesImmediatelyAndNothingBeforeLoad() = runTest {
        val clock = FixedClock(LocalDateTime.of(2026, 11, 18, 12, 0))
        val routine = MutableStateFlow(Routine(wake = wake, themeMode = ThemeMode.LIGHT))
        darkFlow(routine, clock, tickMs = 60_000).test {
            assertEquals(false, awaitItem())
            routine.value = routine.value.copy(themeMode = ThemeMode.DARK)
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // Sin rutina cargada no se emite nada (la pantalla no aplica tema todavía).
        darkFlow(flow<Routine> { }, clock, tickMs = 60_000).test { awaitComplete() }
    }
}
