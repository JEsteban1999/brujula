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

    private fun TestScope.rig(speech: FakeSpeechInput = FakeSpeechInput(), vararg outcomes: InterpretOutcome): Rig {
        val ds: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "p.preferences_pb") }
        val pending = PendingDictations(ds)
        val interp = FakeInterpreter(*outcomes)
        val exec = FakeExecutor()
        return Rig(speech, interp, exec, pending, VoiceViewModel(speech, interp, exec, pending, now = { 1000L }, scopeOverride = backgroundScope))
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
        val r = rig(outcomes = arrayOf(InterpretOutcome.Failed(DictationInterpreter.OFFLINE, retryable = true)))
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
}
