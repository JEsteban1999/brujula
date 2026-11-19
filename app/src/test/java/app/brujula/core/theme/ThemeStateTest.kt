package app.brujula.core.theme

import app.brujula.core.time.FixedClock
import app.brujula.routine.Routine
import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableSharedFlow
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

    @Test fun routineChangeAppliesImmediately() = runTest {
        val clock = FixedClock(LocalDateTime.of(2026, 11, 18, 12, 0))
        val routine = MutableStateFlow(Routine(wake = wake, themeMode = ThemeMode.LIGHT))
        darkFlow(routine, clock, tickMs = 60_000).test {
            assertEquals(false, awaitItem())
            routine.value = routine.value.copy(themeMode = ThemeMode.DARK)
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // Sin rutina cargada no se emite nada hasta que vence el plazo.
        val never = MutableSharedFlow<Routine>()
        darkFlow(never, clock, tickMs = 60_000).test { expectNoEvents(); cancelAndIgnoreRemainingEvents() }
    }

    @Test fun failingRoutineFallsBackToDefaults() = runTest {
        // Por defecto: AUTO, levantarse 08:15 -> a las 22:00 es oscuro.
        val clock = FixedClock(LocalDateTime.of(2026, 11, 18, 22, 0))
        val broken = flow<Routine> { throw java.io.IOException("datastore") }
        darkFlow(broken, clock, tickMs = 60_000).test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun neverEmittingRoutineFallsBackAfterTimeoutThenFollowsRealOne() = runTest {
        val clock = FixedClock(LocalDateTime.of(2026, 11, 18, 22, 0))
        val routine = MutableSharedFlow<Routine>()
        darkFlow(routine, clock, tickMs = 60_000, fallbackAfterMs = 1_500).test {
            expectNoEvents()
            advanceTimeBy(1_501)
            assertEquals(true, awaitItem())
            routine.emit(Routine(themeMode = ThemeMode.LIGHT))
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun resubscribingUsesCurrentClock() = runTest {
        val clock = FixedClock(LocalDateTime.of(2026, 11, 18, 20, 0))
        val routine = MutableStateFlow(Routine(wake = wake, themeMode = ThemeMode.AUTO))
        val flow = darkFlow(routine, clock, tickMs = 60_000)
        flow.test { assertEquals(false, awaitItem()); cancelAndIgnoreRemainingEvents() }
        clock.current = LocalDateTime.of(2026, 11, 18, 21, 30)
        flow.test { assertEquals(true, awaitItem()); cancelAndIgnoreRemainingEvents() }
    }

    @Test fun autoOnSaturdayWaitsForRestWake() = runTest {
        val clock = FixedClock(LocalDateTime.of(2026, 11, 21, 9, 30))
        val routine = MutableStateFlow(Routine(themeMode = ThemeMode.AUTO))
        darkFlow(routine, clock, tickMs = 60_000).test {
            assertEquals(true, awaitItem())
            clock.current = LocalDateTime.of(2026, 11, 21, 10, 0)
            advanceTimeBy(60_001)
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
