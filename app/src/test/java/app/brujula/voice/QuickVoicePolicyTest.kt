package app.brujula.voice

import app.brujula.claude.Validated
import app.brujula.ui.DeepLinks
import app.brujula.ui.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = app.brujula.TestBrujulaApp::class)
class QuickVoicePolicyTest {
    private val day = LocalDate.of(2026, 11, 20)

    @Test fun autoCloseDelays() {
        val plain = VoiceState.Result(listOf("Ejercicio"), "ok", emptyList(), undoAvailable = true)
        val withPlan = plain.copy(proposal = Validated.PlanRequest(day, "arma mi día"))
        assertEquals(4_000L, QuickVoicePolicy.autoCloseDelay(plain))
        assertNull(QuickVoicePolicy.autoCloseDelay(withPlan))
        assertEquals(1_500L, QuickVoicePolicy.autoCloseDelay(VoiceState.Undone))
        assertEquals(3_000L, QuickVoicePolicy.autoCloseDelay(VoiceState.SavedOffline))
        assertEquals(3_000L, QuickVoicePolicy.autoCloseDelay(VoiceState.SavedNeedsKey))
        listOf(
            VoiceState.Idle, VoiceState.Listening("x"), VoiceState.Thinking, VoiceState.NeedsMore("¿?"),
            VoiceState.Error("e", true), VoiceState.OpenProposal(day, "r"),
        ).forEach { assertNull(it.toString(), QuickVoicePolicy.autoCloseDelay(it)) }
    }

    @Test fun closeWhenDueFiresAfterDelayAndRestartsOnStateChange() = kotlinx.coroutines.test.runTest {
        var closed = 0
        val result = VoiceState.Result(listOf("Ejercicio"), "ok", emptyList(), undoAvailable = true)
        val job = launch { QuickVoicePolicy.closeWhenDue(result) { closed++ } }
        advanceTimeBy(3_999); runCurrent()
        assertEquals(0, closed)
        advanceTimeBy(2); runCurrent()
        assertEquals(1, closed)
        // Deshacer cancela el temporizador del resultado y arranca el de Deshecho (1,5 s).
        val second = launch { QuickVoicePolicy.closeWhenDue(result) { closed++ } }
        advanceTimeBy(3_000); runCurrent()
        second.cancel()
        val undone = launch { QuickVoicePolicy.closeWhenDue(VoiceState.Undone) { closed++ } }
        advanceTimeBy(1_499); runCurrent()
        assertEquals(1, closed)
        advanceTimeBy(2); runCurrent()
        assertEquals(2, closed)
        // Sin tiempo (escuchando) no cierra jamás.
        launch { QuickVoicePolicy.closeWhenDue(VoiceState.Thinking) { closed++ } }
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, closed)
        job.cancel(); undone.cancel()
    }

    @Test fun copyIsLiteral() {
        assertEquals("Escuchando… di lo que vas a hacer", QuickVoiceCopy.LISTENING)
        assertEquals("Lo guardé. Lo agendo cuando vuelva internet.", QuickVoiceCopy.SAVED_OFFLINE)
        assertEquals("Lo guardé. Agrega tu clave de Claude en Tu rutina y lo agendo.", QuickVoiceCopy.SAVED_NO_KEY)
        assertEquals("Sin permiso de micrófono.", QuickVoiceCopy.MIC_DENIED)
        assertEquals("Abrir Brújula", QuickVoiceCopy.OPEN_APP)
        assertEquals("Responder", QuickVoiceCopy.ANSWER)
    }

    @Test fun proposalHandoffIntentCarriesTokenAndTruncatedRequest() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = QuickVoicePolicy.proposalIntent(ctx, day, "a".repeat(900))
        val route = intent.getStringExtra(DeepLinks.EXTRA_ROUTE)!!
        assertNotNull(DeepLinks.parse(route))
        assertEquals(Routes.proposal(day, "a".repeat(DeepLinks.MAX_REQUEST)), route)
        assertEquals(DeepLinks.token(ctx), intent.getStringExtra(DeepLinks.EXTRA_TOKEN))
        assertEquals("app.brujula.ui.MainActivity", intent.component!!.className)
    }

    @Test fun widgetMicTargetsQuickVoiceActivityWithImmutableIntent() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val pi = app.brujula.widget.WidgetUpdater.micIntent(ctx)
        val saved = shadowOf(pi)
        assertTrue(saved.isActivityIntent)
        assertEquals(QuickVoiceActivity::class.java.name, saved.savedIntent.component!!.className)
        assertTrue(saved.savedIntent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertFalse(saved.flags and android.app.PendingIntent.FLAG_IMMUTABLE == 0)
    }
}
