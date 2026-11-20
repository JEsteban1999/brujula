package app.brujula.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.data.db.BrujulaDatabase
import app.brujula.sleep.SleepRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = app.brujula.TestBrujulaApp::class)
class SleepRepositoryTest {
    private lateinit var db: BrujulaDatabase
    private lateinit var repo: SleepRepository
    private val thu = LocalDate.of(2026, 11, 19)
    private val fri = thu.plusDays(1)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = SleepRepository(db.sleepDao())
    }

    @After fun tearDown() { db.close() }

    @Test fun confirmBedtimeUpserts() = runTest {
        repo.confirmBedtime(thu, fri.atTime(0, 20))
        repo.confirmBedtime(thu, fri.atTime(0, 45))
        val r = repo.observeRange(thu, thu).first().getValue(thu)
        assertEquals(fri.atTime(0, 45), r.bedAt)
        assertNull(r.wakeAt)
    }

    @Test fun recordWakeOnlyFirstTime() = runTest {
        assertTrue(repo.recordWake(thu, fri.atTime(8, 40)))
        assertFalse(repo.recordWake(thu, fri.atTime(9, 10)))
        assertEquals(fri.atTime(8, 40), db.sleepDao().get(thu)!!.wakeAt)
    }

    @Test fun observeRangeMaps() = runTest {
        repo.confirmBedtime(thu, fri.atTime(0, 20))
        repo.recordWake(fri, fri.plusDays(1).atTime(9, 0))
        repo.confirmBedtime(thu.minusDays(3), thu.minusDays(2).atTime(1, 0))
        val m = repo.observeRange(thu, fri).first()
        assertEquals(setOf(thu, fri), m.keys)
        assertEquals(fri.atTime(0, 20), m.getValue(thu).bedAt)
        assertEquals(fri.plusDays(1).atTime(9, 0), m.getValue(fri).wakeAt)
    }
}
