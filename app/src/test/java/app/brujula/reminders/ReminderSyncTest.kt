package app.brujula.reminders

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.ActivityRepository
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

private class RecordingScheduler(private val holdMs: Long = 0) : ReminderScheduler {
    @Volatile var last: List<Reminder> = emptyList()
    private val inflight = AtomicInteger()
    val maxInflight = AtomicInteger()
    override fun replaceAll(reminders: List<Reminder>) {
        maxInflight.accumulateAndGet(inflight.incrementAndGet()) { a, b -> maxOf(a, b) }
        if (holdMs > 0) Thread.sleep(holdMs)
        last = reminders
        inflight.decrementAndGet()
    }
    override fun scheduleOne(reminder: Reminder) = Unit
}

@RunWith(RobolectricTestRunner::class)
class ReminderSyncTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: BrujulaDatabase
    private lateinit var habits: HabitRepository
    private lateinit var activities: ActivityRepository
    private lateinit var routine: RoutineRepository
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private val day = LocalDate.of(2026, 11, 18)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        habits = HabitRepository(db.habitDao())
        activities = ActivityRepository(db.activityDao())
        routine = RoutineRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "r.preferences_pb") })
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun sync(now: LocalDateTime, scheduler: ReminderScheduler) =
        ReminderSync(FixedClock(now), routine, activities, habits, scheduler)

    @Test fun at0020YesterdaysCompleteWaterIsNotPlannedAndCountsGlueAcrossDays() = runBlocking {
        // Cada 5 min: la vigilia de ayer (hasta 00:30) aun tendria un aviso a las 00:25 si no estuviera completa.
        val water = habits.upsert(Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(5), dailyTarget = 8))
        repeat(8) { habits.logDone(water, day.atTime(10 + it, 0)) }
        // 3 vasos ya registrados para el dia logico de hoy (19)
        repeat(3) { habits.logDone(water, day.plusDays(1).atTime(9 + it, 0)) }
        val scheduler = RecordingScheduler()
        sync(day.plusDays(1).atTime(0, 20), scheduler).resync()

        val planned = scheduler.last
        assertTrue(planned.isNotEmpty())
        assertTrue("sin avisos para la vigilia de ayer", planned.none { it.day == day })
        assertEquals(day.plusDays(1).atTime(8, 20), planned.first().at)
        assertTrue(planned.all { it.day == day.plusDays(1) })
        assertEquals("Vas 3 de 8 vasos.", planned.first().body)
    }

    @Test fun at0020YesterdaysIncompleteWaterStillRemindsBeforeBed() = runBlocking {
        val water = habits.upsert(Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(5), dailyTarget = 8))
        habits.logDone(water, day.atTime(10, 0))
        val scheduler = RecordingScheduler()
        sync(day.plusDays(1).atTime(0, 20), scheduler).resync()
        val first = scheduler.last.first()
        assertEquals(day, first.day)
        assertEquals(day.plusDays(1).atTime(0, 25), first.at)
    }

    @Test fun resyncsAreSerialized() = runBlocking {
        habits.upsert(Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8))
        val scheduler = RecordingScheduler(holdMs = 40)
        val s = sync(day.atTime(12, 0), scheduler)
        (1..6).map { async(Dispatchers.Default) { s.resync() } }.awaitAll()
        assertEquals(1, scheduler.maxInflight.get())
    }
}
