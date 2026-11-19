package app.brujula.claude

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.calendar.WorkClassificationRepository
import app.brujula.data.db.BrujulaDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutoClassifierTest {
    private lateinit var db: BrujulaDatabase
    private lateinit var repo: WorkClassificationRepository
    private val gateway = FakeClaudeGateway()
    private var online = true

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = WorkClassificationRepository(db.workClassificationDao())
    }

    @After fun tearDown() { db.close() }

    private fun TestScopeAuto(scope: kotlinx.coroutines.CoroutineScope) =
        AutoClassifier(EventClassifier(gateway), repo, scope) { online }

    private fun ok(vararg m: Pair<String, Boolean>) =
        GatewayResult.Ok(ClassificationResult(m.map { TitleMark(it.first, it.second) }))

    @Test fun sendsOnlyUnknownKeysAndStoresResultAsAuto() = runTest {
        repo.set("dentista", false)
        gateway.classification = ok("Reunión" to true)
        val auto = TestScopeAuto(backgroundScope)
        auto.request(mapOf("dentista" to "Dentista", "reunion" to "Reunión"), known = repo.observe().first().keys)?.join()
        assertEquals(1, gateway.classifyCalls)
        assertTrue(!gateway.classifyUsers.single().contains("Dentista"))
        assertEquals(mapOf("dentista" to false, "reunion" to true), repo.observe().first())
        assertEquals("AUTO", db.workClassificationDao().sourceOf("reunion"))
    }

    @Test fun userMarkMadeWhileCallInFlightWins() = runTest {
        gateway.classifyWith = {
            // El usuario marca mientras Claude responde.
            kotlinx.coroutines.runBlocking { repo.set("reunion", false) }
            ok("Reunión" to true)
        }
        val auto = TestScopeAuto(backgroundScope)
        auto.request(mapOf("reunion" to "Reunión"), known = emptySet())?.join()
        assertEquals(mapOf("reunion" to false), repo.observe().first())
    }

    @Test fun atMostOnceEvenAfterFailure() = runTest {
        gateway.classification = GatewayResult.Error(retryable = false)
        val auto = TestScopeAuto(backgroundScope)
        repeat(3) { auto.request(mapOf("reunion" to "Reunión"), known = emptySet())?.join() }
        assertEquals(1, gateway.classifyCalls)
        assertTrue(repo.observe().first().isEmpty())
    }

    @Test fun invalidKeyTimeoutAndRetryableErrorsAreRetriedLaterButBoundedByDistinctEmissions() = runTest {
        for (f in listOf(GatewayResult.InvalidKey, GatewayResult.Timeout, GatewayResult.Error(retryable = true))) {
            gateway.classifyCalls = 0
            gateway.classification = f
            val auto = TestScopeAuto(backgroundScope)
            auto.request(mapOf("reunion" to "Reunión"), known = emptySet())?.join()
            // Misma clave en la misma emisión no se repite; en otra emisión (clave liberada) sí se reintenta.
            auto.request(mapOf("reunion" to "Reunión"), known = emptySet())?.join()
            assertEquals("$f", 2, gateway.classifyCalls)
            gateway.classification = ok("Reunión" to true)
            auto.request(mapOf("reunion" to "Reunión"), known = emptySet())?.join()
            auto.request(mapOf("reunion" to "Reunión"), known = repo.observe().first().keys)
            assertEquals("$f", 3, gateway.classifyCalls)
        }
    }

    @Test fun blankKeysAreNeverClassified() = runTest {
        val auto = TestScopeAuto(backgroundScope)
        auto.request(mapOf("" to ""), known = emptySet())?.join()
        assertEquals(0, gateway.classifyCalls)
    }

    @Test fun offlineDoesNothingAndRetriesLater() = runTest {
        gateway.classification = ok("Reunión" to true)
        val auto = TestScopeAuto(backgroundScope)
        online = false
        auto.request(mapOf("reunion" to "Reunión"), known = emptySet())?.join()
        assertEquals(0, gateway.classifyCalls)
        online = true
        auto.request(mapOf("reunion" to "Reunión"), known = emptySet())?.join()
        assertEquals(1, gateway.classifyCalls)
        assertEquals(true, repo.observe().first()["reunion"])
    }

    @Test fun noKeyDoesNothingAndRetriesLater() = runTest {
        gateway.classification = GatewayResult.NoKey
        val auto = TestScopeAuto(backgroundScope)
        auto.request(mapOf("reunion" to "Reunión"), known = emptySet())?.join()
        gateway.classification = ok("Reunión" to true)
        auto.request(mapOf("reunion" to "Reunión"), known = emptySet())?.join()
        assertEquals(2, gateway.classifyCalls)
        assertEquals(true, repo.observe().first()["reunion"])
    }

    @Test fun gatewayExceptionDoesNotCrash() = runTest {
        gateway.classifyWith = { throw IllegalStateException("boom") }
        val auto = TestScopeAuto(backgroundScope)
        auto.request(mapOf("reunion" to "Reunión"), known = emptySet())?.join()
        assertTrue(repo.observe().first().isEmpty())
    }
}
