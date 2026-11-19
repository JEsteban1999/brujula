package app.brujula.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.data.db.BrujulaDatabase
import app.brujula.data.db.Migrations
import app.brujula.data.db.Seed
import app.brujula.habits.HabitRepository
import app.brujula.habits.HabitSchedule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.brujula.routine.RoutineRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class SeedTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: BrujulaDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun TestScope.routineRepo() =
        RoutineRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "seed.preferences_pb") })

    @Test fun ensureDefaultsTwiceLeavesTwoHabitsAndOneActivity() = runTest {
        val activities = ActivityRepository(db.activityDao())
        val habits = HabitRepository(db.habitDao())
        val routine = routineRepo()
        val today = LocalDate.of(2026, 11, 18)
        Seed.ensureDefaults(db, routine, today)
        Seed.ensureDefaults(db, routine, today)
        val hs = habits.observeHabits().first().filter { it.group == app.brujula.habits.HabitGroup.ESSENTIAL }
        assertEquals(2, hs.size)
        assertEquals(HabitSchedule.Every(90), hs.single { it.name == "Agua" }.schedule)
        assertEquals(8, hs.single { it.name == "Agua" }.dailyTarget)
        val balm = hs.single { it.name == "Bálsamo labial" }
        assertEquals(HabitSchedule.AtTimes(listOf(9, 13, 17, 21).map { LocalTime.of(it, 0) }), balm.schedule)
        assertEquals(4, balm.dailyTarget)
        val acts = activities.observeAll().first()
        assertEquals(1, acts.size)
        val a = acts.single()
        assertEquals("Ejercicio en casa", a.title)
        assertEquals(ActivityType.EXERCISE, a.type)
        assertEquals(Repeat.WORKDAYS, a.repeat)
        assertEquals(LocalTime.of(18, 30), a.start)
        assertEquals(30, a.durationMin)
        assertEquals(today, a.date)
    }

    @Test fun deletedDefaultsAreNotReseeded() = runTest {
        val activities = ActivityRepository(db.activityDao())
        val habits = HabitRepository(db.habitDao())
        val routine = routineRepo()
        val today = LocalDate.of(2026, 11, 18)
        Seed.ensureDefaults(db, routine, today)
        habits.observeHabits().first().forEach { habits.delete(it.id) }
        activities.observeAll().first().forEach { activities.delete(it.id) }
        Seed.ensureDefaults(db, routine, today)
        assertEquals(0, habits.observeHabits().first().size)
        assertEquals(0, activities.observeAll().first().size)
    }

    private fun phase1(start: LocalTime = LocalTime.of(18, 30)) = Activity(
        title = "Ejercicio en casa", type = ActivityType.EXERCISE, date = LocalDate.of(2026, 11, 18),
        start = start, durationMin = 30, repeat = Repeat.DAILY,
    )

    @Test fun migrationConvertsPhase1ExerciseOnly() = runTest {
        val activities = ActivityRepository(db.activityDao())
        val routine = routineRepo()
        activities.upsert(phase1())
        activities.upsert(phase1(LocalTime.of(19, 0)).copy(title = "Ejercicio en casa"))
        activities.upsert(phase1().copy(title = "Otra", type = ActivityType.PERSONAL))
        Migrations.exerciseToWorkdays(db, routine)
        val all = activities.observeAll().first()
        assertEquals(Repeat.WORKDAYS, all.single { it.start == LocalTime.of(18, 30) && it.title == "Ejercicio en casa" }.repeat)
        assertEquals(Repeat.DAILY, all.single { it.start == LocalTime.of(19, 0) }.repeat)
        assertEquals(Repeat.DAILY, all.single { it.title == "Otra" }.repeat)
    }

    @Test fun migrationRunsOnlyOnce() = runTest {
        val activities = ActivityRepository(db.activityDao())
        val routine = routineRepo()
        val id = activities.upsert(phase1())
        Migrations.exerciseToWorkdays(db, routine)
        activities.upsert(activities.get(id)!!.copy(repeat = Repeat.DAILY))
        Migrations.exerciseToWorkdays(db, routine)
        assertEquals(Repeat.DAILY, activities.get(id)!!.repeat)
    }

    private val wellnessNames = listOf(
        "Descanso visual 20-20-20", "Pararte y moverte", "Estiramientos", "Postura", "Comer a horas",
        "Desconectar pantallas", "Vitaminas", "Protector solar", "Lectura sin pantalla",
    )

    private suspend fun wellness(habits: HabitRepository) =
        habits.observeHabits().first().filter { it.group == app.brujula.habits.HabitGroup.WELLNESS }

    @Test fun seedWellnessCreatesNineOnceAndVitaminsInactive() = runTest {
        val habits = HabitRepository(db.habitDao())
        val routine = routineRepo()
        Migrations.seedWellness(db, routine)
        Migrations.seedWellness(db, routine)
        val ws = wellness(habits)
        assertEquals(wellnessNames.toSet(), ws.map { it.name }.toSet())
        assertEquals(9, ws.size)
        assertEquals(false, ws.single { it.name == "Vitaminas" }.active)
        assertEquals(8, ws.count { it.active })
        val eye = ws.single { it.name == "Descanso visual 20-20-20" }
        assertEquals(app.brujula.habits.HabitWindow.WORK, eye.window)
        assertEquals(HabitSchedule.Every(30), eye.schedule)
        assertEquals(14, eye.dailyTarget)
        assertEquals(HabitSchedule.BeforeBed(45), ws.single { it.name == "Desconectar pantallas" }.schedule)
        assertEquals(1, ws.single { it.name == "Desconectar pantallas" }.dailyTarget)
        assertEquals(2, ws.single { it.name == "Postura" }.dailyTarget)
    }

    @Test fun deletedWellnessHabitIsNotRecreated() = runTest {
        val habits = HabitRepository(db.habitDao())
        val routine = routineRepo()
        Migrations.seedWellness(db, routine)
        habits.delete(wellness(habits).first().id)
        Migrations.seedWellness(db, routine)
        assertEquals(8, wellness(habits).size)
    }

    @Test fun seedWellnessSkipsAndMarksFlagWhenWellnessHabitAlreadyExists() = runTest {
        val habits = HabitRepository(db.habitDao())
        val routine = routineRepo()
        habits.upsert(
            app.brujula.habits.Habit(
                name = "Postura", icon = app.brujula.habits.HabitIcon.POSTURE, schedule = HabitSchedule.Every(60),
                dailyTarget = 1, group = app.brujula.habits.HabitGroup.WELLNESS,
            ),
        )
        assertEquals(false, routine.isWellnessSeeded())
        Migrations.seedWellness(db, routine)
        assertEquals(1, wellness(habits).size)
        assertEquals(true, routine.isWellnessSeeded())
    }
}
