package app.brujula.calendar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.core.time.TimeSpan
import app.brujula.data.db.BrujulaDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class WorkClassificationRepositoryTest {
    private lateinit var db: BrujulaDatabase
    private lateinit var repo: WorkClassificationRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = WorkClassificationRepository(db.workClassificationDao())
    }

    @After fun tearDown() { db.close() }

    private fun ev(title: String, id: Long = 1) = ExternalEvent(
        id, 0, 1, "Cal", "Google", title,
        TimeSpan(LocalDateTime.of(2026, 11, 18, 10, 0), LocalDateTime.of(2026, 11, 18, 11, 0)), false, null,
    )

    @Test fun keyIgnoresCaseAccentsAndSpaces() {
        assertEquals(classificationKey(ev("Reunión de equipo")), classificationKey(ev("reunion  de EQUIPO")))
        assertEquals("reunion de equipo", classificationKey(ev("  Reunión   de equipo ")))
    }

    @Test fun markingOneMarksTheEquivalentOne() = runTest {
        repo.set(classificationKey(ev("Reunión de equipo")), true)
        val map = repo.observe().first()
        assertTrue(repo.isWork(ev("reunion  de EQUIPO", id = 9), map))
    }

    @Test fun unmarkedIsNotWork() = runTest {
        val map = repo.observe().first()
        assertFalse(repo.isWork(ev("Dentista"), map))
    }

    @Test fun canBeUnmarked() = runTest {
        val k = classificationKey(ev("Stand up"))
        repo.set(k, true)
        repo.set(k, false)
        assertFalse(repo.isWork(ev("Stand up"), repo.observe().first()))
    }

    @Test fun setAutoWritesWhenThereIsNoMark() = runTest {
        repo.setAuto("yoga", false)
        repo.setAuto("reunion", true)
        assertEquals(mapOf("yoga" to false, "reunion" to true), repo.observe().first())
    }

    @Test fun setAutoNeverOverridesUserMark() = runTest {
        repo.set("reunion", false)
        repo.setAuto("reunion", true)
        assertEquals(mapOf("reunion" to false), repo.observe().first())
        assertEquals("USER", db.workClassificationDao().sourceOf("reunion"))
    }

    @Test fun setAutoUpdatesEarlierAutoButUserOverwritesAuto() = runTest {
        repo.setAuto("reunion", true)
        repo.setAuto("reunion", false)
        assertEquals(false, repo.observe().first()["reunion"])
        assertEquals("AUTO", db.workClassificationDao().sourceOf("reunion"))
        repo.set("reunion", true)
        assertEquals(true, repo.observe().first()["reunion"])
        assertEquals("USER", db.workClassificationDao().sourceOf("reunion"))
    }

    @Test fun setAutoIgnoresBlankKeys() = runTest {
        repo.setAuto("", true)
        assertTrue(repo.observe().first().isEmpty())
    }
}
