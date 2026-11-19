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
import kotlinx.coroutines.flow.onEach
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

    @Test fun drainRequestedDuringRunningDrainIsNotLostAndItemAddedMidDrainIsProcessed() = runTest {
        val p = store()
        p.add("a", 1)
        val interp = FakeInterpreter(ok("X"), ok("Y"))
        interp.gate = CompletableDeferred()
        val exec = FakeExecutor()
        val d1 = async { p.drain(interp, exec) }
        runCurrent()
        p.add("b", 2)
        val d2 = async { p.drain(interp, exec) }
        runCurrent()
        assertTrue(d2.isCompleted)
        interp.gate!!.complete(Unit)
        advanceUntilIdle()
        d1.await()
        assertTrue(p.all().isEmpty())
        assertEquals(listOf("a", "b"), interp.texts)
        assertEquals(1, interp.maxRunning)
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

    // --- I1: fecha relativa del instante dictado ---

    private val bogota = java.time.ZoneId.of("America/Bogota")
    private val mon2300 = java.time.LocalDate.of(2026, 11, 23).atTime(23, 0)
    private val tue0800 = java.time.LocalDate.of(2026, 11, 24).atTime(8, 0)

    private fun queuedInterpreter(gateway: FakeClaudeGateway) = DictationInterpreter(gateway, { at ->
        dictationCtx(now = tue0800, dictatedAt = at?.let { java.time.Instant.ofEpochMilli(it).atZone(bogota).toLocalDateTime() })
    })

    private fun oneOff(date: String, start: String) = GatewayResult.Ok(
        DictationResult(
            listOf(DictationAction(type = "create_activity", title = "Dentista", activityType = "APPOINTMENT", date = date, start = start, durationMin = 60, repeat = "NONE")),
            "Listo",
        ),
    )

    @Test fun drainPassesTheDictationInstantToTheInterpreter() = runTest {
        val p = store()
        p.add("a", 111L); p.add("b", 222L)
        val interp = FakeInterpreter(ok("X"), ok("Y"))
        p.drain(interp, FakeExecutor())
        assertEquals(listOf<Long?>(111L, 222L), interp.dictatedAts)
    }

    @Test fun mananaInAQueuedDictationMeansTheDayAfterTheDictationDayNotAfterTheDrainDay() = runTest {
        val gateway = FakeClaudeGateway(dictation = oneOff("2026-11-24", "09:00"))
        val p = store()
        p.add("mañana a las 9 dentista", mon2300.atZone(bogota).toInstant().toEpochMilli())
        val exec = FakeExecutor()
        p.drain(queuedInterpreter(gateway), exec)
        val prompt = gateway.lastUser!!
        assertTrue(prompt, prompt.contains("Mañana es martes 24 de noviembre de 2026"))
        assertTrue(prompt, prompt.contains("Hoy (día lógico) es lunes 23 de noviembre de 2026"))
        assertTrue(prompt, prompt.contains("El usuario dictó esto el lunes 23 de noviembre de 2026 a las 23:00"))
        assertEquals(1, exec.applied.size)
        assertTrue(p.all().isEmpty())
        assertTrue(notes.dropped.isEmpty())
    }

    @Test fun queuedOneOffThatIsNowInThePastIsInvalidAndTheUserIsTold() = runTest {
        // "hoy a las 23:30" dictado el lunes 23:00 y procesado el martes 08:00.
        val gateway = FakeClaudeGateway(dictation = oneOff("2026-11-23", "23:30"))
        val p = store()
        p.add("hoy a las 11:30 dentista", mon2300.atZone(bogota).toInstant().toEpochMilli())
        val exec = FakeExecutor()
        p.drain(queuedInterpreter(gateway), exec)
        assertTrue(exec.applied.isEmpty())
        assertTrue(p.all().isEmpty())
        assertEquals(1, notes.dropped.size)
        assertEquals("hoy a las 11:30 dentista", notes.dropped.single().first)
        assertTrue(notes.dropped.single().second, notes.dropped.single().second.contains("ya pasó"))
    }

    // --- I3: arranque y reintentos ---

    @Test fun validatedTransitionSeededWithAnAlreadyValidatedNetworkDoesNotFire() {
        val t = ValidatedTransition(initiallyValidated = true)
        assertFalse(t.onChange(true))
        assertFalse(t.onChange(true))
        assertFalse(t.onChange(false))
        assertTrue(t.onChange(true))
        assertTrue(ValidatedTransition(initiallyValidated = false).onChange(true))
    }

    /** Dispara [onEmptyRead] la primera vez que una lectura ve la cola vacía; nada más. */
    private class HookedDataStore(
        private val inner: DataStore<Preferences>,
        var onEmptyRead: (suspend () -> Unit)? = null,
    ) : DataStore<Preferences> {
        private val key = stringPreferencesKey("pending_dictations")
        override val data = inner.data.onEach { prefs ->
            val raw = prefs[key]
            if (raw == null || raw == "[]") onEmptyRead?.let { hook -> onEmptyRead = null; hook() }
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = inner.updateData(transform)
    }

    @Test fun drainRequestedAfterThePassSawAnEmptyQueueRunsAgain() = runTest {
        val hooked = HookedDataStore(dataStore())
        val p = PendingDictations(hooked, notes)
        val interp = FakeInterpreter(ok("X"), ok("Y"))
        val exec = FakeExecutor()
        p.add("a", 1)
        // La cola se ve vacía; justo después llega "b" con su petición de drenado (la pasada vigente aún no soltó el candado).
        hooked.onEmptyRead = { p.add("b", 2); p.drain(interp, exec) }
        p.drain(interp, exec)
        assertEquals(listOf("a", "b"), interp.texts)
        assertTrue(p.all().isEmpty())
    }

    @Test fun passThatStoppedOnAFailureDoesNotRunAgainEvenIfAnotherDrainWasRequested() = runTest {
        val p = store()
        p.add("a", 1)
        val interp = FakeInterpreter(retryable)
        interp.gate = CompletableDeferred()
        val d1 = async { p.drain(interp, FakeExecutor()) }
        runCurrent()
        p.drain(interp, FakeExecutor()) // petición durante la pasada
        interp.gate!!.complete(Unit)
        advanceUntilIdle()
        d1.await()
        assertEquals(listOf("a"), interp.texts)
        assertEquals(listOf(1), p.all().map { it.attempts })
    }

    @Test fun networkFailureStopsKeepsTheItemAndDoesNotCountAnAttempt() = runTest {
        val p = store()
        p.add("a", 1); p.add("b", 2)
        val net = InterpretOutcome.Failed(DictationInterpreter.OFFLINE, retryable = true, network = true)
        repeat(5) { p.drain(FakeInterpreter(net), FakeExecutor()) }
        assertEquals(listOf("a", "b"), p.all().map { it.text })
        assertEquals(listOf(0, 0), p.all().map { it.attempts })
        assertTrue(notes.dropped.isEmpty())
    }

    // --- M6: error de configuración ---

    @Test fun configurationErrorStopsWithoutDroppingOrCountingAnAttempt() = runTest {
        val p = store()
        p.add("a", 1)
        val cfg = InterpretOutcome.Failed("config", retryable = false, config = true)
        repeat(5) { p.drain(FakeInterpreter(cfg), FakeExecutor()) }
        assertEquals(listOf("a"), p.all().map { it.text })
        assertEquals(0, p.all().single().attempts)
        assertTrue(notes.dropped.isEmpty())
        p.drain(FakeInterpreter(ok("X")), FakeExecutor())
        assertTrue(p.all().isEmpty())
    }

    @Test fun configErrorStopWithAQueueNotifiesOncePerProcess() = runTest {
        val p = store()
        p.add("a", 1); p.add("b", 2)
        val cfg = InterpretOutcome.Failed("config", retryable = false, config = true)
        p.drain(FakeInterpreter(cfg), FakeExecutor())
        p.drain(FakeInterpreter(cfg), FakeExecutor())
        assertEquals(listOf(2), notes.configStalled)
        assertEquals("No pude procesar 2 dictados pendientes: Claude rechazó la solicitud. Revisa tu saldo y tu clave en platform.claude.com.", PendingCopy.configNotice(2))
        assertEquals("No pude procesar 1 dictado pendiente: Claude rechazó la solicitud. Revisa tu saldo y tu clave en platform.claude.com.", PendingCopy.configNotice(1))
    }

    // --- M1: aviso de clave ---

    @Test fun stoppingOnMissingOrInvalidKeyNotifiesOncePerProcessWithTheQueueSize() = runTest {
        val p = store()
        p.add("a", 1); p.add("b", 2)
        p.drain(FakeInterpreter(InterpretOutcome.NoKey), FakeExecutor())
        p.drain(FakeInterpreter(InterpretOutcome.InvalidKey), FakeExecutor())
        p.drain(FakeInterpreter(InterpretOutcome.NoKey), FakeExecutor())
        assertEquals(listOf(2), notes.needsKey)
    }

    @Test fun keyNoticeIsNotSentForOtherStopsOrAnEmptyQueue() = runTest {
        val p = store()
        p.drain(FakeInterpreter(), FakeExecutor())
        p.add("a", 1)
        p.drain(FakeInterpreter(InterpretOutcome.Offline), FakeExecutor())
        assertTrue(notes.needsKey.isEmpty())
    }

    @Test fun notificationCopyIsNeutral() {
        assertEquals("Agendé tu dictado pendiente: Ejercicio; Dentista", PendingCopy.successTitle(listOf("Ejercicio", "Dentista")))
        assertEquals("Tienes 3 dictados pendientes. Revisa tu clave de Claude en Tu rutina.", PendingCopy.keyNotice(3))
        assertEquals("Tienes 1 dictado pendiente. Revisa tu clave de Claude en Tu rutina.", PendingCopy.keyNotice(1))
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
