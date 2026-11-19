package app.brujula.claude

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.brujula.voice.FakeExecutor
import app.brujula.voice.FakeInterpreter
import app.brujula.voice.RecordingNotifier
import app.brujula.voice.activity
import app.brujula.voice.ok
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val notes = RecordingNotifier()
    private val retryable = InterpretOutcome.Failed(DictationInterpreter.UNAVAILABLE, retryable = true)

    private fun TestScope.dataStore(name: String = "d"): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "$name.preferences_pb") }

    private fun TestScope.store(name: String = "d") = PendingDictations(dataStore(name), notes)

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
        assertEquals(listOf(listOf("Ejercicio", "Dentista") to emptyList<String>()), notes.scheduled)
        assertTrue(notes.dropped.isEmpty())
    }

    @Test fun drainStopsAtFirstFailureKeepsTheRestAndCountsOneAttempt() = runTest {
        val p = store()
        p.add("a", 1); p.add("b", 2); p.add("c", 3)
        val interp = FakeInterpreter(ok("Ejercicio"), retryable, ok("Nunca"))
        p.drain(interp, FakeExecutor())
        assertEquals(listOf("a", "b"), interp.texts)
        assertEquals(listOf("b", "c"), p.all().map { it.text })
        assertEquals(listOf(1, 0), p.all().map { it.attempts })
        assertEquals(listOf(listOf("Ejercicio") to emptyList<String>()), notes.scheduled)
    }

    @Test fun offlineAndKeyOutcomesStopWithoutLosingOrCounting() = runTest {
        val outcomes = listOf(InterpretOutcome.Offline, InterpretOutcome.NoKey, InterpretOutcome.InvalidKey)
        outcomes.forEachIndexed { i, o ->
            val p = store("o$i")
            p.add("a", 1)
            p.drain(FakeInterpreter(o), FakeExecutor())
            assertEquals(listOf("a"), p.all().map { it.text })
            assertEquals(0, p.all().single().attempts)
            p.drain(FakeInterpreter(ok("X")), FakeExecutor())
            assertTrue(p.all().isEmpty())
        }
        assertEquals(3, notes.scheduled.size)
        assertTrue(notes.dropped.isEmpty())
    }

    @Test fun itemFailingThreeTimesIsDroppedAndNotified() = runTest {
        val p = store()
        p.add("a", 1)
        repeat(2) {
            p.drain(FakeInterpreter(retryable), FakeExecutor())
            assertEquals(listOf("a"), p.all().map { it.text })
        }
        assertTrue(notes.dropped.isEmpty())
        p.drain(FakeInterpreter(retryable), FakeExecutor())
        assertTrue(p.all().isEmpty())
        assertEquals(listOf("a" to DictationInterpreter.UNAVAILABLE), notes.dropped)
    }

    @Test fun applyThatCreatesNothingCountsAsAttemptThenDrops() = runTest {
        val p = store()
        p.add("a", 1)
        repeat(3) { p.drain(FakeInterpreter(ok("Ejercicio")), FakeExecutor(failApply = true)) }
        assertTrue(p.all().isEmpty())
        assertEquals(1, notes.dropped.size)
        assertTrue(notes.scheduled.isEmpty())
    }

    @Test fun questionOnlyItemIsRemovedAndUserIsToldTheQuestion() = runTest {
        val p = store()
        p.add("ejercicio y recuérdame llevar", 1)
        p.drain(FakeInterpreter(InterpretOutcome.Ok(listOf(Validated.Question("¿Qué te recuerdo llevar?")), "Te cortaste")), FakeExecutor())
        assertTrue(p.all().isEmpty())
        assertEquals(listOf("ejercicio y recuérdame llevar" to "¿Qué te recuerdo llevar?"), notes.dropped)
    }

    @Test fun refusedAndNonRetryableAreNotified() = runTest {
        val p = store()
        p.add("a", 1); p.add("b", 2); p.add("c", 3)
        p.drain(
            FakeInterpreter(
                InterpretOutcome.Refused(DictationInterpreter.REFUSED),
                InterpretOutcome.Failed("no", retryable = false),
                ok("Dentista"),
            ),
            FakeExecutor(),
        )
        assertTrue(p.all().isEmpty())
        assertEquals(listOf("a" to DictationInterpreter.REFUSED, "b" to "no"), notes.dropped)
        assertEquals(listOf(listOf("Dentista")), notes.scheduled.map { it.first })
    }

    @Test fun partiallyInvalidSuccessNotificationIncludesTheReason() = runTest {
        val p = store()
        p.add("a", 1)
        p.drain(FakeInterpreter(InterpretOutcome.Ok(listOf(activity("Ejercicio"), Validated.Invalid("La fecha ya pasó")), "ok")), FakeExecutor())
        assertTrue(p.all().isEmpty())
        assertEquals(listOf(listOf("Ejercicio") to listOf("La fecha ya pasó")), notes.scheduled)
    }

    @Test fun overlappingDrainReturnsImmediately() = runTest {
        val p = store()
        p.add("a", 1); p.add("b", 2)
        val interp = FakeInterpreter(ok("X"), ok("Y"))
        interp.gate = CompletableDeferred()
        val d1 = async { p.drain(interp, FakeExecutor()) }
        runCurrent()
        val d2 = async { p.drain(interp, FakeExecutor()) }
        runCurrent()
        assertTrue(d2.isCompleted)
        assertFalse(d1.isCompleted)
        interp.gate!!.complete(Unit)
        advanceUntilIdle()
        d1.await()
        assertEquals(1, interp.maxRunning)
        assertEquals(listOf("a", "b"), interp.texts)
        assertTrue(p.all().isEmpty())
    }

    @Test fun damagedEntriesAreSkippedAndUnparseableJsonIsBackedUp() = runTest {
        val ds = dataStore()
        val p = PendingDictations(ds, notes)
        val key = stringPreferencesKey("pending_dictations")
        ds.edit { it[key] = """[{"t":"bueno","a":1},{"x":1},{"t":"otro","a":2}]""" }
        assertEquals(listOf("bueno", "otro"), p.all().map { it.text })
        ds.edit { it[key] = "{{no es json" }
        assertTrue(p.all().isEmpty())
        p.add("nuevo", 5)
        val prefs = ds.data.first()
        assertEquals("{{no es json", prefs[stringPreferencesKey("pending_dictations_backup")])
        assertEquals(listOf("nuevo"), p.all().map { it.text })
    }

    @Test fun validatedTransitionFiresOnceUntilNetworkIsLost() {
        val t = ValidatedTransition()
        assertFalse(t.onChange(false))
        assertTrue(t.onChange(true))
        assertFalse(t.onChange(true))
        assertFalse(t.onChange(true))
        assertFalse(t.onChange(false))
        assertTrue(t.onChange(true))
    }
}
