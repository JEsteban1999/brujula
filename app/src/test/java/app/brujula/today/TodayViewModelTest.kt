package app.brujula.today

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.core.time.FixedClock
import app.brujula.core.time.TimeSpan
import app.brujula.data.db.BrujulaDatabase
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitRepository
import app.brujula.habits.HabitSchedule
import app.brujula.reminders.PermissionStatus
import app.brujula.routine.RoutineRepository
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
class TodayViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: BrujulaDatabase
    private lateinit var activities: ActivityRepository
    private lateinit var habits: HabitRepository
    private val day = LocalDate.of(2026, 11, 18)
    private val clock = FixedClock(LocalDateTime.of(2026, 11, 18, 10, 40))
    private var perms = PermissionStatus(notifications = true, exactAlarms = true)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        activities = ActivityRepository(db.activityDao())
        habits = HabitRepository(db.habitDao())
    }

    @After fun tearDown() { db.close() }

    private fun TestScope.vm() = TodayViewModel(
        clock = clock,
        routineRepository = RoutineRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "r.preferences_pb") },
        ),
        activityRepository = activities,
        habitRepository = habits,
        readPermissions = { perms },
        scopeOverride = backgroundScope,
        tickMs = 0,
    )

    private fun act(title: String, type: ActivityType, start: String, min: Int, date: LocalDate = day, repeat: Repeat = Repeat.NONE) =
        Activity(title = title, type = type, date = date, start = LocalTime.parse(start), durationMin = min, repeat = repeat)

    private suspend fun seedDay(): Long {
        val w1 = activities.upsert(act("Trabajo", ActivityType.WORK, "08:00", 150))
        activities.upsert(act("Ejercicio", ActivityType.EXERCISE, "10:30", 30))
        activities.upsert(act("Trabajo", ActivityType.WORK, "11:00", 90))
        activities.setDone(w1, day, true)
        return w1
    }

    @Test fun nowEntryAndWorkMinutes() = runTest {
        seedDay()
        val s = vm().state.first { it.timeline.isNotEmpty() }
        assertTrue(s.timeline.single { it.isNow }.occurrence.activity.type == ActivityType.EXERCISE)
        assertEquals(240, s.workMinutes) // 150 + 90
        assertEquals("Miércoles 18 de noviembre", s.dayLabel)
        assertTrue(s.timeline.first().occurrence.done)
    }

    @Test fun addHabitIncrementsCount() = runTest {
        val water = habits.upsert(
            Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8),
        )
        repeat(3) { habits.logDone(water, clock.now().minusMinutes(60L * (it + 1))) }
        val vm = vm()
        assertEquals(3, vm.state.first { it.habits.isNotEmpty() }.habits.single().count)
        vm.addHabit(water)
        val s = vm.state.first { it.habits.single().count == 4 }
        assertEquals(4, s.habits.single().count)
        assertEquals(8, s.habits.single().target)
    }

    @Test fun toggleDoneMarksOnlyToday() = runTest {
        val id = activities.upsert(act("Ejercicio", ActivityType.EXERCISE, "18:30", 30, repeat = Repeat.DAILY))
        val vm = vm()
        vm.state.first { it.timeline.isNotEmpty() }
        vm.toggleDone(id, day)
        vm.state.first { it.timeline.single().occurrence.done }
        val keys = activities.observeDoneKeys().first()
        assertTrue((id to day) in keys)
        assertFalse((id to day.plusDays(1)) in keys)
        vm.toggleDone(id, day)
        vm.state.first { !it.timeline.single().occurrence.done }
        assertTrue(activities.observeDoneKeys().first().isEmpty())
    }

    @Test fun freeSlotsInsideAwakeWindow() = runTest {
        seedDay()
        activities.upsert(act("Ejercicio", ActivityType.EXERCISE, "18:30", 30))
        val s = vm().state.first { it.timeline.size == 4 }
        val at = { h: Int, m: Int -> day.atTime(h, m) }
        assertTrue(s.freeSlots.contains(TimeSpan(at(12, 30), at(18, 30))))
        assertTrue(s.freeSlots.none { it.start < at(8, 15) })
        assertTrue(s.freeSlots.none { it.end > day.plusDays(1).atTime(0, 30) })
        assertTrue(s.freeSlots.all { it.minutes >= 30 })
        assertTrue(s.freeSlots.contains(TimeSpan(at(19, 0), day.plusDays(1).atTime(0, 30))))
    }

    @Test fun overnightEndingBeforeWakeIsNotShown() = runTest {
        // ayer 23:30 + 90 min termina 01:00 de hoy: antes de levantarse (08:15), no entra en la vigilia de hoy
        activities.upsert(act("Película", ActivityType.PERSONAL, "23:30", 90, date = day.minusDays(1)))
        activities.upsert(act("Trabajo", ActivityType.WORK, "09:00", 60))
        val s = vm().state.first { it.timeline.isNotEmpty() }
        assertEquals(listOf("Trabajo"), s.timeline.map { it.occurrence.activity.title })
    }

    @Test fun overnightIntersectingAwakeWindowAppearsOnceAtTop() = runTest {
        // una vez, el día anterior, 23:30 -> 09:00 de hoy: cruza la vigilia (08:15)
        activities.upsert(act("Guardia", ActivityType.PERSONAL, "23:30", 570, date = day.minusDays(1)))
        activities.upsert(act("Trabajo", ActivityType.WORK, "09:30", 60))
        val s = vm().state.first { it.timeline.isNotEmpty() }
        assertEquals(listOf("Guardia", "Trabajo"), s.timeline.map { it.occurrence.activity.title })
        assertEquals(day.minusDays(1), s.timeline.first().occurrence.day)
        assertEquals(1, s.timeline.count { it.occurrence.activity.title == "Guardia" })
        // el hueco libre empieza cuando acaba (09:00), no a las 08:15
        assertTrue(s.freeSlots.none { it.start < day.atTime(9, 0) })
        assertEquals(day.atTime(9, 0), s.freeSlots.first().start)
        // no cuenta como trabajo de hoy
        assertEquals(60, s.workMinutes)
    }

    @Test fun toggleDoneTargetsTheGivenOccurrenceOfOvernightRepeat() = runTest {
        // DAILY 23:30 + 10 h: la de ayer termina 09:30 (cruza la vigilia) y la de hoy empieza 23:30
        val id = activities.upsert(act("Guardia", ActivityType.PERSONAL, "23:30", 600, date = day.minusDays(1), repeat = Repeat.DAILY))
        val yesterday = day.minusDays(1)
        activities.setDone(id, yesterday, true)
        val vm = vm()
        val s = vm.state.first { it.timeline.size == 2 }
        assertEquals(listOf(yesterday, day), s.timeline.map { it.occurrence.day })
        assertEquals(listOf(true, false), s.timeline.map { it.occurrence.done })
        vm.toggleDone(id, day)
        vm.state.first { st -> st.timeline.size == 2 && st.timeline.all { it.occurrence.done } }
        assertEquals(setOf(id to yesterday, id to day), activities.observeDoneKeys().first())
        vm.toggleDone(id, day)
        vm.state.first { st -> st.timeline.map { it.occurrence.done } == listOf(true, false) }
        assertEquals(setOf(id to yesterday), activities.observeDoneKeys().first())
    }

    @Test fun missingPermissionsAreExposed() = runTest {
        perms = PermissionStatus(notifications = false, exactAlarms = true)
        val vm = vm()
        assertFalse(vm.state.first { !it.permissions.ok }.permissions.ok)
        perms = PermissionStatus(notifications = true, exactAlarms = true)
        vm.refreshPermissions()
        assertTrue(vm.state.first { it.permissions.ok }.permissions.ok)
    }
}
