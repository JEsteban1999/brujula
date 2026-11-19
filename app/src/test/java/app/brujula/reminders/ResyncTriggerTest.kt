package app.brujula.reminders

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.core.time.FixedClock
import app.brujula.data.db.BrujulaDatabase
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitRepository
import app.brujula.habits.HabitSchedule
import app.brujula.routine.RoutineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class ResyncTriggerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: BrujulaDatabase
    private lateinit var habits: HabitRepository
    private lateinit var activities: ActivityRepository
    private lateinit var routine: RoutineRepository
    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val now = LocalDateTime.of(2026, 11, 18, 12, 0)
    private val calls = AtomicInteger()
    private val sync = ReminderResync { calls.incrementAndGet() }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        habits = HabitRepository(db.habitDao())
        activities = ActivityRepository(db.activityDao())
        routine = RoutineRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "r.preferences_pb") })
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun water() = Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8)

    private fun start() = startReminderResync(
        scope, reminderChanges(routine, activities, habits, FixedClock(now)), sync, debounceMs = 100,
    )

    /** Espera a que no haya llamadas nuevas durante un rato (el debounce ya vacio). */
    private fun settle(): Int {
        var last = -1
        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end) {
            Thread.sleep(400)
            val c = calls.get()
            if (c == last && c > 0) return c
            last = c
        }
        return calls.get()
    }

    private fun assertTriggers(change: suspend () -> Unit) {
        start()
        val before = settle()
        runBlocking { change() }
        val after = settle()
        assertTrue("sin resync tras el cambio ($before -> $after)", after > before)
    }

    @Test fun resyncsOnStart() {
        start()
        assertTrue(settle() >= 1)
    }

    @Test fun habitLogTriggersResync() {
        val id = runBlocking { habits.upsert(water()) }
        assertTriggers { habits.logDone(id, now) }
    }

    @Test fun newHabitTriggersResync() {
        assertTriggers { habits.upsert(water()) }
    }

    @Test fun activityDoneTriggersResync() {
        val id = runBlocking {
            activities.upsert(Activity(title = "Ejercicio", type = ActivityType.EXERCISE, date = now.toLocalDate(), start = LocalTime.of(18, 30), durationMin = 30))
        }
        assertTriggers { activities.setDone(id, now.toLocalDate(), true) }
    }

    @Test fun routineChangeTriggersResync() {
        assertTriggers { routine.update { it.copy(bed = LocalTime.of(23, 0)) } }
    }

    @Test fun burstOfChangesIsDebounced() {
        val id = runBlocking { habits.upsert(water()) }
        start()
        val before = settle()
        runBlocking { repeat(5) { habits.logDone(id, now.plusMinutes(it.toLong())) } }
        val after = settle()
        assertTrue("debounce: ${after - before} resyncs para 5 cambios", after - before in 1..2)
    }

    @Test fun upstreamErrorIsLoggedAndCollectorRestarts() {
        val attempts = AtomicInteger()
        val flaky: Flow<Unit> = flow {
            if (attempts.incrementAndGet() == 1) throw IllegalStateException("room cerrado")
            emit(Unit)
        }
        startReminderResync(scope, flaky, sync, debounceMs = 10, retryDelayMs = 10)
        val end = System.currentTimeMillis() + 5000
        while (calls.get() < 1 && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(1, calls.get())
        assertEquals(2, attempts.get())
    }

    @Test fun failingResyncDoesNotKillCollector() {
        val n = AtomicInteger()
        val failingFirst = ReminderResync { if (n.incrementAndGet() == 1) throw IllegalStateException("boom") }
        val id = runBlocking { habits.upsert(water()) }
        startReminderResync(scope, reminderChanges(routine, activities, habits, FixedClock(now)), failingFirst, debounceMs = 50)
        val end0 = System.currentTimeMillis() + 5000
        while (n.get() < 1 && System.currentTimeMillis() < end0) Thread.sleep(20)
        runBlocking { habits.logDone(id, now) }
        val end = System.currentTimeMillis() + 5000
        while (n.get() < 2 && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(n.get() >= 2)
    }
}
