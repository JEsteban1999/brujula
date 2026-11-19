package app.brujula.planning

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.Activity
import app.brujula.activities.ActivityOrigin
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.calendar.ExternalEvent
import app.brujula.calendar.FakeCalendarSource
import app.brujula.calendar.WorkClassificationRepository
import app.brujula.core.time.FixedClock
import app.brujula.core.time.TimeSpan
import app.brujula.data.db.BrujulaDatabase
import app.brujula.habits.HabitRepository
import app.brujula.routine.BedtimeChoice
import app.brujula.routine.RoutineRepository
import app.brujula.settings.FakeReminderSync
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class NightViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: BrujulaDatabase
    private lateinit var activities: ActivityRepository
    private lateinit var habits: HabitRepository
    private val thu = LocalDate.of(2026, 11, 19)
    private val fri = thu.plusDays(1)
    private val calendar = FakeCalendarSource()
    private val sync = FakeReminderSync()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        activities = ActivityRepository(db.activityDao())
        habits = HabitRepository(db.habitDao())
    }

    @After fun tearDown() { db.close() }

    private class Env(val vm: NightViewModel, val routine: RoutineRepository)

    private suspend fun TestScope.env(now: LocalDateTime, routine: RoutineRepository = newRoutine()): Env {
        routine.calendarPrefs.setSelectedCalendarIds(setOf(1L))
        val snapshots = DaySnapshotProvider(routine, activities, habits, calendar, WorkClassificationRepository(db.workClassificationDao()))
        return Env(NightViewModel(FixedClock(now), snapshots, routine, sync, backgroundScope), routine)
    }

    private fun TestScope.newRoutine() =
        RoutineRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "night.preferences_pb") })

    private suspend fun Env.loaded() = vm.state.filterNotNull().first()

    private fun call() = ExternalEvent(1, 0, 1, "Trabajo", "Outlook", "Llamada con cliente", TimeSpan(fri.atTime(15, 0), fri.atTime(16, 0)), false, null)

    private suspend fun seedTomorrow() {
        calendar.eventList = listOf(call())
        activities.upsert(
            Activity(
                title = "Ejercicio en casa", type = ActivityType.EXERCISE, date = fri, start = LocalTime.of(19, 0), durationMin = 30,
                origin = ActivityOrigin.DICTATION,
            ),
        )
    }

    @Test fun stateMatchesMockupCopy() = runTest {
        seedTomorrow()
        val s = env(thu.atTime(22, 45)).loaded()
        assertEquals("10:45 pm · Buenas noches", s.timeLabel)
        assertEquals(
            listOf(NightItem("Llamada con cliente", "15:00", "Outlook"), NightItem("Ejercicio en casa", "19:00", "lo dictaste")),
            s.tomorrow,
        )
        assertEquals(fri.atTime(0, 15), s.bedtime.bedtime)
        assertEquals(480, s.bedtime.sleepMinutes)
        assertEquals("Para dormir 8 h, acuéstate a las 0:15", s.bedtimeText)
        assertEquals("Avisarme a las 23:45", s.reminderText)
        assertTrue(s.reminderOn)
        assertTrue(s.reminderVisible)
        assertEquals(ExerciseState.NONE, s.today.exercise)
    }

    @Test fun firstOpenTurnsReminderOn() = runTest {
        val e = env(thu.atTime(22, 45))
        assertEquals(null, e.routine.bedtimeChoice.first())
        e.loaded()
        assertEquals(BedtimeChoice(thu, fri.atTime(0, 15), 480, remind = true), e.routine.bedtimeChoice.first())
        assertEquals(1, sync.calls)
    }

    @Test fun toggleOffPersists() = runTest {
        val routine = newRoutine()
        val e = env(thu.atTime(22, 45), routine)
        e.loaded()
        e.vm.setReminder(false).join()
        assertFalse(e.vm.state.first()!!.reminderOn)
        assertEquals(false, routine.bedtimeChoice.first()?.remind)
        assertEquals(2, sync.calls)
        // Una pantalla nueva el mismo dia respeta la eleccion y no reprograma otra vez.
        val again = env(thu.atTime(22, 50), routine).loaded()
        assertFalse(again.reminderOn)
        assertEquals(2, sync.calls)
        // Al encenderlo de nuevo tambien se guarda.
        val e2 = env(thu.atTime(22, 55), routine)
        e2.loaded()
        e2.vm.setReminder(true).join()
        assertEquals(true, routine.bedtimeChoice.first()?.remind)
        assertEquals(3, sync.calls)
    }

    @Test fun pastReminderHidesToggle() = runTest {
        // 23:50: el aviso de las 23:45 ya paso; la hora de dormir (0:15) sigue siendo la de esta noche.
        val s = env(thu.atTime(23, 50)).loaded()
        assertEquals(fri.atTime(0, 15), s.bedtime.bedtime)
        assertFalse(s.reminderVisible)
    }

    @Test fun afterMidnightKeepsTheLogicalDay() = runTest {
        val e = env(fri.atTime(0, 30))
        val s = e.loaded()
        assertEquals(thu, e.vm.today)
        assertEquals(fri, e.vm.tomorrow)
        assertEquals("12:30 am · Buenas noches", s.timeLabel)
        // La hora de dormir no se pone en el pasado: 0:15 ya paso, se corre a 0:30 y no hay aviso que encender.
        assertEquals(fri.atTime(0, 30), s.bedtime.bedtime)
        assertFalse(s.reminderVisible)
        assertEquals(thu, e.routine.bedtimeChoice.first()?.day)
    }

    @Test fun tomorrowWithNothingIsEmpty() = runTest {
        assertTrue(env(thu.atTime(22, 45)).loaded().tomorrow.isEmpty())
    }

    @Test fun greetingByHour() {
        assertEquals("Buenas noches", greeting(LocalTime.of(3, 59)))
        assertEquals("Buenos días", greeting(LocalTime.of(4, 0)))
        assertEquals("Buenos días", greeting(LocalTime.of(11, 59)))
        assertEquals("Buenas tardes", greeting(LocalTime.of(12, 0)))
        assertEquals("Buenas tardes", greeting(LocalTime.of(18, 59)))
        assertEquals("Buenas noches", greeting(LocalTime.of(19, 0)))
        assertEquals("12:05 am", formatClock12(LocalTime.of(0, 5)))
        assertEquals("12:00 pm", formatClock12(LocalTime.NOON))
        assertEquals("10:45 pm", formatClock12(LocalTime.of(22, 45)))
    }
}
