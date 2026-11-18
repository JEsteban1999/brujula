package app.brujula.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.data.db.BrujulaDatabase
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
        val hs = habits.observeHabits().first()
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
        assertEquals(Repeat.DAILY, a.repeat)
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
}
