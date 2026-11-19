package app.brujula.planning

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.Activity
import app.brujula.activities.ActivityOrigin
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.OccurrenceOverride
import app.brujula.activities.Repeat
import app.brujula.calendar.FakeGoogleCalendarWriter
import app.brujula.calendar.GoogleCopySync
import app.brujula.calendar.GoogleEventDraft
import app.brujula.claude.ClaudeNote
import app.brujula.claude.ClaudeNotes
import app.brujula.core.time.FixedClock
import app.brujula.core.time.TimeSpan
import app.brujula.data.db.BrujulaDatabase
import app.brujula.reminders.Reminder
import app.brujula.reminders.ReminderResync
import app.brujula.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import java.time.LocalTime

/** Programador que solo anota: qué esperas "En 15 min" se cancelaron y en qué orden respecto del resync. */
private class RecordingScheduler(private val log: MutableList<String>) : ReminderScheduler {
    override fun replaceAll(reminders: List<Reminder>) = Unit
    override fun scheduleOne(reminder: Reminder) = Unit
    override fun cancelSnooze(key: String) { log += "cancel:$key" }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = app.brujula.TestBrujulaApp::class)
class ProposalExecutorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val wed = LocalDate.of(2026, 11, 18)
    private val thu = wed.plusDays(1)
    private val fri = wed.plusDays(2)
    private val sat = wed.plusDays(3)

    private lateinit var db: BrujulaDatabase
    private lateinit var activities: ActivityRepository
    private lateinit var proposals: ProposalRepository
    private lateinit var notes: ClaudeNotes
    private val writer = FakeGoogleCalendarWriter()
    private lateinit var googleSync: GoogleCopySync
    private val clock = FixedClock(thu.atTime(10, 0))
    private val log = mutableListOf<String>()
    private val resyncs get() = log.count { it == "resync" }
    private val cancels get() = log.filter { it.startsWith("cancel:") }.map { it.removePrefix("cancel:") }
    private lateinit var executor: ProposalExecutor
    private val sched = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(sched))
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun advanceUntilIdle() = scope.coroutineContext.job.children.toList().joinAll()

    private fun run(block: suspend TestScope.() -> Unit) = runTest(StandardTestDispatcher(sched)) { block() }

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // Base de datos en archivo, aislada por prueba: las transacciones concurrentes se serializan como en el teléfono.
        db = Room.databaseBuilder(ctx, BrujulaDatabase::class.java, File(tmp.root, "proposals.db").absolutePath)
            .allowMainThreadQueries().build()
        activities = ActivityRepository(db.activityDao())
        proposals = ProposalRepository(db.proposalDao())
        notes = ClaudeNotes(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "notes.preferences_pb") })
        googleSync = GoogleCopySync(writer, activities)
        executor = newExecutor()
    }

    @After fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private fun newExecutor() = ProposalExecutor(
        db, activities, proposals, googleSync, ReminderResync { log += "resync" }, notes, clock, scope,
        reminderScheduler = RecordingScheduler(log),
    )

    private fun span(day: LocalDate, h: Int, m: Int, minutes: Long) = day.atTime(h, m).let { TimeSpan(it, it.plusMinutes(minutes)) }

    private class Seeded(val bank: Long, val gym: Long, val read: Long, val hair: Long)

    /** Agenda del viernes: una de una sola vez con copia, dos recurrentes (una ya movida ese día) y una cita con copia. */
    private suspend fun seed(gymCopy: Boolean = false): Seeded {
        val bank = activities.upsert(
            Activity(
                title = "Llamar al banco", type = ActivityType.PERSONAL, date = fri, start = LocalTime.of(15, 0), durationMin = 60,
                remindBeforeMin = 15, copyToGoogle = true, origin = ActivityOrigin.DICTATION,
            ),
        )
        link(bank, 500, span(fri, 15, 0, 60))
        val gym = activities.upsert(
            Activity(
                title = "Gimnasio", type = ActivityType.EXERCISE, date = wed, start = LocalTime.of(18, 0), durationMin = 60,
                repeat = Repeat.DAILY, copyToGoogle = gymCopy,
            ),
        )
        activities.setOverride(OccurrenceOverride(gym, fri, fri.atTime(18, 30), 45, skipped = false))
        val read = activities.upsert(
            Activity(
                title = "Leer", type = ActivityType.PERSONAL, date = wed, start = LocalTime.of(21, 0), durationMin = 30,
                repeat = Repeat.DAILY, copyToGoogle = false,
            ),
        )
        val hair = activities.upsert(
            Activity(
                title = "Peluquería", type = ActivityType.APPOINTMENT, date = fri, start = LocalTime.of(12, 0), durationMin = 30,
                remindBeforeMin = 30, copyToGoogle = true, origin = ActivityOrigin.DICTATION,
            ),
        )
        link(hair, 501, span(fri, 12, 0, 30))
        // Un evento que no es copia de Brújula: nunca se toca.
        writer.events[900] = GoogleEventDraft("Reunión ajena", fri.atTime(8, 0), fri.atTime(9, 0), null, activityId = 9999)
        return Seeded(bank, gym, read, hair)
    }

    private suspend fun link(id: Long, eventId: Long, s: TimeSpan) {
        writer.events[eventId] = GoogleEventDraft("x", s.start, s.end, null, id)
        activities.setGoogleEventId(id, eventId)
    }

    private val deepWork = Activity(
        title = "Trabajo profundo", type = ActivityType.WORK, date = fri, start = LocalTime.of(9, 0), durationMin = 120,
        remindBeforeMin = null, copyToGoogle = false, origin = ActivityOrigin.PLAN,
    )

    private fun proposal(day: LocalDate, ops: List<PlanOp>, summary: String = "Te armé el viernes.") = Proposal(
        day = day, createdAt = clock.now(), request = null, summary = summary, ops = ops, rejected = emptyList(),
        preview = emptyList(), totals = PlanTotals(120, 45, 480, day.atTime(23, 0)),
    )

    private fun allOps(s: Seeded) = listOf(
        PlanOp.Create(deepWork),
        PlanOp.Move("A${s.bank}", s.bank, fri, "Llamar al banco", span(fri, 15, 0, 60), span(fri, 16, 0, 60), oneOff = true),
        PlanOp.Move("A${s.gym}", s.gym, fri, "Gimnasio", span(fri, 18, 30, 45), span(fri, 20, 0, 45), oneOff = false),
        PlanOp.Skip("A${s.read}", s.read, fri, "Leer", span(fri, 21, 0, 30), oneOff = false),
        PlanOp.Skip("A${s.hair}", s.hair, fri, "Peluquería", span(fri, 12, 0, 30), oneOff = true),
    )

    private suspend fun all() = activities.observeAll().first()
    private suspend fun overrides() = activities.observeOverrides().first()
    private suspend fun status(id: Long) = db.proposalDao().get(id)!!.status
    private suspend fun titled(t: String) = all().filter { it.title == t }

    @Test fun acceptAppliesAllOpsInOneTransaction() = run {
        val s = seed()
        val id = proposals.savePending(proposal(fri, allOps(s)))

        val r = executor.accept(id)
        assertTrue(r.toString(), r is AcceptResult.Accepted)
        assertEquals(ProposalStatus.ACCEPTED, (r as AcceptResult.Accepted).proposal.status)
        assertEquals("ACCEPTED", status(id))
        assertNotNull(db.proposalDao().get(id)!!.undo)
        assertEquals(clock.now(), db.proposalDao().get(id)!!.acceptedAt)
        assertEquals(1, resyncs)
        advanceUntilIdle()

        val created = titled("Trabajo profundo").single()
        assertEquals(deepWork.copy(id = created.id), created)
        val bank = activities.get(s.bank)!!
        assertEquals(LocalTime.of(16, 0), bank.start)
        assertEquals(fri, bank.date)
        assertEquals(60, bank.durationMin)
        assertEquals(500L, bank.googleEventId)
        assertEquals(OccurrenceOverride(s.gym, fri, fri.atTime(20, 0), 45, skipped = false), activities.getOverride(s.gym, fri))
        assertEquals(true, activities.getOverride(s.read, fri)!!.skipped)
        assertNull(activities.get(s.hair))
        // La serie no se edita (D3).
        assertEquals(LocalTime.of(18, 0), activities.get(s.gym)!!.start)
        assertEquals(LocalTime.of(21, 0), activities.get(s.read)!!.start)

        // Google: un update de la movida, un delete de la saltada, nada más y nada ajeno.
        assertEquals(1, writer.updates)
        assertEquals(1, writer.deletes)
        assertEquals(0, writer.inserts)
        assertEquals(fri.atTime(16, 0), writer.events[500]!!.start)
        assertNull(writer.events[501])
        assertTrue(writer.touched.toString(), writer.touched.all { it == 500L || it == 501L })
        assertNotNull(writer.events[900])

        assertEquals(ClaudeNote("Te armé el viernes.", fri, id), notes.note.first())
    }

    @Test fun failureMidTransactionChangesNothing() = run {
        val s = seed()
        val id = proposals.savePending(proposal(fri, allOps(s)))
        val before = all()
        val overridesBefore = overrides()
        executor.afterApply = { throw IllegalStateException("boom") }

        assertEquals(AcceptResult.Failed(AcceptResult.FAILED), executor.accept(id))
        advanceUntilIdle()
        assertEquals(before, all())
        assertEquals(overridesBefore, overrides())
        assertEquals("PENDING", status(id))
        assertNull(db.proposalDao().get(id)!!.undo)
        assertEquals(0, resyncs)
        assertTrue(cancels.isEmpty())
        assertTrue(writer.touched.isEmpty())
        assertNull(notes.note.first())
        assertEquals("No pude aplicar el plan. No cambié nada.", AcceptResult.FAILED)
    }

    @Test fun acceptRefusesStaleProposal() = run {
        val s = seed()
        val id = proposals.savePending(proposal(fri, allOps(s)))
        // El usuario movió la llamada después de recibir la propuesta.
        activities.upsert(activities.get(s.bank)!!.copy(start = LocalTime.of(17, 0)))
        val before = all()
        val overridesBefore = overrides()

        assertEquals(AcceptResult.Stale("Tu agenda cambió desde esta propuesta."), executor.accept(id))
        advanceUntilIdle()
        assertEquals(before, all())
        assertEquals(overridesBefore, overrides())
        assertEquals("PENDING", status(id))
        assertEquals(id, proposals.pendingFor(fri)!!.id)
        assertEquals(0, resyncs)
        assertTrue(writer.touched.isEmpty())
    }

    @Test fun acceptRefusesWhenAReferencedItemWasDeletedDoneOrItsOverrideChanged() = run {
        val s = seed()
        val skipHair = proposals.savePending(proposal(fri, listOf(PlanOp.Create(deepWork), allOps(s)[4])))
        activities.delete(s.hair)
        assertEquals(AcceptResult.Stale(AcceptResult.STALE), executor.accept(skipHair))

        val moveGym = proposals.savePending(proposal(fri, listOf(PlanOp.Create(deepWork), allOps(s)[2])))
        activities.setDone(s.gym, fri, true)
        assertEquals(AcceptResult.Stale(AcceptResult.STALE), executor.accept(moveGym))
        activities.setDone(s.gym, fri, false)
        // Sin el override previo, la ocurrencia vuelve a las 18:00 y ya no coincide con `from` (18:30).
        activities.clearOverride(s.gym, fri)
        assertEquals(AcceptResult.Stale(AcceptResult.STALE), executor.accept(moveGym))

        val skipRead = proposals.savePending(proposal(fri, listOf(PlanOp.Create(deepWork), allOps(s)[3])))
        // Se volvió de una sola vez: ya no es la misma clase de cambio.
        activities.upsert(activities.get(s.read)!!.copy(repeat = Repeat.NONE, date = fri))
        assertEquals(AcceptResult.Stale(AcceptResult.STALE), executor.accept(skipRead))

        assertTrue(titled("Trabajo profundo").isEmpty())
        assertEquals("PENDING", status(skipRead))
        assertEquals(0, resyncs)
    }

    @Test fun acceptRefusesRecurringWithGoogleCopy() = run {
        // D4: una recurrente con copia es fija; si llega aquí (copia activada después de proponer), no se toca.
        val s = seed(gymCopy = true)
        val id = proposals.savePending(proposal(fri, listOf(allOps(s)[2])))
        assertEquals(AcceptResult.Stale(AcceptResult.STALE), executor.accept(id))
        assertEquals(OccurrenceOverride(s.gym, fri, fri.atTime(18, 30), 45, skipped = false), activities.getOverride(s.gym, fri))
        assertEquals("PENDING", status(id))
    }

    @Test fun acceptRefusesPastOps() = run {
        clock.current = thu.atTime(14, 5)
        val createAt2 = proposals.savePending(
            proposal(thu, listOf(PlanOp.Create(deepWork.copy(date = thu, start = LocalTime.of(14, 0), durationMin = 60)))),
        )
        assertEquals(AcceptResult.Expired("Ya pasó parte de esta propuesta."), executor.accept(createAt2))
        assertEquals("PENDING", status(createAt2))
        assertTrue(titled("Trabajo profundo").isEmpty())

        // Un bloque de hoy que empezó a las 14:00 (después de proponer) ya no se mueve ni se quita.
        val walk = activities.upsert(
            Activity(title = "Caminar", type = ActivityType.EXERCISE, date = thu, start = LocalTime.of(14, 0), durationMin = 30),
        )
        val moveStarted = proposals.savePending(
            proposal(thu, listOf(PlanOp.Move("A$walk", walk, thu, "Caminar", span(thu, 14, 0, 30), span(thu, 17, 0, 30), oneOff = true))),
        )
        assertEquals(AcceptResult.Expired(AcceptResult.EXPIRED), executor.accept(moveStarted))
        assertEquals(LocalTime.of(14, 0), activities.get(walk)!!.start)

        // Moverlo a una hora que ya pasó, tampoco.
        val moveToPast = proposals.savePending(
            proposal(thu, listOf(PlanOp.Move("A$walk", walk, thu, "Caminar", span(thu, 14, 0, 30), span(thu, 13, 0, 30), oneOff = true))),
        )
        clock.current = thu.atTime(13, 30)
        assertEquals(AcceptResult.Expired(AcceptResult.EXPIRED), executor.accept(moveToPast))
        assertEquals(0, resyncs)
    }

    @Test fun acceptRefusesPastDay() = run {
        val id = proposals.savePending(
            proposal(wed, listOf(PlanOp.Create(deepWork.copy(date = wed, start = LocalTime.of(20, 0)))), summary = "miércoles"),
        )
        assertEquals(AcceptResult.Expired(AcceptResult.EXPIRED), executor.accept(id))
        assertEquals("PENDING", status(id))
        assertTrue(all().isEmpty())
        assertNull(notes.note.first())
    }

    @Test fun concurrentAcceptAppliesOnce() = runBlocking {
        val s = seed()
        val id = proposals.savePending(proposal(fri, allOps(s)))
        executor.afterApply = { delay(50) }
        val results = withContext(Dispatchers.Default) { List(2) { async { executor.accept(id) } }.awaitAll() }
        assertEquals(results.toString(), 1, results.count { it is AcceptResult.Accepted })
        assertEquals(results.toString(), 1, results.count { it == AcceptResult.AlreadyAccepted })
        assertEquals(1, titled("Trabajo profundo").size)
        assertEquals(1, resyncs)
    }

    @Test fun concurrentAcceptFromTwoExecutorsStillAppliesOnce() = runBlocking {
        // Sin Mutex compartido, la revisión de PENDING dentro de la transacción basta.
        val s = seed()
        val id = proposals.savePending(proposal(fri, allOps(s)))
        val a = newExecutor().apply { afterApply = { delay(50) } }
        val b = newExecutor().apply { afterApply = { delay(50) } }
        val results = withContext(Dispatchers.Default) { listOf(async { a.accept(id) }, async { b.accept(id) }).awaitAll() }
        assertEquals(results.toString(), 1, results.count { it is AcceptResult.Accepted })
        assertEquals(results.toString(), 1, results.count { it == AcceptResult.AlreadyAccepted })
        assertEquals(1, titled("Trabajo profundo").size)
    }

    @Test fun undoRestoresExactly() = run {
        val s = seed()
        val hairBefore = activities.get(s.hair)!!
        val id = proposals.savePending(proposal(fri, allOps(s)))
        executor.accept(id)
        advanceUntilIdle()

        assertEquals(UndoResult.Undone(0), executor.undo(id))
        advanceUntilIdle()

        assertTrue(titled("Trabajo profundo").isEmpty())
        val bank = activities.get(s.bank)!!
        assertEquals(LocalTime.of(15, 0), bank.start)
        assertEquals(500L, bank.googleEventId)
        assertEquals(fri.atTime(15, 0), writer.events[500]!!.start) // afterSave con la hora vieja
        assertEquals(OccurrenceOverride(s.gym, fri, fri.atTime(18, 30), 45, skipped = false), activities.getOverride(s.gym, fri))
        assertNull(activities.getOverride(s.read, fri))
        val hair = activities.get(s.hair)!!
        assertEquals(hairBefore.copy(googleEventId = hair.googleEventId), hair)
        assertEquals("PENDING", status(id))
        assertNull(db.proposalDao().get(id)!!.undo)
        assertNull(db.proposalDao().get(id)!!.acceptedAt)
        assertEquals(id, proposals.pendingFor(fri)!!.id)
        assertEquals(2, resyncs)
        assertEquals(ClaudeNote("Deshice los cambios del plan.", fri, null), notes.note.first())
        assertTrue(writer.touched.toString(), writer.touched.all { it == 500L || it == 501L })
        assertNotNull(writer.events[900])
    }

    @Test fun undoRecreatesSkippedOneOffWithItsGoogleLinkAndCopy() = run {
        val s = seed()
        val before = activities.get(s.hair)!!
        val id = proposals.savePending(proposal(fri, listOf(allOps(s)[4])))
        executor.accept(id)
        advanceUntilIdle()
        assertNull(writer.events[501])

        assertEquals(UndoResult.Undone(0), executor.undo(id))
        advanceUntilIdle()
        val hair = activities.get(s.hair)!!
        // Mismo id (las marcas de hecho y los vínculos siguen valiendo) y todos sus datos; la copia se vuelve a crear.
        assertEquals(before.copy(googleEventId = hair.googleEventId), hair)
        assertEquals(ActivityOrigin.DICTATION, hair.origin)
        val newEvent = hair.googleEventId!!
        assertTrue(newEvent != 501L)
        assertEquals(GoogleEventDraft("Peluquería", fri.atTime(12, 0), fri.atTime(12, 30), null, s.hair), writer.events[newEvent])
        assertEquals(1, writer.events.values.count { it.activityId == s.hair })
    }

    @Test fun undoOfRecurringSkipRestoresThePreexistingOverrideValues() = run {
        val s = seed()
        val id = proposals.savePending(
            proposal(fri, listOf(PlanOp.Skip("A${s.gym}", s.gym, fri, "Gimnasio", span(fri, 18, 30, 45), oneOff = false))),
        )
        executor.accept(id)
        assertEquals(true, activities.getOverride(s.gym, fri)!!.skipped)
        assertEquals(UndoResult.Undone(0), executor.undo(id))
        assertEquals(OccurrenceOverride(s.gym, fri, fri.atTime(18, 30), 45, skipped = false), activities.getOverride(s.gym, fri))
    }

    @Test fun skipClearsPendingSnoozeBeforeTheResync() = run {
        val s = seed()
        val id = proposals.savePending(proposal(fri, allOps(s)))
        executor.accept(id)
        val expected = listOf(s.bank, s.gym, s.read, s.hair).map { "act:$it:20261120:s" }
        assertEquals(expected.toSet(), cancels.toSet())
        // Se cancelan antes del resync: el resync no las vuelve a armar.
        assertEquals("resync", log.last())
    }

    @Test fun undoKeepsItemsEditedAfterAccept() = run {
        val s = seed()
        val id = proposals.savePending(proposal(fri, allOps(s)))
        executor.accept(id)
        advanceUntilIdle()
        activities.upsert(activities.get(s.bank)!!.copy(start = LocalTime.of(17, 0)))

        val r = executor.undo(id)
        assertEquals(UndoResult.Undone(kept = 1), r)
        assertEquals("Dejé 1 cambio que hiciste después.", UndoResult.keptMessage(1))
        assertEquals("Dejé 2 cambios que hiciste después.", UndoResult.keptMessage(2))
        assertNull(UndoResult.keptMessage(0))
        advanceUntilIdle()
        assertEquals(LocalTime.of(17, 0), activities.get(s.bank)!!.start)
        assertEquals(500L, activities.get(s.bank)!!.googleEventId)
        // Lo demás sí se deshizo.
        assertTrue(titled("Trabajo profundo").isEmpty())
        assertEquals(OccurrenceOverride(s.gym, fri, fri.atTime(18, 30), 45, skipped = false), activities.getOverride(s.gym, fri))
        assertNull(activities.getOverride(s.read, fri))
        assertNotNull(activities.get(s.hair))
        assertEquals("PENDING", status(id))
    }

    @Test fun undoKeepsEditedCreatedBlocksAndOverrides() = run {
        val s = seed()
        val id = proposals.savePending(proposal(fri, allOps(s)))
        executor.accept(id)
        val created = titled("Trabajo profundo").single()
        activities.upsert(created.copy(start = LocalTime.of(10, 0)))
        activities.setOverride(OccurrenceOverride(s.gym, fri, fri.atTime(21, 0), 45, skipped = false))
        assertEquals(UndoResult.Undone(kept = 2), executor.undo(id))
        assertEquals(LocalTime.of(10, 0), activities.get(created.id)!!.start)
        assertEquals(fri.atTime(21, 0), activities.getOverride(s.gym, fri)!!.start)
    }

    @Test fun acceptUndoAcceptAgain() = run {
        val s = seed()
        val id = proposals.savePending(proposal(fri, allOps(s)))
        assertTrue(executor.accept(id) is AcceptResult.Accepted)
        advanceUntilIdle()
        assertEquals(UndoResult.Undone(0), executor.undo(id))
        advanceUntilIdle()
        assertTrue(executor.accept(id) is AcceptResult.Accepted)
        advanceUntilIdle()

        assertEquals("ACCEPTED", status(id))
        assertEquals(1, titled("Trabajo profundo").size)
        assertEquals(LocalTime.of(16, 0), activities.get(s.bank)!!.start)
        assertEquals(fri.atTime(16, 0), writer.events[500]!!.start)
        assertNull(activities.get(s.hair))
        assertTrue(writer.events.values.none { it.activityId == s.hair })
        assertEquals(true, activities.getOverride(s.read, fri)!!.skipped)
        assertEquals(ClaudeNote("Te armé el viernes.", fri, id), notes.note.first())
        assertEquals(AcceptResult.AlreadyAccepted, executor.accept(id))

        assertEquals(UndoResult.Undone(0), executor.undo(id))
        advanceUntilIdle()
        assertTrue(titled("Trabajo profundo").isEmpty())
        assertNotNull(activities.get(s.hair))
        assertEquals(UndoResult.NotAccepted, executor.undo(id))
    }

    @Test fun undoNeedsAnAcceptedProposalWhoseDayHasNotPassed() = run {
        val s = seed()
        val id = proposals.savePending(proposal(fri, allOps(s)))
        assertEquals(UndoResult.NotAccepted, executor.undo(id))
        assertEquals(UndoResult.NotAccepted, executor.undo(id + 100))
        executor.accept(id)
        val after = all()
        clock.current = sat.atTime(5, 0)
        assertEquals(UndoResult.Failed(UndoResult.PAST), executor.undo(id))
        assertEquals(after, all())
        assertEquals("ACCEPTED", status(id))
    }

    @Test fun undoMakesTheUndoneProposalThePendingOneForItsDay() = run {
        val s = seed()
        val first = proposals.savePending(proposal(fri, allOps(s)))
        executor.accept(first)
        val newer = proposals.savePending(proposal(fri, listOf(PlanOp.Create(deepWork.copy(start = LocalTime.of(7, 0)))), "otra"))
        executor.undo(first)
        assertEquals(first, proposals.pendingFor(fri)!!.id)
        assertEquals("SUPERSEDED", status(newer))
    }

    @Test fun acceptOfUnknownOrUnreadableProposalFails() = run {
        assertEquals(AcceptResult.Failed(AcceptResult.FAILED), executor.accept(12345))
    }

    @Test fun createdBlocksAlwaysGetPlanDefaults() = run {
        // D11 se impone al aplicar aunque el payload trajera otra cosa.
        val odd = deepWork.copy(type = ActivityType.EXERCISE, copyToGoogle = true, remindBeforeMin = null, origin = ActivityOrigin.UNKNOWN, repeat = Repeat.DAILY)
        val id = proposals.savePending(proposal(fri, listOf(PlanOp.Create(odd), PlanOp.Create(deepWork.copy(start = LocalTime.of(14, 0))))))
        executor.accept(id)
        advanceUntilIdle()
        val exercise = all().single { it.type == ActivityType.EXERCISE }
        assertEquals(false, exercise.copyToGoogle)
        assertEquals(15, exercise.remindBeforeMin)
        assertEquals(ActivityOrigin.PLAN, exercise.origin)
        assertEquals(Repeat.NONE, exercise.repeat)
        assertNull(all().single { it.type == ActivityType.WORK }.remindBeforeMin)
        assertEquals(0, writer.inserts)
    }
}
