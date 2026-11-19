package app.brujula.claude

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.brujula.voice.FakeExecutor
import app.brujula.voice.FakeInterpreter
import app.brujula.voice.ok
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = app.brujula.TestBrujulaApp::class)
class PendingDictationsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val notified = mutableListOf<List<String>>()

    private fun TestScope.store(name: String = "d"): PendingDictations {
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "$name.preferences_pb") }
        return PendingDictations(ds) { notified += it }
    }

    @Test fun addKeepsOrderAndTimestamp() = runTest {
        val p = store()
        p.add("uno", 10L)
        p.add("dos \"con comillas\"\ny salto", 20L)
        assertEquals(listOf("uno", "dos \"con comillas\"\ny salto"), p.all().map { it.text })
        assertEquals(listOf(10L, 20L), p.all().map { it.at })
    }

    @Test fun drainAppliesInOrderEmptiesQueueAndNotifies() = runTest {
        val p = store()
        p.add("a", 1); p.add("b", 2)
        val interp = FakeInterpreter(ok("Ejercicio"), ok("Dentista"))
        val exec = FakeExecutor()
        p.drain(interp, exec)
        assertEquals(listOf("a", "b"), interp.texts)
        assertEquals(2, exec.applied.size)
        assertTrue(p.all().isEmpty())
        assertEquals(listOf(listOf("Ejercicio", "Dentista")), notified)
    }

    @Test fun drainStopsAtFirstFailureAndKeepsTheRest() = runTest {
        val p = store()
        p.add("a", 1); p.add("b", 2); p.add("c", 3)
        val interp = FakeInterpreter(ok("Ejercicio"), InterpretOutcome.Failed(DictationInterpreter.UNAVAILABLE, retryable = true), ok("Nunca"))
        p.drain(interp, FakeExecutor())
        assertEquals(listOf("a", "b"), interp.texts)
        assertEquals(listOf("b", "c"), p.all().map { it.text })
        assertEquals(listOf(listOf("Ejercicio")), notified)
    }

    @Test fun offlineAndKeyOutcomesStopWithoutLosingAnything() = runTest {
        val outcomes = listOf(InterpretOutcome.Offline, InterpretOutcome.NoKey, InterpretOutcome.InvalidKey)
        outcomes.forEachIndexed { i, o ->
            val p = store("o$i")
            p.add("a", 1)
            p.drain(FakeInterpreter(o), FakeExecutor())
            assertEquals(listOf("a"), p.all().map { it.text })
            p.drain(FakeInterpreter(ok("X")), FakeExecutor())
            assertTrue(p.all().isEmpty())
        }
        assertEquals(3, notified.size)
    }

    @Test fun itemIsRemovedOnlyIfApplied() = runTest {
        val p = store()
        p.add("a", 1)
        p.drain(FakeInterpreter(ok("Ejercicio")), FakeExecutor(failApply = true))
        assertEquals(listOf("a"), p.all().map { it.text })
        assertTrue(notified.isEmpty())
    }

    @Test fun refusedItemIsDroppedSoItDoesNotBlockTheQueue() = runTest {
        val p = store()
        p.add("a", 1); p.add("b", 2)
        p.drain(FakeInterpreter(InterpretOutcome.Refused(DictationInterpreter.REFUSED), ok("Dentista")), FakeExecutor())
        assertTrue(p.all().isEmpty())
        assertEquals(listOf(listOf("Dentista")), notified)
    }

    @Test fun twoDrainsNeverOverlap() = runTest {
        val p = store()
        p.add("a", 1); p.add("b", 2)
        val interp = FakeInterpreter(ok("X"), ok("Y"))
        interp.gate = CompletableDeferred()
        val d1 = async { p.drain(interp, FakeExecutor()) }
        val d2 = async { p.drain(interp, FakeExecutor()) }
        advanceUntilIdle()
        interp.gate!!.complete(Unit)
        d1.await(); d2.await()
        assertEquals(1, interp.maxRunning)
        assertEquals(listOf("a", "b"), interp.texts)
        assertTrue(p.all().isEmpty())
    }
}
