package app.brujula.planning

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.brujula.calendar.FakeCalendarSource
import app.brujula.calendar.FakeGoogleCalendarWriter
import app.brujula.calendar.GoogleCopyJournal
import app.brujula.calendar.GoogleCopySync
import app.brujula.calendar.GoogleEventDraft
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

    @Test fun proposalServicesAreWiredAndTheExecutorIsReplaceable() = runBlocking {
        val c = app.container
        assertSame(c.proposalRepository, c.proposalRepository)
        assertSame(c.claudeNotes, c.claudeNotes)
        // Por defecto, dos lecturas comparten el mismo cerrojo de un solo vuelo.
        assertSame(c.proposalExecutor.lock, c.proposalExecutor.lock)
        val custom = c.proposalExecutor
        c.proposalExecutor = custom
        assertSame(custom, c.proposalExecutor)
    }

    @Test fun reconcileGoogleCopiesDrainsTheJournalFirst() = runBlocking {
        val c = app.container
        val writer = FakeGoogleCalendarWriter()
        writer.events[600] = GoogleEventDraft("Huérfana", thu.atTime(9, 0), thu.atTime(10, 0), null, activityId = 77)
        c.googleCopySync = GoogleCopySync(writer, c.activityRepository)
        // Una actividad 77 que ya no existe dejó su copia: el diario la borra al reconciliar.
        val entry = GoogleCopyJournal.Entry.Delete(77, 600)
        c.googleCopyJournal.record(listOf(entry))
        c.reconcileGoogleCopies()
        assertTrue(writer.events.isEmpty())
        assertTrue(c.googleCopyJournal.pending().isEmpty())
    }
}
