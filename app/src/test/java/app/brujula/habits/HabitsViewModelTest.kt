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

    @Test fun weekRestMarksWeekendDays() = runTest {
        val st = vm().state.first { true in it.weekRest }
        // lunes 16 nov 2026 es festivo (Independencia de Cartagena, 11 nov trasladado)
        assertEquals(listOf(true, false, false, false, false, true, true), st.weekRest)
    }

    private suspend fun seeded(routine: RoutineRepository) {
        app.brujula.data.db.Migrations.seedWellness(db, routine)
    }

    @Test fun wellnessRowsInSpecOrderAndEssentialCardsStay() = runTest {
        water()
        val routine = RoutineRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "w.preferences_pb") })
        seeded(routine)
        val vm = HabitsViewModel(clock, routine, activities, habits, scopeOverride = backgroundScope, tickMs = 0)
        val s = vm.state.first { it.wellness.size == 9 && it.cards.isNotEmpty() }
        assertEquals(listOf("Agua"), s.cards.map { it.habit.name })
        assertEquals(
            listOf(
                "Descanso visual 20-20-20", "Pararte y moverte", "Estiramientos", "Postura", "Comer a horas",
                "Desconectar pantallas", "Vitaminas", "Protector solar", "Lectura sin pantalla",
            ),
            s.wellness.map { it.habit.name },
        )
        assertEquals(
            listOf(
                "Cada 30 min en horario de trabajo", "Cada 60 min en horario de trabajo", "Cada 2 h en horario de trabajo",
                "10:15 y 16:15 en horario de trabajo", "12:30 y 16:00", "45 min antes de dormir", "9:00",
                "9:00 y 12:30", "21:30",
            ),
            s.wellness.map { it.summary },
        )
        assertEquals(s.wellness.map { it.habit.dailyTarget }, s.wellness.map { it.target })
    }

    @Test fun setActiveTogglesAndResyncs() = runTest {
        val routine = RoutineRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "w2.preferences_pb") })
        seeded(routine)
        var resyncs = 0
        val vm = HabitsViewModel(clock, routine, activities, habits, resync = { resyncs++ }, scopeOverride = backgroundScope, tickMs = 0)
        val vit = vm.state.first { it.wellness.size == 9 }.wellness.first { it.habit.name == "Vitaminas" }
        assertEquals(false, vit.habit.active)
        vm.setActive(vit.habit.id, true)
        val after = vm.state.first { s -> s.wellness.any { it.habit.name == "Vitaminas" && it.habit.active } }
        assertEquals(9, after.wellness.size)
        assertEquals(1, resyncs)
    }

    @Test fun wellnessCountUpdatesOnAdd() = runTest {
        val routine = RoutineRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "w3.preferences_pb") })
        seeded(routine)
        val vm = HabitsViewModel(clock, routine, activities, habits, scopeOverride = backgroundScope, tickMs = 0)
        val eye = vm.state.first { it.wellness.size == 9 }.wellness.first()
        vm.add(eye.habit.id)
        assertEquals(1, vm.state.first { s -> s.wellness.first().count == 1 }.wellness.first().count)
    }

    @Test fun restDayWellnessTargetIsNumberOfRemindersThatDay() = runTest {
        val routine = RoutineRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "w4.preferences_pb") })
        seeded(routine)
        val sat = FixedClock(LocalDateTime.of(2026, 11, 21, 12, 0))
        val vm = HabitsViewModel(sat, routine, activities, habits, scopeOverride = backgroundScope, tickMs = 0)
        val rows = vm.state.first { it.wellness.size == 9 }.wellness.associateBy { it.habit.name }
        assertEquals(0, rows.getValue("Descanso visual 20-20-20").target)
        assertEquals(0, rows.getValue("Postura").target)
        assertEquals(2, rows.getValue("Comer a horas").target)
        assertEquals(0, rows.getValue("Comer a horas").count)
    }
}
