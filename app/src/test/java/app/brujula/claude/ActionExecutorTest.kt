package app.brujula.claude

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.calendar.FakeGoogleCalendarWriter
import app.brujula.calendar.GoogleCopySync
import app.brujula.data.db.BrujulaDatabase
import app.brujula.habits.Habit
import app.brujula.habits.HabitGroup
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitRepository
import app.brujula.habits.HabitSchedule
import app.brujula.reminders.ReminderResync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(application = app.brujula.TestBrujulaApp::class)
class ActionExecutorTest {
    private lateinit var db: BrujulaDatabase
    private lateinit var activities: ActivityRepository
    private lateinit var habits: HabitRepository
    private val writer = FakeGoogleCalendarWriter()
    private var resyncs = 0
    private var resyncThrows = false
    private lateinit var executor: ActionExecutor
    private val sched = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(sched))

    private suspend fun advanceUntilIdle() = scope.coroutineContext.job.children.toList().joinAll()

    private fun run(block: suspend TestScope.() -> Unit) = runTest(StandardTestDispatcher(sched)) { block() }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        activities = ActivityRepository(db.activityDao())
        habits = HabitRepository(db.habitDao())
        executor = ActionExecutor(db, activities, habits, GoogleCopySync(writer, activities), ReminderResync { resyncs++; if (resyncThrows) throw IllegalStateException("boom") }, scope)
    }

    @After fun tearDown() { db.close() }

    private val exercise = Activity(
        title = "Ejercicio", type = ActivityType.EXERCISE, date = LocalDate.of(2026, 11, 19),
        start = LocalTime.of(19, 0), durationMin = 60,
    )
    private val dentist = Activity(
        title = "Dentista", type = ActivityType.APPOINTMENT, date = LocalDate.of(2026, 11, 20),
        start = LocalTime.of(16, 45), durationMin = 30, copyToGoogle = true,
    )
    private val stretch = Habit(
        name = "Estirarse", icon = HabitIcon.STRETCH, schedule = HabitSchedule.Every(120), dailyTarget = 6,
        group = HabitGroup.ESSENTIAL,
    )

    @Test fun appliesActivityAndHabitWithSpanishLabelsAndOneResync() = run {
        val r = executor.apply(listOf(Validated.Activity(exercise), Validated.Habit(stretch)))
        assertEquals(
            listOf("Ejercicio · jue 19 nov · 19:00–20:00", "Hábito · Estirarse · cada 2 h"),
            r.created.map { it.label },
        )
        assertEquals(1, resyncs)
        assertNotNull(activities.get(r.created[0].activityId!!))
        assertEquals(1, habits.count())
        assertEquals(listOf(r.created[0].activityId!!), r.undo!!.activityIds)
        assertEquals(listOf(r.created[1].habitId!!), r.undo!!.habitIds)
    }

    @Test fun throwingResyncAfterCommitStillReturnsCreatedAndTokenAndLaunchesTheGoogleCopy() = run {
        resyncThrows = true
        val r = executor.apply(listOf(Validated.Activity(dentist), Validated.Habit(stretch)))
        advanceUntilIdle()
        assertEquals(2, r.created.size)
        assertNotNull(r.undo)
        assertEquals(1, activities.count())
        assertEquals(1, habits.count())
        assertEquals(1, writer.events.size)
    }

    @Test fun undoWithThrowingResyncStillDeletesRowsAndDoesNotThrow() = run {
        val r = executor.apply(listOf(Validated.Activity(dentist), Validated.Habit(stretch)))
        advanceUntilIdle()
        resyncThrows = true
        executor.undo(r.undo!!)
        assertEquals(0, activities.count())
        assertEquals(0, habits.count())
        assertTrue(writer.events.isEmpty())
    }

    @Test fun habitLabelsForOtherModes() = run {
        val at = stretch.copy(schedule = HabitSchedule.AtTimes(listOf(LocalTime.of(9, 0), LocalTime.of(14, 30))))
        val bed = stretch.copy(name = "Leer", schedule = HabitSchedule.BeforeBed(30))
        val r = executor.apply(listOf(Validated.Habit(at), Validated.Habit(bed)))
        assertEquals(listOf("Hábito · Estirarse · a las 9:00, 14:30", "Hábito · Leer · 30 min antes de dormir"), r.created.map { it.label })
    }

    @Test fun appointmentIsCopiedToGoogleAndUndoRemovesRowsAndCopy() = run {
        val r = executor.apply(listOf(Validated.Activity(dentist), Validated.Habit(stretch)))
        advanceUntilIdle()
        assertEquals(1, writer.events.size)
        executor.undo(r.undo!!)
        assertTrue(activities.observeAll().first().isEmpty())
        assertEquals(0, habits.count())
        assertTrue(writer.events.isEmpty())
        assertEquals(2, resyncs)
    }

    @Test fun undoIsIdempotentAndLeavesOtherRowsAlone() = run {
        val other = activities.upsert(exercise.copy(title = "Otra"))
        val r = executor.apply(listOf(Validated.Activity(dentist)))
        executor.undo(r.undo!!)
        executor.undo(r.undo!!)
        assertEquals(listOf("Otra"), activities.observeAll().first().map { it.title })
        assertNotNull(activities.get(other))
        assertTrue(writer.events.isEmpty())
    }

    @Test fun nonActionItemsCreateNothingAndPassMessages() = run {
        val r = executor.apply(listOf(Validated.Question("¿A qué hora?"), Validated.Unsupported("No puedo mover cosas."), Validated.Invalid("«X»: fecha ya pasó.")))
        assertEquals(listOf("¿A qué hora?", "No puedo mover cosas.", "«X»: fecha ya pasó."), r.messages)
        assertTrue(r.created.isEmpty())
        assertNull(r.undo)
        assertEquals(0, resyncs)
        assertEquals(0, activities.count())
        assertEquals(0, habits.count())
    }

    @Test fun mixedBatchKeepsMessagesAndCreatesValidOnes() = run {
        val r = executor.apply(listOf(Validated.Invalid("mal"), Validated.Activity(exercise)))
        assertEquals(listOf("mal"), r.messages)
        assertEquals(1, r.created.size)
    }

    @Test fun failureRollsBackEverything() = run {
        var n = 0
        executor.afterInsert = { if (++n == 2) error("boom") }
        val r = executor.apply(listOf(Validated.Activity(exercise), Validated.Activity(dentist), Validated.Habit(stretch)))
        assertTrue(r.created.isEmpty())
        assertNull(r.undo)
        assertTrue(r.messages.isNotEmpty())
        assertEquals(0, activities.count())
        assertEquals(0, habits.count())
        assertTrue(writer.events.isEmpty())
        assertEquals(0, resyncs)
    }

    @Test fun repeatingActivityLabelMentionsRepeat() = run {
        val r = executor.apply(listOf(Validated.Activity(exercise.copy(repeat = Repeat.DAILY))))
        assertEquals("Ejercicio · jue 19 nov · 19:00–20:00 · cada día", r.created[0].label)
    }

    @Test fun applyReturnsBeforeSlowGoogleCopyThenUndoRemovesIt() = run {
        writer.insertDelayMs = 5_000
        val r = executor.apply(listOf(Validated.Activity(dentist)))
        assertNotNull(r.undo)
        assertEquals(1, resyncs)
        assertTrue(writer.events.isEmpty())
        advanceUntilIdle()
        assertEquals(1, writer.events.size)
        executor.undo(r.undo!!)
        assertTrue(writer.events.isEmpty())
    }

    @Test fun undoTwiceStillSweepsCopyLinkedAfterApplyReturned() = run {
        val r = executor.apply(listOf(Validated.Activity(dentist)))
        val id = r.undo!!.activityIds.single()
        executor.undo(r.undo!!)
        // Una copia que aparece después de borrar la fila (terminó tarde) la barre el reintento.
        writer.events[500L] = app.brujula.calendar.GoogleEventDraft(
            activityId = id, title = "Dentista", start = java.time.LocalDateTime.of(2026, 11, 20, 16, 45),
            end = java.time.LocalDateTime.of(2026, 11, 20, 17, 15), rrule = null,
        )
        executor.undo(r.undo!!)
        assertTrue(writer.events.isEmpty())
    }

    @Test fun undoAfterLogRemovesHabitAndItsLogs() = run {
        val r = executor.apply(listOf(Validated.Habit(stretch)))
        val hid = r.undo!!.habitIds.single()
        habits.logDone(hid, java.time.LocalDateTime.of(2026, 11, 19, 10, 0))
        executor.undo(r.undo!!)
        assertEquals(0, habits.count())
        assertEquals(0, db.query("SELECT COUNT(*) FROM habit_logs", null).use { it.moveToFirst(); it.getInt(0) })
    }
}
