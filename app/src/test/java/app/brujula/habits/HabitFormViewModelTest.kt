package app.brujula.habits

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.data.db.BrujulaDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class HabitFormViewModelTest {
    private lateinit var db: BrujulaDatabase
    private lateinit var habits: HabitRepository
    private var resyncs = 0

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        habits = HabitRepository(db.habitDao())
    }

    @After fun tearDown() { db.close() }

    private fun TestScope.vm(id: Long? = null) = HabitFormViewModel(
        habitId = id, habitRepository = habits, resync = { resyncs++ }, scopeOverride = backgroundScope,
    )

    @Test fun validations() = runTest {
        val vm = vm()
        vm.save()
        assertEquals("Ponle un nombre al hábito", vm.state.value.nameError)
        assertFalse(vm.state.value.saved)

        vm.setName("Agua")
        vm.setIntervalMin(10)
        vm.save()
        assertNull(vm.state.value.nameError)
        assertNotNull(vm.state.value.intervalError)
        vm.setIntervalMin(481)
        vm.save()
        assertNotNull(vm.state.value.intervalError)

        vm.setMode(HabitMode.AT_TIMES)
        vm.setTimes(emptyList())
        vm.save()
        assertNotNull(vm.state.value.timesError)
        assertNull(vm.state.value.intervalError) // el intervalo no aplica en AT_TIMES

        vm.setMode(HabitMode.EVERY)
        vm.setIntervalMin(60)
        vm.setDays(emptySet())
        vm.save()
        assertNotNull(vm.state.value.daysError)
        assertFalse(vm.state.value.saved)
        assertTrue(habits.observeHabits().first().isEmpty())
    }

    @Test fun validEverySavesHabit() = runTest {
        val vm = vm()
        vm.setName("  Agua ")
        vm.setIcon(HabitIcon.WATER)
        vm.setIntervalMin(90)
        vm.setDailyTarget(8)
        vm.save()
        vm.state.first { it.saved }
        val h = habits.observeHabits().first().single()
        assertEquals("Agua", h.name)
        assertEquals(HabitSchedule.Every(90), h.schedule)
        assertEquals(8, h.dailyTarget)
        assertEquals(DayOfWeek.entries.toSet(), h.days)
        assertEquals(1, resyncs)
    }

    @Test fun validAtTimesSavesSortedAndOnlyOnce() = runTest {
        val vm = vm()
        vm.setName("Bálsamo")
        vm.setMode(HabitMode.AT_TIMES)
        vm.setTimes(listOf(LocalTime.of(13, 0), LocalTime.of(9, 0), LocalTime.of(13, 0)))
        vm.setDays(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        vm.save()
        vm.save() // doble toque
        vm.state.first { it.saved }
        val all = habits.observeHabits().first()
        assertEquals(1, all.size)
        assertEquals(HabitSchedule.AtTimes(listOf(LocalTime.of(9, 0), LocalTime.of(13, 0))), all.single().schedule)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), all.single().days)
    }

    @Test fun editLoadsAndUpdatesAndDeletes() = runTest {
        val id = habits.upsert(
            Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8),
        )
        val vm = vm(id)
        val loaded = vm.state.first { it.ready }
        assertTrue(loaded.isEdit)
        assertEquals("Agua", loaded.name)
        assertEquals(90, loaded.intervalMin)
        vm.setName("Agua fresca")
        vm.save()
        vm.state.first { it.saved }
        assertEquals(listOf("Agua fresca"), habits.observeHabits().first().map { it.name })

        val vm2 = vm(id)
        vm2.state.first { it.ready }
        vm2.delete()
        vm2.state.first { it.saved }
        assertTrue(habits.observeHabits().first().isEmpty())
    }
}
