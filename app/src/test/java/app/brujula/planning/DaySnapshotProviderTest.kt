package app.brujula.planning

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.calendar.CalendarSource
import app.brujula.calendar.DeviceCalendar
import app.brujula.calendar.ExternalEvent
import app.brujula.calendar.FakeCalendarSource
import app.brujula.calendar.WorkClassificationRepository
import app.brujula.calendar.classificationKey
import app.brujula.core.time.TimeSpan
import app.brujula.data.db.BrujulaDatabase
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitRepository
import app.brujula.habits.HabitSchedule
import app.brujula.routine.RoutineRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class DaySnapshotProviderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: BrujulaDatabase
    private lateinit var activities: ActivityRepository
    private lateinit var habits: HabitRepository
    private lateinit var marks: WorkClassificationRepository
    private val thu = LocalDate.of(2026, 11, 19)
    private val calendar = FakeCalendarSource()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        activities = ActivityRepository(db.activityDao())
        habits = HabitRepository(db.habitDao())
        marks = WorkClassificationRepository(db.workClassificationDao())
    }

    @After fun tearDown() { db.close() }

    private fun ext(id: Long, title: String, s: LocalDateTime, e: LocalDateTime, allDay: Boolean = false) =
        ExternalEvent(id, 0, 1, "Trabajo", "Outlook", title, TimeSpan(s, e), allDay, null)

    private suspend fun TestScope.provider(source: CalendarSource = calendar): DaySnapshotProvider {
        val routine = RoutineRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "r.preferences_pb") })
        routine.calendarPrefs.setSelectedCalendarIds(setOf(1L))
        return DaySnapshotProvider(routine, activities, habits, source, marks)
    }

    @Test fun workAddsOwnWorkAndExternalMarkedAsWork() = runTest {
        activities.upsert(Activity(title = "Informe", type = ActivityType.WORK, date = thu, start = LocalTime.of(9, 0), durationMin = 120))
        activities.upsert(Activity(title = "Mercado", type = ActivityType.PERSONAL, date = thu, start = LocalTime.of(19, 0), durationMin = 60))
        val daily = ext(1, "Daily", thu.atTime(14, 0), thu.atTime(15, 0))
        calendar.eventList = listOf(
            daily,
            ext(2, "Cumpleaños de Ana", thu.atTime(20, 0), thu.atTime(21, 0)),
            ext(3, "Vacaciones", thu.atStartOfDay(), thu.plusDays(1).atStartOfDay(), allDay = true),
        )
        marks.set(classificationKey(daily), true)

        val snap = provider().snapshot(thu)

        assertEquals(180, snap.summary.workMinutes)
        assertEquals(360..420, snap.summary.workGoal)
        assertEquals(listOf("Daily" to true, "Cumpleaños de Ana" to false), snap.external.map { it.event.title to it.countsAsWork })
        assertEquals(listOf("Informe", "Mercado"), snap.own.map { it.activity.title })
        assertEquals(thu, snap.day)
    }

    @Test fun brujulaCopiesAreNotExternal() = runTest {
        val id = activities.upsert(Activity(title = "Dentista", type = ActivityType.APPOINTMENT, date = thu, start = LocalTime.of(15, 0), durationMin = 60))
        activities.setGoogleEventId(id, 99L)
        calendar.eventList = listOf(ext(99, "Dentista", thu.atTime(15, 0), thu.atTime(16, 0)))
        val snap = provider().snapshot(thu)
        assertTrue(snap.external.isEmpty())
        assertEquals(listOf("Dentista"), snap.own.map { it.activity.title })
    }

    @Test fun waterComesFromTheFirstActiveWaterHabit() = runTest {
        habits.upsert(Habit(name = "Agua vieja", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(60), dailyTarget = 12, active = false))
        val water = habits.upsert(Habit(name = "Tomar agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(60), dailyTarget = 8))
        repeat(3) { habits.logDone(water, thu.atTime(9 + it, 0)) }
        habits.logDone(water, thu.minusDays(1).atTime(10, 0))

        val summary = provider().snapshot(thu).summary

        assertEquals(3, summary.waterCount)
        assertEquals(8, summary.waterTarget)
    }

    @Test fun withoutWaterHabitWaterIsNull() = runTest {
        val summary = provider().snapshot(thu).summary
        assertNull(summary.waterCount)
        assertNull(summary.waterTarget)
    }

    @Test fun exerciseDonePendingOrNone() = runTest {
        val sat = LocalDate.of(2026, 11, 21)
        val fri = thu.plusDays(1)
        val gym = activities.upsert(Activity(title = "Gimnasio", type = ActivityType.EXERCISE, date = thu, start = LocalTime.of(18, 30), durationMin = 60))
        activities.upsert(Activity(title = "Correr", type = ActivityType.EXERCISE, date = fri, start = LocalTime.of(7, 0), durationMin = 45))
        activities.setDone(gym, thu, true)
        val p = provider()

        assertEquals(ExerciseState.DONE, p.snapshot(thu).summary.exercise)
        assertEquals(ExerciseState.PENDING, p.snapshot(fri).summary.exercise)
        assertEquals(ExerciseState.NONE, p.snapshot(sat).summary.exercise)
        assertNull("Sin meta en descanso", p.snapshot(sat).summary.workGoal)
    }

    @Test fun whenTheCalendarThrowsThereAreNoExternals() = runTest {
        activities.upsert(Activity(title = "Informe", type = ActivityType.WORK, date = thu, start = LocalTime.of(9, 0), durationMin = 60))
        val broken = object : CalendarSource {
            override fun hasReadPermission() = true
            override suspend fun calendars() = emptyList<DeviceCalendar>()
            override suspend fun events(calendarIds: Set<Long>, from: LocalDateTime, to: LocalDateTime): List<ExternalEvent> =
                throw SecurityException("permiso revocado")
        }
        val snap = provider(broken).snapshot(thu)
        assertTrue(snap.external.isEmpty())
        assertEquals(60, snap.summary.workMinutes)
        assertEquals(listOf("Informe"), snap.own.map { it.activity.title })
    }

    @Test fun buildDaySnapshotMatchesProvider() = runTest {
        val daily = ext(1, "Daily", thu.atTime(14, 0), thu.atTime(15, 0))
        activities.upsert(Activity(title = "Informe", type = ActivityType.WORK, date = thu, start = LocalTime.of(9, 0), durationMin = 120))
        activities.upsert(Activity(title = "Gimnasio", type = ActivityType.EXERCISE, date = thu, start = LocalTime.of(18, 30), durationMin = 60))
        calendar.eventList = listOf(daily, ext(2, "Cumpleaños", thu.atTime(20, 0), thu.atTime(21, 0)), ext(3, "Vacaciones", thu.atStartOfDay(), thu.plusDays(1).atStartOfDay(), allDay = true))
        marks.set(classificationKey(daily), true)
        val water = habits.upsert(Habit(name = "Tomar agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(60), dailyTarget = 8))
        habits.logDone(water, thu.atTime(9, 0))

        val fromProvider = provider().snapshot(thu)
        val inputs = SnapshotInputs(
            routine = app.brujula.routine.Routine(),
            activities = activities.observeAll().first(),
            done = activities.observeDoneKeys().first(),
            overrides = activities.observeOverrides().first(),
            external = calendar.eventList,
        ) { it.eventId == 1L }
        val activeWater = habits.observeHabits().first().first { it.icon == HabitIcon.WATER }
        val pure = buildDaySnapshot(thu, inputs, activeWater, 1)

        assertEquals(fromProvider, pure)
    }
}
