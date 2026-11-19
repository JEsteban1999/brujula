package app.brujula.calendar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.data.db.BrujulaDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(application = app.brujula.TestBrujulaApp::class)
class GoogleCopySyncTest {
    private lateinit var db: BrujulaDatabase
    private lateinit var repo: ActivityRepository
    private val writer = FakeGoogleCalendarWriter()
    private lateinit var sync: GoogleCopySync
    private var switchOn = true

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ActivityRepository(db.activityDao())
        sync = GoogleCopySync(writer, repo) { switchOn }
    }

    @After fun tearDown() { db.close() }

    private val base = Activity(
        title = "Dentista", type = ActivityType.APPOINTMENT, date = LocalDate.of(2026, 11, 20),
        start = LocalTime.of(16, 45), durationMin = 60, copyToGoogle = true,
    )

    private suspend fun save(a: Activity): Activity {
        val id = repo.upsert(a)
        return repo.get(id)!!
    }

    @Test fun savingWithCopyInsertsAndStoresId() = runTest {
        val a = save(base)
        assertEquals(GoogleCopyStatus.COPIED, sync.afterSave(a))
        assertEquals(100L, repo.get(a.id)!!.googleEventId)
        assertEquals("Dentista", writer.events[100L]!!.title)
    }

    @Test fun editingUpdatesSameEvent() = runTest {
        val a = save(base)
        sync.afterSave(a)
        val edited = save(a.copy(title = "Dentista 2", googleEventId = null))
        assertEquals(GoogleCopyStatus.COPIED, sync.afterSave(edited))
        assertEquals(1, writer.inserts)
        assertEquals(1, writer.updates)
        assertEquals("Dentista 2", writer.events[100L]!!.title)
        assertEquals(100L, repo.get(a.id)!!.googleEventId)
    }

    @Test fun turningCopyOffDeletesAndClearsId() = runTest {
        val a = save(base)
        sync.afterSave(a)
        val off = save(a.copy(copyToGoogle = false, googleEventId = null))
        assertEquals(GoogleCopyStatus.NOT_REQUESTED, sync.afterSave(off))
        assertEquals(emptyMap<Long, GoogleEventDraft>(), writer.events)
        assertNull(repo.get(a.id)!!.googleEventId)
    }

    @Test fun deletingActivityDeletesCopy() = runTest {
        val a = save(base)
        sync.afterSave(a)
        val withId = repo.get(a.id)!!
        repo.delete(a.id)
        sync.afterDelete(withId)
        assertEquals(emptyMap<Long, GoogleEventDraft>(), writer.events)
    }

    @Test fun noPermissionWritesNothing() = runTest {
        writer.permission = false
        val a = save(base)
        assertEquals(GoogleCopyStatus.NO_PERMISSION, sync.afterSave(a))
        assertEquals(0, writer.inserts)
        assertNull(repo.get(a.id)!!.googleEventId)
    }

    @Test fun noGoogleAccount() = runTest {
        writer.calendarId = null
        val a = save(base)
        assertEquals(GoogleCopyStatus.NO_GOOGLE_ACCOUNT, sync.afterSave(a))
        assertEquals(0, writer.inserts)
    }

    @Test fun copyNotRequestedDoesNothing() = runTest {
        val a = save(base.copy(copyToGoogle = false))
        assertEquals(GoogleCopyStatus.NOT_REQUESTED, sync.afterSave(a))
        assertEquals(0, writer.inserts + writer.updates + writer.deletes)
    }

    @Test fun staleEventIdIsRecreated() = runTest {
        val a = save(base)
        sync.afterSave(a)
        writer.events.clear() // borrado a mano en Google
        val edited = save(a.copy(googleEventId = null))
        assertEquals(GoogleCopyStatus.COPIED, sync.afterSave(edited))
        assertEquals(2, writer.inserts)
        assertEquals(101L, repo.get(a.id)!!.googleEventId)
        assertEquals(1, writer.events.size)
    }

    @Test fun writerFailureKeepsActivitySaved() = runTest {
        writer.failInsert = true
        val a = save(base)
        assertEquals(GoogleCopyStatus.FAILED, sync.afterSave(a))
        assertEquals("Dentista", repo.get(a.id)!!.title)
        assertNull(repo.get(a.id)!!.googleEventId)
    }

    @Test fun lostLinkAdoptsExistingCopyInsteadOfInserting() = runTest {
        val a = save(base)
        writer.events[500L] = draftFor(a)
        assertEquals(GoogleCopyStatus.COPIED, sync.afterSave(a))
        assertEquals(0, writer.inserts)
        assertEquals(500L, repo.get(a.id)!!.googleEventId)
    }

    @Test fun lostLinkWithDuplicatesKeepsOne() = runTest {
        val a = save(base)
        writer.events[500L] = draftFor(a)
        writer.events[501L] = draftFor(a)
        sync.afterSave(a)
        assertEquals(1, writer.events.size)
        assertEquals(0, writer.inserts)
    }

    @Test fun concurrentSavesInsertOnce() = runTest {
        writer.insertDelayMs = 50
        val a = save(base)
        val r = listOf(async { sync.afterSave(a) }, async { sync.afterSave(a) }).awaitAll()
        assertEquals(listOf(GoogleCopyStatus.COPIED, GoogleCopyStatus.COPIED), r)
        assertEquals(1, writer.inserts)
        assertEquals(1, writer.events.size)
    }

    @Test fun deleteAlsoRemovesCopiesWithoutStoredLink() = runTest {
        val a = save(base)
        writer.events[500L] = draftFor(a)
        sync.afterDelete(a)
        assertEquals(emptyMap<Long, GoogleEventDraft>(), writer.events)
    }

    @Test fun ownEventTargetUsesCollectionUri() {
        val t = ownEventTarget(42L)
        assertEquals(android.provider.CalendarContract.Events.CONTENT_URI, t.uri)
        assertEquals(
            "_id = ? AND customAppPackage = ? AND deleted = 0", t.selection,
        )
        assertEquals(listOf("42", "app.brujula"), t.args.toList())
    }

    // --- reconcile (I3) ---
    private val today = LocalDate.of(2026, 11, 18)

    @Test fun reconcileCopiesFutureAppointmentWithoutLink() = runTest {
        val a = save(base)
        sync.reconcile(today)
        assertEquals(1, writer.inserts)
        assertEquals(100L, repo.get(a.id)!!.googleEventId)
    }

    @Test fun reconcileSkipsPastOneOffButKeepsRecurring() = runTest {
        val past = save(base.copy(date = today.minusDays(1)))
        val recurring = save(base.copy(title = "Gym", date = today.minusDays(30), repeat = app.brujula.activities.Repeat.WEEKLY))
        sync.reconcile(today)
        assertNull(repo.get(past.id)!!.googleEventId)
        assertEquals(100L, repo.get(recurring.id)!!.googleEventId)
        assertEquals(1, writer.inserts)
    }

    @Test fun reconcileSkipsActivitiesWithCopyOff() = runTest {
        save(base.copy(copyToGoogle = false))
        sync.reconcile(today)
        assertEquals(0, writer.inserts + writer.updates + writer.deletes)
    }

    @Test fun reconcileTwiceInsertsOnce() = runTest {
        save(base)
        sync.reconcile(today)
        sync.reconcile(today)
        assertEquals(1, writer.inserts)
        assertEquals(1, writer.events.size)
    }

    @Test fun reconcileDoesNothingWithSwitchOffNoPermissionOrNoAccount() = runTest {
        val a = save(base)
        switchOn = false
        sync.reconcile(today)
        switchOn = true
        writer.permission = false
        sync.reconcile(today)
        writer.permission = true
        writer.calendarId = null
        sync.reconcile(today)
        assertEquals(0, writer.inserts)
        assertNull(repo.get(a.id)!!.googleEventId)
    }

    // --- interruptor global (M5) ---
    @Test fun saveWithSwitchOffDoesNotCallWriterAndShowsNoNotice() = runTest {
        switchOn = false
        val a = save(base)
        assertEquals(GoogleCopyStatus.NOT_REQUESTED, sync.afterSave(a))
        assertEquals(0, writer.inserts + writer.updates + writer.deletes)
        assertNull(repo.get(a.id)!!.googleEventId)
    }

    @Test fun switchOffKeepsExistingCopyOnSaveButDeleteStillRemovesIt() = runTest {
        val a = save(base)
        sync.afterSave(a)
        switchOn = false
        val edited = save(a.copy(title = "Otro", googleEventId = null))
        assertEquals(GoogleCopyStatus.NOT_REQUESTED, sync.afterSave(edited))
        assertEquals(1, writer.events.size)
        assertEquals("Dentista", writer.events[100L]!!.title)
        assertEquals(0, writer.updates)
        val withId = repo.get(a.id)!!
        repo.delete(a.id)
        sync.afterDelete(withId)
        assertEquals(emptyMap<Long, GoogleEventDraft>(), writer.events)
    }

    // --- M2 ---
    @Test fun copyTurnedOffWithLostIdStillRemovesExistingCopy() = runTest {
        val a = save(base.copy(copyToGoogle = false))
        writer.events[500L] = draftFor(a)
        assertEquals(GoogleCopyStatus.NOT_REQUESTED, sync.afterSave(a))
        assertEquals(emptyMap<Long, GoogleEventDraft>(), writer.events)
    }
}
