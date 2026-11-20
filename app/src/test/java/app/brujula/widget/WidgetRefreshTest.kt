package app.brujula.widget

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.ActivityRepository
import app.brujula.calendar.WorkClassificationRepository
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
import kotlinx.coroutines.flow.MutableSharedFlow
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class WidgetRefreshTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: BrujulaDatabase
    private lateinit var habits: HabitRepository
    private val scope = CoroutineScope(Job() + Dispatchers.Default)
    private val calls = AtomicInteger()

    @Before fun setUp() {
        db = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java, File(tmp.root, "w.db").path).build()
        habits = HabitRepository(db.habitDao())
    }

    @After fun tearDown() { scope.cancel(); db.close() }

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

    @Test fun changesSkippedWithoutWidgets() {
        val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        startWidgetRefresh(scope, flow, hasWidgets = { false }, update = { calls.incrementAndGet() }, debounceMs = 50)
        Thread.sleep(200)
        runBlocking { flow.emit(Unit) }
        Thread.sleep(600)
        assertEquals(0, calls.get())
    }

    @Test fun habitLogChangeEmits() {
        val id = runBlocking {
            habits.upsert(Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8))
        }
        val routine = RoutineRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "r.preferences_pb") })
        val changes = widgetChanges(routine, ActivityRepository(db.activityDao()), habits, WorkClassificationRepository(db.workClassificationDao()))
        startWidgetRefresh(scope, changes, hasWidgets = { true }, update = { calls.incrementAndGet() }, debounceMs = 100)
        val before = settle()
        assertTrue("debe emitir al arrancar", before >= 1)
        runBlocking { habits.logDone(id, LocalDateTime.of(2026, 11, 18, 12, 0)) }
        val after = settle()
        assertTrue("sin actualización tras registrar ($before -> $after)", after > before)
    }

    @Test fun burstDebouncedToOneUpdate() {
        val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        startWidgetRefresh(scope, flow, hasWidgets = { true }, update = { calls.incrementAndGet() }, debounceMs = 300)
        Thread.sleep(200)
        runBlocking { repeat(5) { flow.emit(Unit); Thread.sleep(30) } }
        Thread.sleep(1200)
        assertEquals(1, calls.get())
    }

    @Test fun errorKeepsCollecting() {
        val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        startWidgetRefresh(scope, flow, hasWidgets = { true }, update = { if (calls.incrementAndGet() == 1) throw IllegalStateException("boom") }, debounceMs = 50)
        Thread.sleep(200)
        runBlocking { flow.emit(Unit) }
        Thread.sleep(600)
        runBlocking { flow.emit(Unit) }
        Thread.sleep(600)
        assertEquals(2, calls.get())
    }
}
