package app.brujula.planning

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.core.time.TimeSpan
import app.brujula.data.db.BrujulaDatabase
import app.brujula.data.db.ProposalEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = app.brujula.TestBrujulaApp::class)
class ProposalRepositoryTest {
    private lateinit var db: BrujulaDatabase
    private lateinit var repo: ProposalRepository
    private val fri = LocalDate.of(2026, 11, 20)
    private val sat = fri.plusDays(1)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ProposalRepository(db.proposalDao())
    }

    @After fun tearDown() { db.close() }

    private fun proposal(day: LocalDate, summary: String) = Proposal(
        day = day, createdAt = day.minusDays(1).atTime(21, 0), request = null, summary = summary,
        ops = listOf(PlanOp.Skip("A1", 1, day, "Leer", TimeSpan(day.atTime(21, 0), day.atTime(21, 30)), oneOff = false)),
        rejected = emptyList(), preview = emptyList(),
        totals = PlanTotals(0, 0, 480, day.atTime(23, 0)),
    )

    private suspend fun status(id: Long) = db.proposalDao().get(id)!!.status

    @Test fun savePendingSupersedesSameDayOnly() = runTest {
        val a = repo.savePending(proposal(fri, "a"))
        val b = repo.savePending(proposal(sat, "b"))
        val c = repo.savePending(proposal(fri, "c"))
        assertEquals("SUPERSEDED", status(a))
        assertEquals("PENDING", status(b))
        assertEquals("PENDING", status(c))
        assertEquals(c, repo.pendingFor(fri)!!.id)
        assertEquals("c", repo.pendingFor(fri)!!.summary)
        assertEquals(b, repo.pendingFor(sat)!!.id)
        assertNull(repo.pendingFor(sat.plusDays(1)))
        // Lo guardado vuelve igual (cambios, totales, estado).
        val read = repo.get(c)!!
        assertEquals(proposal(fri, "c").copy(id = c), read)
    }

    @Test fun unreadablePayloadIsDiscarded() = runTest {
        val id = db.proposalDao().insert(
            ProposalEntity(day = fri, createdAt = fri.atTime(7, 0), status = "PENDING", request = null, summary = "x", payload = "{basura"),
        )
        assertNull(repo.get(id))
        assertEquals("DISCARDED", status(id))
        assertNull(repo.pendingFor(fri))

        val other = db.proposalDao().insert(
            ProposalEntity(day = sat, createdAt = fri.atTime(7, 0), status = "PENDING", request = null, summary = "x", payload = "[]"),
        )
        assertNull(repo.pendingFor(sat))
        assertEquals("DISCARDED", status(other))
    }

    @Test fun unreadableAcceptedProposalIsNeverDiscarded() = runTest {
        val id = db.proposalDao().insert(
            ProposalEntity(day = fri, createdAt = fri.atTime(7, 0), status = "ACCEPTED", request = null, summary = "x", payload = "{basura", undo = "{}"),
        )
        assertNull(repo.get(id))
        assertEquals("ACCEPTED", status(id))
        assertEquals(setOf(fri), repo.acceptedDays())
    }

    @Test fun daoSupersedePendingLeavesAcceptedAndOtherDaysAlone() = runTest {
        val dao = db.proposalDao()
        val accepted = repo.savePending(proposal(fri, "aceptada"))
        dao.markAccepted(accepted, "{}", fri.atTime(8, 0))
        val pending = repo.savePending(proposal(fri, "pendiente"))
        val saturday = repo.savePending(proposal(sat, "sábado"))
        dao.supersedePending(fri)
        assertEquals("ACCEPTED", status(accepted))
        assertEquals("SUPERSEDED", status(pending))
        assertEquals("PENDING", status(saturday))
        assertEquals("{}", dao.get(accepted)!!.undo)
        assertEquals(fri.atTime(8, 0), dao.get(accepted)!!.acceptedAt)
    }

    @Test fun daoLatestByStatusIsTheNewestOfThatStatusAndDay() = runTest {
        val dao = db.proposalDao()
        val first = repo.savePending(proposal(fri, "1"))
        dao.setStatus(first, "DISCARDED")
        val second = repo.savePending(proposal(fri, "2"))
        dao.setStatus(second, "DISCARDED")
        val third = repo.savePending(proposal(fri, "3"))
        repo.savePending(proposal(sat, "otro día"))
        assertEquals(second, dao.latestByStatus(fri, "DISCARDED")!!.id)
        assertEquals(third, dao.latestByStatus(fri, "PENDING")!!.id)
        assertNull(dao.latestByStatus(fri, "ACCEPTED"))
    }

    @Test fun acceptedDaysAndIdsOnlyCountAccepted() = runTest {
        val dao = db.proposalDao()
        val a = repo.savePending(proposal(fri, "a"))
        repo.savePending(proposal(sat, "b"))
        assertEquals(emptySet<LocalDate>(), repo.acceptedDays())
        dao.markAccepted(a, null, fri.atTime(8, 0))
        assertEquals(setOf(fri), repo.acceptedDays())
        assertEquals(setOf(fri), repo.observeAcceptedDays().first())
        assertEquals(setOf(a), repo.observeAcceptedIds().first())
    }

    @Test fun discardMarksDiscarded() = runTest {
        val a = repo.savePending(proposal(fri, "a"))
        repo.discard(a)
        assertEquals("DISCARDED", status(a))
        assertNull(repo.pendingFor(fri))
        assertNotNull(repo.get(a))
        assertEquals(ProposalStatus.DISCARDED, repo.get(a)!!.status)
    }
}
