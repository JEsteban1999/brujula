package app.brujula.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.data.db.BrujulaDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class ActivityRepositoryTest {
    private lateinit var db: BrujulaDatabase
    private lateinit var repo: ActivityRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ActivityRepository(db.activityDao())
    }

    @After fun tearDown() { db.close() }

    private val sample = Activity(
        title = "Dentista", type = ActivityType.APPOINTMENT, date = LocalDate.of(2026, 11, 20),
        start = LocalTime.of(16, 45), durationMin = 60, repeat = Repeat.WEEKLY, remindBeforeMin = null, copyToGoogle = true,
    )

    @Test fun upsertThenGetIsEqual() = runTest {
        val id = repo.upsert(sample)
        assertTrue(id > 0)
        assertEquals(sample.copy(id = id), repo.get(id))
        assertEquals(listOf(sample.copy(id = id)), repo.observeAll().first())
    }

    @Test fun upsertWithIdUpdates() = runTest {
        val id = repo.upsert(sample)
        repo.upsert(sample.copy(id = id, title = "Otro"))
        assertEquals("Otro", repo.get(id)!!.title)
        assertEquals(1, repo.observeAll().first().size)
    }

    @Test fun setDoneAppearsOnlyForThatDay() = runTest {
        val id = repo.upsert(sample)
        val d1 = LocalDate.of(2026, 11, 20)
        val d2 = LocalDate.of(2026, 11, 21)
        repo.setDone(id, d1, true)
        assertEquals(setOf(id to d1), repo.observeDoneKeys().first())
        assertFalse((id to d2) in repo.observeDoneKeys().first())
        repo.setDone(id, d1, false)
        assertTrue(repo.observeDoneKeys().first().isEmpty())
    }

    @Test fun deleteRemovesActivityAndCompletions() = runTest {
        val id = repo.upsert(sample)
        repo.setDone(id, sample.date, true)
        repo.delete(id)
        assertNull(repo.get(id))
        assertTrue(repo.observeDoneKeys().first().isEmpty())
    }
}
