package app.brujula.habits

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.core.time.FixedClock
import app.brujula.data.db.BrujulaDatabase
import app.brujula.routine.RoutineRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class HabitsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: BrujulaDatabase
    private lateinit var activities: ActivityRepository
    private lateinit var habits: HabitRepository
    private val day = LocalDate.of(2026, 11, 18) // miércoles
    private val clock = FixedClock(LocalDateTime.of(2026, 11, 18, 10, 40))

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        activities = ActivityRepository(db.activityDao())
        habits = HabitRepository(db.habitDao())
    }

    @After fun tearDown() { db.close() }

    private fun TestScope.vm(at: FixedClock = clock, file: String = "r.preferences_pb") = HabitsViewModel(
        clock = at,
        routineRepository = RoutineRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, file) },
        ),
        activityRepository = activities,
        habitRepository = habits,
        scopeOverride = backgroundScope,
        tickMs = 0,
    )

    private suspend fun water() = habits.upsert(
        Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8),
    )

    @Test fun nextReminderAndCounts() = runTest {
        val id = water()
        repeat(3) { habits.logDone(id, clock.now().minusMinutes(60L * (it + 1))) }
        val card = vm().state.first { it.cards.isNotEmpty() && it.cards.single().count == 3 }.cards.single()
        assertEquals(3, card.count)
        assertEquals(day.atTime(11, 15), card.nextAt)
        assertEquals(emptyList<Pair<LocalTime, Boolean>>(), card.slots)
    }

    @Test fun nextReminderIsNullAfterLastOne() = runTest {
        water()
        val late = vm(FixedClock(day.atTime(23, 50)), "r2.preferences_pb")
        assertNull(late.state.first { it.cards.isNotEmpty() }.cards.single().nextAt)
    }

    @Test fun balmSlotsMarkedInOrder() = runTest {
        val id = habits.upsert(
            Habit(
                name = "Bálsamo labial", icon = HabitIcon.LIP_BALM, dailyTarget = 4,
                schedule = HabitSchedule.AtTimes(listOf(9, 13, 17, 21).map { LocalTime.of(it, 0) }),
            ),
        )
        habits.logDone(id, clock.now().minusMinutes(90))
        val card = vm().state.first { it.cards.isNotEmpty() && it.cards.single().count == 1 }.cards.single()
        assertEquals(
            listOf(
                LocalTime.of(9, 0) to true, LocalTime.of(13, 0) to false,
                LocalTime.of(17, 0) to false, LocalTime.of(21, 0) to false,
            ),
            card.slots,
        )
    }

    @Test fun addAndUndoChangeCount() = runTest {
        val id = water()
        val vm = vm()
        vm.state.first { it.cards.isNotEmpty() }
        vm.add(id)
        vm.state.first { it.cards.single().count == 1 }
        vm.undo(id)
        assertEquals(0, vm.state.first { it.cards.isNotEmpty() && it.cards.single().count == 0 }.cards.single().count)
    }

    @Test fun streakFromExerciseCompletions() = runTest {
        val ex = activities.upsert(
            Activity(
                title = "Ejercicio", type = ActivityType.EXERCISE, date = day.minusDays(10),
                start = LocalTime.of(18, 30), durationMin = 30, repeat = Repeat.DAILY,
            ),
        )
        // una actividad que no es ejercicio no cuenta
        val other = activities.upsert(
            Activity(title = "Trabajo", type = ActivityType.WORK, date = day, start = LocalTime.of(9, 0), durationMin = 60),
        )
        activities.setDone(other, day.minusDays(3), true)
        listOf(16, 17, 18).forEach { activities.setDone(ex, LocalDate.of(2026, 11, it), true) }
        val s = vm().state.first { it.exerciseStreak > 0 }
        assertEquals(3, s.exerciseStreak)
        assertEquals(listOf(true, true, true, false, false, false, false), s.weekDone)
    }
}
