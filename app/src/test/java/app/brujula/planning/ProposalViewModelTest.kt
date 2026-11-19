package app.brujula.planning

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.Activity
import app.brujula.activities.ActivityOrigin
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.calendar.FakeGoogleCalendarWriter
import app.brujula.calendar.GoogleCopyJournal
import app.brujula.calendar.GoogleCopySync
import app.brujula.claude.ClaudeNotes
import app.brujula.claude.DayPlanner
import app.brujula.claude.FakeClaudeGateway
import app.brujula.claude.PlanOutcome
import app.brujula.core.time.FixedClock
import app.brujula.core.time.TimeSpan
import app.brujula.data.db.BrujulaDatabase
import app.brujula.reminders.ReminderResync
import app.brujula.voice.SpeechEvent
import app.brujula.voice.SpeechInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Planificador de mentira: devuelve lo que se le pone en la cola y anota cada llamada. */
private class FakePlanner : DayPlanner(FakeClaudeGateway(), { error("sin uso") }) {
    class Call(val day: LocalDate, val request: String?, val previous: Proposal?, val adjustment: String?)

    val calls = mutableListOf<Call>()
    val queue = ArrayDeque<PlanOutcome>()
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun propose(day: LocalDate, request: String?, previous: Proposal?, adjustment: String?): PlanOutcome {
        calls += Call(day, request, previous, adjustment)
        gate?.await()
        return queue.removeFirst()
    }
}

private class NoSpeech : SpeechInput {
    override val isAvailable = false
    override fun start(onEvent: (SpeechEvent) -> Unit) = Unit
    override fun stop() = Unit
    override fun cancel() = Unit
    override fun destroy() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(application = app.brujula.TestBrujulaApp::class)
class ProposalViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val thu = LocalDate.of(2026, 11, 19)
    private val fri = thu.plusDays(1)
    private val clock = FixedClock(thu.atTime(10, 0))
    private lateinit var db: BrujulaDatabase
    private lateinit var activities: ActivityRepository
    private lateinit var proposals: ProposalRepository
    private lateinit var executor: ProposalExecutor
    private val planner = FakePlanner()
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.databaseBuilder(ctx, BrujulaDatabase::class.java, File(tmp.root, "vm.db").absolutePath).allowMainThreadQueries().build()
        activities = ActivityRepository(db.activityDao())
        proposals = ProposalRepository(db.proposalDao())
        executor = newExecutor()
    }

    @After fun tearDown() {
        db.close()
        storeScope.cancel()
        syncScope.cancel()
    }

    private fun newExecutor() = ProposalExecutor(
        db, activities, proposals, GoogleCopySync(FakeGoogleCalendarWriter(), activities), ReminderResync { },
        ClaudeNotes(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "n-${System.nanoTime()}.preferences_pb") }),
        clock, syncScope,
        journal = GoogleCopyJournal(
            ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("vm-j", Context.MODE_PRIVATE)
                .also { it.edit().clear().commit() },
        ),
    )

    private fun TestScope.vm(
        request: String? = null,
        day: LocalDate = fri,
        exec: ProposalExecutor = executor,
        bedtime: suspend () -> LocalDateTime? = { null },
    ) = ProposalViewModel(
        day = day, request = request, planner = planner, proposals = proposals, executor = exec, speech = NoSpeech(), clock = clock,
        wakeFor = { LocalTime.of(8, 15) }, bedtimeRemindAt = bedtime, scopeOverride = backgroundScope,
    )

    private val work = Activity(
        title = "Trabajo", type = ActivityType.WORK, date = LocalDate.of(2026, 11, 20), start = LocalTime.of(13, 30), durationMin = 90,
        remindBeforeMin = null, copyToGoogle = false, origin = ActivityOrigin.PLAN,
    )

    private fun proposal(
        ops: List<PlanOp> = listOf(PlanOp.Create(work)), request: String? = "arma mi día", summary: String = "Te armé el viernes.",
    ) = Proposal(
        day = fri, createdAt = clock.now(), request = request, summary = summary, ops = ops, rejected = emptyList(),
        preview = listOf(PreviewBlock("Trabajo", TimeSpan(fri.atTime(13, 30), fri.atTime(15, 0)), BlockTag.NUEVO, "13:30 – 15:00")),
        totals = PlanTotals(90, 0, 480, fri.atTime(23, 0)),
    )

    private suspend fun showing(v: ProposalViewModel) = v.state.first { it is ProposalUiState.Showing } as ProposalUiState.Showing

    @Test fun showsStoredPendingWithoutCallingClaude() = runTest {
        val id = proposals.savePending(proposal())
        val s = showing(vm())
        assertEquals(id, s.proposal.id)
        assertEquals("Tu viernes 20", s.dayTitle)
        assertFalse(s.stale)
        assertTrue(planner.calls.isEmpty())
    }

    @Test fun pendingProposalSurvivesNewViewModel() = runTest {
        planner.queue += PlanOutcome.Ok(proposal())
        val first = showing(vm(request = "arma mi día"))
        // La pantalla se cierra y el proceso muere: un ViewModel nuevo (sin pedido) encuentra la propuesta guardada.
        val again = showing(vm())
        assertEquals(first.proposal.id, again.proposal.id)
        assertNotEquals(0L, again.proposal.id)
        assertEquals(1, planner.calls.size)
    }

    @Test fun requestGeneratesAndSaves() = runTest {
        planner.gate = CompletableDeferred()
        planner.queue += PlanOutcome.Ok(proposal())
        val v = vm(request = "arma mi día")
        v.state.first { it is ProposalUiState.Loading }
        planner.gate!!.complete(Unit)
        val s = showing(v)
        assertEquals(proposals.pendingFor(fri)!!.id, s.proposal.id)
        assertEquals("arma mi día", planner.calls.single().request)
    }

    @Test fun offlineShowsErrorWithRetry() = runTest {
        planner.queue += PlanOutcome.Offline
        planner.queue += PlanOutcome.Failed(DayPlanner.OFFLINE, retryable = true)
        planner.queue += PlanOutcome.Ok(proposal())
        val v = vm(request = "arma mi día")
        assertEquals(ProposalUiState.Error(DayPlanner.OFFLINE, retry = true), v.state.first { it is ProposalUiState.Error })
        v.retry()
        v.state.first { it is ProposalUiState.Error && planner.calls.size == 2 }
        v.retry()
        showing(v)
        assertEquals(listOf("arma mi día", "arma mi día", "arma mi día"), planner.calls.map { it.request })
    }

    @Test fun acceptShowsTomorrowConfirmation() = runTest {
        proposals.savePending(proposal())
        val v = vm()
        showing(v)
        v.accept()
        val a = v.state.first { it is ProposalUiState.Accepted } as ProposalUiState.Accepted
        assertEquals("Mañana está listo", a.title)
        assertTrue(a.body, a.body.startsWith("Lo puse en tu agenda."))
        assertTrue(a.body, a.body.contains("A las 8:15 te llega el resumen."))
        assertEquals(1, activities.observeAll().first().count { it.title == "Trabajo" })
    }

    @Test fun acceptTitleAndBodyForOtherDaysAndBedtime() = runTest {
        val sat = thu.plusDays(2)
        proposals.savePending(proposal(ops = listOf(PlanOp.Create(work.copy(date = sat)))).copy(day = sat))
        val v = vm(day = sat, bedtime = { sat.atTime(23, 30) })
        showing(v)
        v.accept()
        val a = v.state.first { it is ProposalUiState.Accepted } as ProposalUiState.Accepted
        assertEquals("Tu sábado 21 está listo", a.title)
        assertTrue(a.body, a.body.contains("A las 8:15 te llega el resumen y a las 23:30 el aviso para ir a dormir."))
    }

    @Test fun staleAcceptShowsBanner() = runTest {
        val id = activities.upsert(work.copy(title = "Llamar", type = ActivityType.PERSONAL, date = fri, start = LocalTime.of(15, 0)))
        val move = PlanOp.Move(
            "A$id", id, fri, "Llamar", TimeSpan(fri.atTime(15, 0), fri.atTime(16, 30)), TimeSpan(fri.atTime(17, 0), fri.atTime(18, 30)),
            oneOff = true,
        )
        proposals.savePending(proposal(ops = listOf(move)))
        val v = vm()
        assertFalse(showing(v).stale)
        activities.delete(id)
        v.accept()
        val s = v.state.first { it is ProposalUiState.Showing && it.stale } as ProposalUiState.Showing
        assertEquals("Tu agenda cambió desde esta propuesta.", s.message)
        assertTrue(planner.calls.isEmpty())
    }

    @Test fun expiredAcceptShowsTheSameBannerAndRequestNewReusesTheRequest() = runTest {
        proposals.savePending(proposal())
        val v = vm()
        showing(v)
        clock.current = fri.atTime(14, 0) // el bloque de las 13:30 ya empezó
        v.accept()
        val s = v.state.first { it is ProposalUiState.Showing && it.stale } as ProposalUiState.Showing
        assertEquals("Tu agenda cambió desde esta propuesta.", s.message)
        planner.queue += PlanOutcome.Ok(proposal(request = "arma mi día"))
        v.requestNew()
        val n = v.state.first { it is ProposalUiState.Showing && !it.stale } as ProposalUiState.Showing
        assertNotEquals(s.proposal.id, n.proposal.id)
        assertEquals("arma mi día", planner.calls.single().request)
        assertNull(planner.calls.single().adjustment)
    }

    @Test fun undoReturnsToProposal() = runTest {
        proposals.savePending(proposal())
        val v = vm()
        val before = showing(v)
        v.accept()
        v.state.first { it is ProposalUiState.Accepted }
        v.undo()
        val s = showing(v)
        assertEquals(before.proposal.id, s.proposal.id)
        assertEquals(ProposalStatus.PENDING, s.proposal.status)
        assertEquals(0, activities.observeAll().first().count { it.title == "Trabajo" })
        assertEquals(before.proposal.id, proposals.pendingFor(fri)!!.id)
    }

    @Test fun adjustSupersedesPrevious() = runTest {
        val oldId = proposals.savePending(proposal())
        val v = vm()
        showing(v)
        planner.queue += PlanOutcome.Ok(proposal(summary = "Ajustado."))
        v.adjust("Pasa el gym a la tarde")
        val s = v.state.first { it is ProposalUiState.Showing && it.proposal.id != oldId } as ProposalUiState.Showing
        assertEquals("Ajustado.", s.proposal.summary)
        assertEquals(oldId, planner.calls.single().previous!!.id)
        assertEquals("Pasa el gym a la tarde", planner.calls.single().adjustment)
        assertEquals("SUPERSEDED", db.proposalDao().get(oldId)!!.status)
        assertEquals(s.proposal.id, proposals.pendingFor(fri)!!.id)
    }

    @Test fun failedAdjustKeepsCurrent() = runTest {
        val id = proposals.savePending(proposal())
        val v = vm()
        showing(v)
        planner.queue += PlanOutcome.Failed("Claude está ocupado.", retryable = true)
        v.adjust("Hoy no voy a entrenar")
        val s = v.state.first { it is ProposalUiState.Showing && it.message != null } as ProposalUiState.Showing
        assertEquals(id, s.proposal.id)
        assertEquals("Claude está ocupado.", s.message)
        assertEquals(id, proposals.pendingFor(fri)!!.id)
    }

    @Test fun cancelWhileWaitingRestoresAndDiscardsLateResult() = runTest {
        val id = proposals.savePending(proposal())
        val v = vm()
        showing(v)
        planner.gate = CompletableDeferred()
        planner.queue += PlanOutcome.Ok(proposal(summary = "Tarde."))
        v.adjust("Pasa el gym a la tarde")
        v.state.first { it is ProposalUiState.Loading }
        v.cancelWaiting()
        val s = v.state.first { it is ProposalUiState.Showing } as ProposalUiState.Showing
        assertEquals(id, s.proposal.id)
        planner.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(id, (v.state.value as ProposalUiState.Showing).proposal.id)
        assertEquals(id, proposals.pendingFor(fri)!!.id)
    }

    @Test fun discardCloses() = runTest {
        proposals.savePending(proposal())
        val v = vm()
        showing(v)
        v.discard()
        v.state.first { it == ProposalUiState.Closed }
        assertNull(proposals.pendingFor(fri))
    }

    @Test fun doubleTapAcceptCallsExecutorOnce() = runTest {
        proposals.savePending(proposal())
        val counting = object : ProposalExecutor(
            db, activities, proposals, GoogleCopySync(FakeGoogleCalendarWriter(), activities), ReminderResync { },
            ClaudeNotes(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "c.preferences_pb") }), clock, syncScope,
        ) {
            var accepts = 0
            val gate = CompletableDeferred<Unit>()
            override suspend fun accept(id: Long): AcceptResult {
                accepts++
                gate.await()
                return super.accept(id)
            }
        }
        val v = vm(exec = counting)
        showing(v)
        v.accept()
        v.accept()
        advanceUntilIdle()
        counting.gate.complete(Unit)
        v.state.first { it is ProposalUiState.Accepted }
        assertEquals(1, counting.accepts)
    }

    @Test fun emptyProposalIsShownWithNoOps() = runTest {
        proposals.savePending(proposal(ops = emptyList()))
        assertTrue(showing(vm()).proposal.ops.isEmpty())
    }

    @Test fun noKeyShowsErrorWithoutRetry() = runTest {
        planner.queue += PlanOutcome.NoKey
        val e = vm(request = "x").state.first { it is ProposalUiState.Error } as ProposalUiState.Error
        assertEquals(DayPlanner.NO_KEY, e.message)
        assertFalse(e.retry)
    }
}
