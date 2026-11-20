package app.brujula.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.data.db.BrujulaDatabase
import app.brujula.data.db.toModel
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitRepository
import app.brujula.habits.HabitSchedule
import app.brujula.habits.FasterStretch
import app.brujula.data.db.decodeSchedule
import app.brujula.data.db.encode
import app.brujula.suggestions.HabitLog
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

    @Test fun groupWindowAndBeforeBedRoundTrip() = runTest {
        val id = repo.upsert(
            Habit(
                name = "Pantallas", icon = HabitIcon.SCREEN_OFF, schedule = HabitSchedule.BeforeBed(45), dailyTarget = 1,
                group = app.brujula.habits.HabitGroup.WELLNESS, window = app.brujula.habits.HabitWindow.WORK,
            ),
        )
        val h = repo.observeHabits().first().single { it.id == id }
        assertEquals(app.brujula.habits.HabitGroup.WELLNESS, h.group)
        assertEquals(app.brujula.habits.HabitWindow.WORK, h.window)
        assertEquals(HabitSchedule.BeforeBed(45), h.schedule)
        assertEquals(HabitIcon.SCREEN_OFF, h.icon)
    }

    @Test fun unknownIconFallsBackToGeneric() = runTest {
        val e = app.brujula.data.db.HabitEntity(1, "X", "NOPE", "every:60", 1, "1,2", true)
        assertEquals(HabitIcon.GENERIC, e.toModel().icon)
    }

    @Test fun scheduleWithFasterRoundTrip() = runTest {
        val faster = HabitSchedule.Every(90, FasterStretch(LocalTime.of(15, 0), 60))
        assertEquals("every:90|15:00/60", faster.encode())
        assertEquals(faster, decodeSchedule(faster.encode()))
        val id = repo.upsert(water().copy(schedule = faster))
        assertEquals(faster, repo.observeHabits().first().single { it.id == id }.schedule)
        // Quitar el tramo vuelve al texto de siempre.
        assertEquals("every:90", HabitSchedule.Every(90).encode())
    }

    @Test fun oldEveryTextStillReads() {
        assertEquals(HabitSchedule.Every(90), decodeSchedule("every:90"))
        assertEquals(HabitSchedule.AtTimes(listOf(LocalTime.of(9, 0), LocalTime.of(13, 0))), decodeSchedule("at:09:00,13:00"))
        assertEquals(HabitSchedule.BeforeBed(45), decodeSchedule("bed:45"))
        // Un sufijo que esta versión no entiende se ignora: queda el intervalo base.
        assertEquals(HabitSchedule.Every(90), decodeSchedule("every:90|algo"))
        assertEquals(HabitSchedule.Every(90), decodeSchedule("every:90|25:00/60"))
        assertEquals(HabitSchedule.Every(90), decodeSchedule("every:90|15:00/0"))
    }

    @Test fun logsBetweenFiltersByLogicalDay() = runTest {
        val id = repo.upsert(water())
        repo.logDone(id, LocalDateTime.of(2026, 11, 10, 9, 0)) // fuera (antes)
        repo.logDone(id, LocalDateTime.of(2026, 11, 12, 0, 30)) // día lógico 11: fuera
        repo.logDone(id, LocalDateTime.of(2026, 11, 13, 1, 0)) // día lógico 12: dentro
        repo.logSkip(id, LocalDateTime.of(2026, 11, 15, 15, 0), "k1")
        repo.logDone(id, LocalDateTime.of(2026, 11, 18, 9, 0))
        repo.logDone(id, LocalDateTime.of(2026, 11, 19, 3, 59)) // día lógico 18: dentro
        repo.logDone(id, LocalDateTime.of(2026, 11, 19, 4, 0)) // día lógico 19: fuera
        val logs = repo.logsBetween(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 18))
        assertEquals(4, logs.size)
        assertEquals(setOf(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 18)), logs.map { it.logicalDay }.toSet())
        val skip = logs.single { it.skipped }
        assertEquals(HabitLog(id, LocalDateTime.of(2026, 11, 15, 15, 0), LocalDate.of(2026, 11, 15), true), skip)
    }
}
