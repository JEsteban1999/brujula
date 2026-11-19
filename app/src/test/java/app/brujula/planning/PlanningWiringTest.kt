package app.brujula.planning

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.brujula.calendar.FakeCalendarSource
import app.brujula.claude.DayPlanner
import app.brujula.claude.FakeClaudeGateway
import app.brujula.claude.PlanOutcome
import app.brujula.container
import app.brujula.core.time.FixedClock
import app.brujula.core.time.TimeSpan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

/** AppContainer arma el contexto de planificación y DayPlanner con las dependencias vigentes (sustituibles). */
@RunWith(RobolectricTestRunner::class)
class PlanningWiringTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val thu = LocalDate.of(2026, 11, 19)

    @Test fun planContextUsesTheCurrentClock() = runBlocking {
        val c = app.container
        c.clock = FixedClock(LocalDateTime.of(2026, 11, 18, 21, 0))
        c.calendarSource = FakeCalendarSource()
        val ctx = c.planContext(thu)
        assertEquals(thu, ctx.day)
        assertEquals(LocalDate.of(2026, 11, 18), ctx.logicalToday)
        assertEquals(TimeSpan(thu.atTime(12, 0), thu.atTime(14, 0)), ctx.lunch)
        assertEquals(thu, c.daySnapshots.snapshot(thu).day)
    }

    @Test fun dayPlannerIsReplaceableAndDefaultsToTheCurrentGateway() = runBlocking {
        val c = app.container
        c.clock = FixedClock(LocalDateTime.of(2026, 11, 18, 21, 0))
        c.calendarSource = FakeCalendarSource()
        val gateway = FakeClaudeGateway()
        c.claudeGateway = gateway
        // El día +7 se rechaza antes de la red: prueba que el planificador por defecto usa el reloj vigente.
        assertTrue(c.dayPlanner.propose(thu.plusDays(6), request = null) is PlanOutcome.Failed)
        assertEquals(0, gateway.planCalls)

        val custom = DayPlanner(gateway, { c.planContext(it) })
        c.dayPlanner = custom
        assertSame(custom, c.dayPlanner)
    }
}
