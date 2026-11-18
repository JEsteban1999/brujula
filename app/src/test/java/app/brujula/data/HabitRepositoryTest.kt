package app.brujula.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.data.db.BrujulaDatabase
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitRepository
import app.brujula.habits.HabitSchedule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class HabitRepositoryTest {
    private lateinit var db: BrujulaDatabase
    private lateinit var repo: HabitRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = HabitRepository(db.habitDao())
    }

    @After fun tearDown() { db.close() }

    private fun water() = Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8)

    @Test fun sameReminderKeyCountsOnce() = runTest {
        val id = repo.upsert(water())
        val at = LocalDateTime.of(2026, 11, 18, 11, 15)
        assertTrue(repo.logDone(id, at, "habit:$id:202611181115"))
        assertFalse(repo.logDone(id, at, "habit:$id:202611181115"))
        assertEquals(1, repo.observeCountsFor(at.toLocalDate()).first()[id])
    }

    @Test fun afterMidnightCountsForPreviousDay() = runTest {
        val id = repo.upsert(water())
        repo.logDone(id, LocalDateTime.of(2026, 11, 19, 0, 20))
        assertEquals(1, repo.observeCountsFor(LocalDate.of(2026, 11, 18)).first()[id])
    }

    @Test fun schedulesRoundTrip() = runTest {
        val a = repo.upsert(water())
        val times = listOf(LocalTime.of(9, 0), LocalTime.of(13, 0), LocalTime.of(17, 0), LocalTime.of(21, 0))
        val b = repo.upsert(Habit(name = "Balsamo", icon = HabitIcon.LIP_BALM, schedule = HabitSchedule.AtTimes(times), dailyTarget = 4))
        val habits = repo.observeHabits().first().associateBy { it.id }
        assertEquals(HabitSchedule.Every(90), habits[a]!!.schedule)
        assertEquals(HabitSchedule.AtTimes(times), habits[b]!!.schedule)
        assertEquals(HabitIcon.LIP_BALM, habits[b]!!.icon)
    }

    @Test fun skippedDoesNotCountButIsHandled() = runTest {
        val id = repo.upsert(water())
        val at = LocalDateTime.of(2026, 11, 18, 11, 15)
        repo.logSkip(id, at, "k1")
        repo.logDone(id, at.plusMinutes(90), "k2")
        assertEquals(1, repo.observeCountsFor(at.toLocalDate()).first()[id])
        assertEquals(setOf("k1", "k2"), repo.observeHandledKeys().first())
    }

    @Test fun undoLastRemovesMostRecentDone() = runTest {
        val id = repo.upsert(water())
        val day = LocalDate.of(2026, 11, 18)
        repo.logDone(id, day.atTime(9, 0))
        repo.logDone(id, day.atTime(10, 30))
        repo.undoLast(id, day)
        assertEquals(1, repo.observeCountsFor(day).first()[id])
    }

    @Test fun deleteRemovesHabitAndLogs() = runTest {
        val id = repo.upsert(water())
        repo.logDone(id, LocalDateTime.of(2026, 11, 18, 9, 0), "x")
        repo.delete(id)
        assertTrue(repo.observeHabits().first().isEmpty())
        assertTrue(repo.observeHandledKeys().first().isEmpty())
    }
}
