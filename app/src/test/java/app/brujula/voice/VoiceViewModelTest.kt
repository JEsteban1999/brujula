package app.brujula.voice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelStore
import app.brujula.claude.DictationInterpreter
import app.brujula.claude.InterpretOutcome
import app.brujula.claude.PendingDictations
import app.brujula.claude.Validated
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class VoiceViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Rig(
        val speech: FakeSpeechInput, val interp: FakeInterpreter, val exec: FakeExecutor,
        val pending: PendingDictations, val vm: VoiceViewModel,
    )

    private var drains = 0

    private fun TestScope.rig(speech: FakeSpeechInput = FakeSpeechInput(), vararg outcomes: InterpretOutcome, queueWithoutKey: Boolean = false, floating: Boolean = false, vmScope: CoroutineScope? = null): Rig {
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "p.preferences_pb") }
        val pending = PendingDictations(ds)
        val interp = FakeInterpreter(*outcomes)
        val exec = FakeExecutor()
        return Rig(speech, interp, exec, pending, VoiceViewModel(speech, interp, exec, pending, now = { 1000L }, scopeOverride = vmScope ?: backgroundScope, leaveScope = backgroundScope, requestDrain = { drains++ }, queueWithoutKey = queueWithoutKey, floating = floating))
    }

    @Test fun finalSpeechGoesThinkingThenResultWithLabelsAndUndo() = runTest {
        val r = rig(outcomes = arrayOf(ok("Ejercicio", "Dentista", reply = "Agendé dos cosas")))
        val gate = CompletableDeferred<Unit>().also { r.interp.gate = it }
        r.vm.start()
        assertEquals(VoiceState.Listening(""), r.vm.state.value)
        r.speech.emit(SpeechEvent.Partial("mañana ejer"))
        assertEquals(VoiceState.Listening("mañana ejer"), r.vm.state.value)
        r.speech.emit(SpeechEvent.Final("mañana ejercicio"))
        runCurrent()
        assertEquals(VoiceState.Thinking, r.vm.state.value)
        gate.complete(Unit)
        runCurrent()
        val s = r.vm.state.value as VoiceState.Result
        assertEquals(listOf("Ejercicio", "Dentista"), s.created)
        assertEquals("Agendé dos cosas", s.reply)
        assertTrue(s.undoAvailable)
        assertEquals(listOf("mañana ejercicio"), r.interp.texts)
    }

    private val friday = java.time.LocalDate.of(2026, 11, 20)

    @Test fun planOnlyOpensProposalWithoutApplying() = runTest {
        val r = rig(outcomes = arrayOf(InterpretOutcome.Ok(listOf(Validated.PlanRequest(friday, "hoy no voy a entrenar")), "Te armo una propuesta")))
        r.vm.submitText("hoy no voy a entrenar")
        runCurrent()
        assertEquals(VoiceState.OpenProposal(friday, "hoy no voy a entrenar"), r.vm.state.value)
        assertTrue("Nada se aplica por voz", r.exec.applied.isEmpty())
        assertTrue(r.pending.all().isEmpty())
        r.vm.undo() // sin nada que deshacer
        runCurrent()
        assertTrue(r.exec.undone.isEmpty())
    }

    @Test fun planOnlyWithMessagesShowsThemBeforeOfferingProposal() = runTest {
        val plan = Validated.PlanRequest(friday, "pasa el gym y borra el hábito")
        val r = rig(outcomes = arrayOf(InterpretOutcome.Ok(listOf(plan, Validated.Unsupported("Los hábitos se cambian a mano.")), "ok")))
        r.vm.submitText("pasa el gym y borra el hábito")
        runCurrent()
        val s = r.vm.state.value as VoiceState.Result
        assertEquals(listOf("Los hábitos se cambian a mano."), s.messages)
        assertEquals(plan, s.proposal)
        assertTrue(r.exec.applied.isEmpty())
        // No dice "No agendé nada": lo que sigue es la Propuesta.
        assertEquals(VoiceCopy.PLAN_OFFERED, s.title)
        assertEquals("Claude puede reorganizar tu día", VoiceCopy.PLAN_OFFERED)
        assertEquals(VoiceCopy.NOTHING_SCHEDULED, s.copy(proposal = null).title)
        assertEquals(VoiceCopy.SCHEDULED, s.copy(created = listOf("Dentista")).title)
    }

    @Test fun mixedAppliesCreatesAndOffersProposal() = runTest {
        val plan = Validated.PlanRequest(friday, "agenda dentista y pasa el gym a la tarde")
        val r = rig(outcomes = arrayOf(InterpretOutcome.Ok(listOf(activity("Dentista"), plan), "Listo")))
        r.vm.submitText("agenda dentista y pasa el gym a la tarde")
        runCurrent()
        val s = r.vm.state.value as VoiceState.Result
        assertEquals(listOf("Dentista"), s.created)
        assertEquals(plan, s.proposal)
        assertTrue(s.undoAvailable)
        assertEquals(1, r.exec.applied.flatten().filterIsInstance<Validated.Activity>().size)
        r.vm.undo()
        runCurrent()
        assertEquals(VoiceState.Undone, r.vm.state.value)
    }

    @Test fun needMoreInfoThenAnswerIsSentAsOneDictation() = runTest {
        val r = rig(outcomes = arrayOf(
            InterpretOutcome.Ok(listOf(Validated.Question("¿Qué te recuerdo llevar?")), "Te cortaste"),
            ok("Ejercicio"),
        ))
        r.vm.submitText("ejercicio mañana y recuérdame llevar")
        runCurrent()
        assertEquals(VoiceState.NeedsMore("¿Qué te recuerdo llevar?"), r.vm.state.value)
        assertTrue(r.exec.applied.isEmpty())
        r.vm.submitText("la toalla")
        runCurrent()
        assertEquals("ejercicio mañana y recuérdame llevar. la toalla", r.interp.texts.last())
        assertTrue(r.vm.state.value is VoiceState.Result)
    }

    @Test fun offlineQueuesTextAndShowsOfflineCopy() = runTest {
        val r = rig(outcomes = arrayOf(InterpretOutcome.Offline))
        r.vm.submitText("mañana dentista")
        runCurrent()
        assertEquals(VoiceState.SavedOffline, r.vm.state.value)
        assertEquals(DictationInterpreter.OFFLINE, VoiceCopy.OFFLINE)
        assertEquals(listOf("mañana dentista"), r.pending.all().map { it.text })
        assertEquals(1000L, r.pending.all().single().at)
    }

    @Test fun noKeyKeepsTextAndDoesNotQueue() = runTest {
        val r = rig(outcomes = arrayOf(InterpretOutcome.NoKey))
        r.vm.submitText("algo")
        runCurrent()
        assertEquals(VoiceState.Error(DictationInterpreter.NO_KEY, retry = false), r.vm.state.value)
        assertEquals("algo", r.vm.draft.value)
        assertTrue(r.pending.all().isEmpty())
    }

    @Test fun noKeyQueuesWhenQueueWithoutKey() = runTest {
        val before = drains
        val r = rig(outcomes = arrayOf(InterpretOutcome.NoKey), queueWithoutKey = true)
        r.vm.submitText("algo")
        runCurrent()
        assertEquals(VoiceState.SavedNeedsKey, r.vm.state.value)
        assertEquals(listOf("algo"), r.pending.all().map { it.text })
        assertEquals("Sin pedir drenado", before, drains)
    }

    @Test fun invalidKeyQueuesWhenQueueWithoutKey() = runTest {
        val r = rig(outcomes = arrayOf(InterpretOutcome.InvalidKey), queueWithoutKey = true)
        r.vm.submitText("algo")
        runCurrent()
        assertEquals(VoiceState.SavedNeedsKey, r.vm.state.value)
        assertEquals(listOf("algo"), r.pending.all().map { it.text })
    }

    @Test fun floatingPartialThenNoMatchThenRetryStillSubmits() = runTest {
        val r = rig(outcomes = arrayOf(ok("Ejercicio")), floating = true)
        r.vm.start()
        r.speech.emit(SpeechEvent.Partial("mañana ejer"))
        r.speech.emit(SpeechEvent.Failure(SpeechFailure.NO_MATCH))
        assertEquals("", r.vm.draft.value)
        r.vm.retry()
        r.speech.emit(SpeechEvent.Final("mañana ejercicio"))
        runCurrent()
        assertTrue(r.vm.state.value is VoiceState.Result)
        assertEquals(listOf("mañana ejercicio"), r.interp.texts)
    }

    @Test fun floatingTimeoutIsQueued() = runTest {
        val r = rig(outcomes = arrayOf(InterpretOutcome.Failed(DictationInterpreter.TOO_SLOW, retryable = true)), floating = true)
        r.vm.submitText("ejercicio")
        runCurrent()
        assertEquals(VoiceState.SavedOffline, r.vm.state.value)
        assertEquals(listOf("ejercicio"), r.pending.all().map { it.text })
    }

    /** Orden de producción: `clear()` cierra el alcance de la pantalla y solo después llama a `onCleared`. */
    private fun TestScope.leave(r: Rig, vmScope: CoroutineScope) {
        vmScope.cancel()
        ViewModelStore().also { it.put("k", r.vm) }.clear()
        runCurrent()
    }

    private fun TestScope.screenScope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    private fun TestScope.needsMore(vmScope: CoroutineScope): Rig {
        val r = rig(outcomes = arrayOf(InterpretOutcome.Ok(listOf(Validated.Question("¿A qué hora?")), ""), ok("Dentista")), floating = true, vmScope = vmScope)
        r.vm.submitText("dentista")
        runCurrent()
        assertTrue(r.vm.state.value is VoiceState.NeedsMore)
        return r
    }

    @Test fun floatingLeavingNeedsMoreQueuesOnce() = runTest {
        val vs = screenScope()
        val r = needsMore(vs)
        r.vm.start()
        r.speech.emit(SpeechEvent.Partial("a las cuatro"))
        leave(r, vs)
        assertEquals(listOf("dentista. a las cuatro"), r.pending.all().map { it.text })
    }

    @Test fun floatingLeavingWhileThinkingAnswerQueuesFullTextOnce() = runTest {
        val vs = screenScope()
        val r = needsMore(vs)
        r.interp.gate = CompletableDeferred()
        r.vm.start()
        r.speech.emit(SpeechEvent.Partial("a las"))
        r.speech.emit(SpeechEvent.Final("a las cuatro"))
        runCurrent()
        assertEquals(VoiceState.Thinking, r.vm.state.value)
        leave(r, vs)
        assertEquals(listOf("dentista. a las cuatro"), r.pending.all().map { it.text })
    }

    @Test fun floatingLeavingAfterApplyHandedOffQueuesNothing() = runTest {
        val vs = screenScope()
        val r = rig(outcomes = arrayOf(ok("Ejercicio")), floating = true, vmScope = vs)
        r.exec.applyGate = CompletableDeferred()
        r.vm.submitText("ejercicio")
        runCurrent()
        leave(r, vs)
        r.exec.applyGate!!.complete(Unit)
        runCurrent()
        assertTrue(r.pending.all().isEmpty())
        assertEquals(1, r.exec.applied.size)
    }

    @Test fun floatingQueueForAppThenClearQueuesOnce() = runTest {
        val vs = screenScope()
        val r = needsMore(vs)
        r.vm.start()
        r.speech.emit(SpeechEvent.Partial("a las cuatro"))
        r.speech.emit(SpeechEvent.Failure(SpeechFailure.NO_MATCH))
        r.vm.queueForApp()
        runCurrent()
        leave(r, vs)
        assertEquals(1, r.pending.all().size)
    }

    @Test fun floatingStalePartialDoesNotGarbleQueuedBase() = runTest {
        val vs = screenScope()
        val r = rig(outcomes = arrayOf(InterpretOutcome.Ok(listOf(Validated.Question("¿A qué hora?")), "")), floating = true, vmScope = vs)
        r.vm.start()
        r.speech.emit(SpeechEvent.Partial("dent"))
        r.speech.emit(SpeechEvent.Final("dentista"))
        runCurrent()
        assertTrue(r.vm.state.value is VoiceState.NeedsMore)
        leave(r, vs)
        assertEquals(listOf("dentista"), r.pending.all().map { it.text })
    }

    @Test fun floatingApplyFailureThenLeaveQueuesOnce() = runTest {
        val vs = screenScope()
        val r = rig(outcomes = arrayOf(ok("Ejercicio")), floating = true, vmScope = vs)
        r.exec.failApply = true
        r.vm.submitText("ejercicio")
        runCurrent()
        assertTrue((r.vm.state.value as VoiceState.Error).retry)
        leave(r, vs)
        assertEquals(listOf("ejercicio"), r.pending.all().map { it.text })
    }

    @Test fun floatingQueueFailureThenLeaveQueuesOnce() = runTest {
        val vs = screenScope()
        val real: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "q.preferences_pb") }
        var failures = 1
        val flaky = object : DataStore<Preferences> {
            override val data = real.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (failures-- > 0) throw java.io.IOException("boom")
                return real.updateData(transform)
            }
        }
        val pending = PendingDictations(flaky)
        val vm = VoiceViewModel(
            FakeSpeechInput(), FakeInterpreter(InterpretOutcome.Offline), FakeExecutor(), pending, now = { 1L },
            scopeOverride = vs, leaveScope = backgroundScope, floating = true,
        )
        vm.submitText("dentista")
        runCurrent()
        assertEquals(VoiceCopy.QUEUE_FAILED, (vm.state.value as VoiceState.Error).message)
        vs.cancel()
        ViewModelStore().also { it.put("k", vm) }.clear()
        runCurrent()
        assertEquals(listOf("dentista"), pending.all().map { it.text })
    }

    @Test fun floatingRefusedDismissedWithoutOpeningAppQueuesNothing() = runTest {
        val vs = screenScope()
        val r = rig(outcomes = arrayOf(InterpretOutcome.Refused("No")), floating = true, vmScope = vs)
        r.vm.submitText("algo")
        runCurrent()
        leave(r, vs)
        assertTrue(r.pending.all().isEmpty())
    }

    @Test fun floatingRefusedCarriesTextOnlyWhenOpeningApp() = runTest {
        val r = rig(outcomes = arrayOf(InterpretOutcome.Refused("No")), floating = true)
        r.vm.submitText("algo")
        runCurrent()
        assertTrue(r.vm.state.value is VoiceState.Error)
        assertTrue(r.pending.all().isEmpty())
        r.vm.queueForApp()
        runCurrent()
        assertEquals(listOf("algo"), r.pending.all().map { it.text })
    }

    @Test fun defaultTimeoutStillErrorsWithDraft() = runTest {
        val r = rig(outcomes = arrayOf(InterpretOutcome.Failed(DictationInterpreter.TOO_SLOW, retryable = true)))
        r.vm.submitText("ejercicio")
        runCurrent()
        assertEquals(VoiceState.Error(DictationInterpreter.TOO_SLOW, retry = true), r.vm.state.value)
        assertEquals("ejercicio", r.vm.draft.value)
        assertTrue(r.pending.all().isEmpty())
    }

    @Test fun noKeyKeepsErrorByDefault() = runTest {
        val r = rig(outcomes = arrayOf(InterpretOutcome.NoKey))
        r.vm.submitText("algo")
        runCurrent()
        assertTrue(r.vm.state.value is VoiceState.Error)
        assertTrue(r.pending.all().isEmpty())
    }

    @Test fun invalidKeyShowsInvalidKeyCopy() = runTest {
        val r = rig(outcomes = arrayOf(InterpretOutcome.InvalidKey))
        r.vm.submitText("algo")
        runCurrent()
        assertEquals(VoiceState.Error(DictationInterpreter.INVALID_KEY, retry = false), r.vm.state.value)
        assertEquals("algo", r.vm.draft.value)
        assertTrue(r.pending.all().isEmpty())
    }

    @Test fun timeoutOffersRetryWithoutQueueingAndRetryReinterprets() = runTest {
        val r = rig(outcomes = arrayOf(InterpretOutcome.Failed(DictationInterpreter.TOO_SLOW, retryable = true), ok("Ejercicio")))
        r.vm.submitText("ejercicio")
        runCurrent()
        assertEquals(VoiceState.Error(DictationInterpreter.TOO_SLOW, retry = true), r.vm.state.value)
        assertEquals("ejercicio", r.vm.draft.value)
        assertTrue(r.pending.all().isEmpty())
        r.vm.retry()
        runCurrent()
        assertEquals(listOf("ejercicio", "ejercicio"), r.interp.texts)
        assertTrue(r.vm.state.value is VoiceState.Result)
    }

    @Test fun networkFailureIsQueued() = runTest {
        val r = rig(outcomes = arrayOf(InterpretOutcome.Failed(DictationInterpreter.OFFLINE, retryable = true, network = true)))
        r.vm.submitText("ejercicio")
        runCurrent()
        assertEquals(VoiceState.SavedOffline, r.vm.state.value)
        assertEquals(1, r.pending.all().size)
    }

    @Test fun refusedKeepsTextInField() = runTest {
        val r = rig(outcomes = arrayOf(InterpretOutcome.Refused(DictationInterpreter.REFUSED)))
        r.vm.submitText("???")
        runCurrent()
        assertEquals(VoiceState.Error(DictationInterpreter.REFUSED, retry = false), r.vm.state.value)
        assertEquals("???", r.vm.draft.value)
    }

    @Test fun invalidMessagesAppearInResult() = runTest {
        val r = rig(outcomes = arrayOf(
            InterpretOutcome.Ok(listOf(activity("Ejercicio"), Validated.Invalid("La fecha ya pasó")), "Agendé todo"),
        ))
        r.vm.submitText("x")
        runCurrent()
        val s = r.vm.state.value as VoiceState.Result
        assertEquals(listOf("La fecha ya pasó"), s.messages)
        assertEquals(listOf("Ejercicio"), s.created)
    }

    @Test fun undoRunsOnceAndShowsUndone() = runTest {
        val r = rig(outcomes = arrayOf(ok("Ejercicio")))
        r.vm.submitText("x")
        runCurrent()
        r.vm.undo()
        r.vm.undo()
        runCurrent()
        assertEquals(VoiceState.Undone, r.vm.state.value)
        assertEquals(1, r.exec.undone.size)
    }

    @Test fun noMatchShowsFriendlyMessageAndRetryListensAgain() = runTest {
        val r = rig()
        r.vm.start()
        r.speech.emit(SpeechEvent.Failure(SpeechFailure.NO_MATCH))
        assertEquals(VoiceState.Error(VoiceCopy.NOT_HEARD, retry = true), r.vm.state.value)
        r.vm.retry()
        assertEquals(2, r.speech.starts)
        assertEquals(VoiceState.Listening(""), r.vm.state.value)
    }

    @Test fun unavailableRecognizerNeverStartsAndMicDeniedShowsHint() = runTest {
        val r = rig(FakeSpeechInput(isAvailable = false))
        assertFalse(r.vm.voiceAvailable)
        r.vm.start()
        assertEquals(0, r.speech.starts)
        assertEquals(VoiceCopy.NO_VOICE, r.vm.hint.value)
        val r2 = rig()
        r2.vm.onMicDenied()
        assertEquals(VoiceCopy.MIC_DENIED, r2.vm.hint.value)
        assertEquals(0, r2.speech.starts)
    }

    @Test fun stopAsksRecognizerToFinish() = runTest {
        val r = rig()
        r.vm.start()
        r.vm.stop()
        assertEquals(1, r.speech.stops)
    }

    @Test fun clearingDestroysRecognizer() {
        val speech = FakeSpeechInput()
        val store = ViewModelStore()
        val ds = PreferenceDataStoreFactory.create(scope = CoroutineScope(Job())) { File(tmp.root, "q.preferences_pb") }
        val vm = VoiceViewModel(speech, FakeInterpreter(), FakeExecutor(), PendingDictations(ds), now = { 0L })
        store.put("k", vm)
        store.clear()
        assertTrue(speech.destroyed)
    }

    @Test fun leavingWhileThinkingQueuesTheTextAndRequestsDrain() = runTest {
        val speech = FakeSpeechInput()
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "c.preferences_pb") }
        val pending = PendingDictations(ds)
        val interp = FakeInterpreter(ok("X")).also { it.gate = CompletableDeferred() }
        val vm = VoiceViewModel(speech, interp, FakeExecutor(), pending, now = { 7L }, scopeOverride = backgroundScope, requestDrain = { drains++ })
        val store = ViewModelStore()
        store.put("k", vm)
        vm.submitText("dentista mañana")
        runCurrent()
        assertEquals(VoiceState.Thinking, vm.state.value)
        store.clear()
        runCurrent()
        assertEquals(listOf("dentista mañana"), pending.all().map { it.text })
        assertEquals(1, drains)
    }

    @Test fun partialSpeechSurvivesNetworkError() = runTest {
        val r = rig()
        r.vm.start()
        r.speech.emit(SpeechEvent.Partial("mañana dentista"))
        r.speech.emit(SpeechEvent.Failure(SpeechFailure.NETWORK))
        assertEquals(VoiceState.Error(VoiceCopy.NETWORK, retry = false), r.vm.state.value)
        assertEquals("mañana dentista", r.vm.draft.value)
        assertEquals("El reconocimiento de voz necesita internet. Puedes escribirlo.", VoiceCopy.NETWORK)
    }

    @Test fun queueFailureFallsBackToErrorWithTextInDraft() = runTest {
        val vm = VoiceViewModel(
            FakeSpeechInput(), FakeInterpreter(InterpretOutcome.Offline), FakeExecutor(), PendingDictations(ThrowingDataStore()),
            now = { 0L }, scopeOverride = backgroundScope,
        )
        vm.submitText("algo")
        runCurrent()
        assertEquals(VoiceState.Error(VoiceCopy.QUEUE_FAILED, retry = true), vm.state.value)
        assertEquals("algo", vm.draft.value)
    }

    @Test fun undoFailureShowsMessageAndKeepsUndoAvailable() = runTest {
        val r = rig(outcomes = arrayOf(ok("Ejercicio")))
        r.exec.failUndo = true
        r.vm.submitText("x")
        runCurrent()
        r.vm.undo()
        runCurrent()
        val s = r.vm.state.value as VoiceState.Result
        assertTrue(s.undoAvailable)
        assertTrue(s.messages.contains("No pude deshacer. Inténtalo de nuevo."))
        r.exec.failUndo = false
        r.vm.undo()
        runCurrent()
        assertEquals(VoiceState.Undone, r.vm.state.value)
    }

    @Test fun terminalErrorForgetsPendingQuestion() = runTest {
        val r = rig(outcomes = arrayOf(
            InterpretOutcome.Ok(listOf(Validated.Question("¿Qué?")), ""),
            InterpretOutcome.Refused("no"),
            ok("Ejercicio"),
        ))
        r.vm.submitText("uno")
        runCurrent()
        r.vm.submitText("dos")
        runCurrent()
        assertEquals("uno. dos", r.vm.draft.value)
        r.vm.submitText(r.vm.draft.value)
        runCurrent()
        assertEquals("uno. dos", r.interp.texts.last())
    }

    @Test fun startingFreshDictationDropsOldQuestionButAnsweringKeepsIt() = runTest {
        val r = rig(outcomes = arrayOf(InterpretOutcome.Ok(listOf(Validated.Question("¿Qué?")), ""), ok("A")))
        r.vm.submitText("uno")
        runCurrent()
        r.vm.start() // responder por voz: conserva "uno"
        r.speech.emit(SpeechEvent.Final("dos"))
        runCurrent()
        assertEquals("uno. dos", r.interp.texts.last())
    }

    @Test fun leavingWhileApplyIsInFlightAppliesOnceAndDoesNotQueue() = runTest {
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "e.preferences_pb") }
        val pending = PendingDictations(ds)
        val exec = FakeExecutor().also { it.applyGate = CompletableDeferred() }
        val vm = VoiceViewModel(FakeSpeechInput(), FakeInterpreter(ok("X")), exec, pending, scopeOverride = backgroundScope, requestDrain = { drains++ })
        val store = ViewModelStore()
        store.put("k", vm)
        vm.submitText("dentista")
        runCurrent()
        store.clear()
        runCurrent()
        exec.applyGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, exec.applied.size)
        assertTrue(pending.all().isEmpty())
        assertEquals(0, drains)
    }

    @Test fun leavingWhileQueueWriteIsInFlightLeavesExactlyOneEntry() = runTest {
        val inner: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "f.preferences_pb") }
        val gate = CompletableDeferred<Unit>()
        val vm = VoiceViewModel(
            FakeSpeechInput(), FakeInterpreter(InterpretOutcome.Offline), FakeExecutor(), PendingDictations(GatedDataStore(inner, gate)),
            scopeOverride = backgroundScope, requestDrain = { drains++ },
        )
        val store = ViewModelStore()
        store.put("k", vm)
        vm.submitText("dentista")
        runCurrent()
        store.clear()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("dentista"), PendingDictations(inner).all().map { it.text })
    }

    @Test fun undoFailureMessageIsNotDuplicated() = runTest {
        val r = rig(outcomes = arrayOf(ok("Ejercicio")))
        r.exec.failUndo = true
        r.vm.submitText("x")
        runCurrent()
        r.vm.undo(); runCurrent()
        r.vm.undo(); runCurrent()
        val s = r.vm.state.value as VoiceState.Result
        assertEquals(1, s.messages.count { it == VoiceCopy.UNDO_FAILED })
    }

    // --- M3 / T4: el habla nunca pisa lo escrito ---

    @Test fun typingWhileListeningCancelsTheRecognizerAndGoesIdle() = runTest {
        val r = rig()
        r.vm.start()
        r.speech.emit(SpeechEvent.Partial("mañana"))
        r.vm.setDraft("llamar al banco")
        assertEquals(VoiceState.Idle, r.vm.state.value)
        assertEquals(1, r.speech.cancels)
        assertEquals("llamar al banco", r.vm.draft.value)
        // Eventos tardíos del reconocedor ya no cuentan.
        r.speech.emit(SpeechEvent.Final("mañana dentista"))
        runCurrent()
        assertEquals(VoiceState.Idle, r.vm.state.value)
        assertEquals("llamar al banco", r.vm.draft.value)
        assertTrue(r.interp.texts.isEmpty())
    }

    @Test fun blankTypingWhileListeningKeepsListening() = runTest {
        val r = rig()
        r.vm.start()
        r.vm.setDraft("  ")
        assertEquals(VoiceState.Listening(""), r.vm.state.value)
        assertEquals(0, r.speech.cancels)
    }

    @Test fun recognizerErrorKeepsTypedTextInsteadOfThePartial() = runTest {
        val r = rig()
        r.vm.setDraft("escrito por mí")
        r.vm.start()
        r.speech.emit(SpeechEvent.Partial("lo que oyó"))
        r.speech.emit(SpeechEvent.Failure(SpeechFailure.NETWORK))
        assertEquals("escrito por mí", r.vm.draft.value)
    }

    @Test fun finalSpeechWhileTheDraftHoldsTypedTextIsAppendedAndNotSubmitted() = runTest {
        val r = rig()
        r.vm.setDraft("llamar al banco")
        r.vm.start()
        r.speech.emit(SpeechEvent.Final("y mañana dentista"))
        runCurrent()
        assertEquals(VoiceState.Idle, r.vm.state.value)
        assertEquals("llamar al banco y mañana dentista", r.vm.draft.value)
        assertTrue(r.interp.texts.isEmpty())
    }

    @Test fun finalSpeechWithBlankDraftStillSubmits() = runTest {
        val r = rig(outcomes = arrayOf(ok("Dentista")))
        r.vm.start()
        r.speech.emit(SpeechEvent.Final("mañana dentista"))
        runCurrent()
        assertEquals(listOf("mañana dentista"), r.interp.texts)
    }
}
