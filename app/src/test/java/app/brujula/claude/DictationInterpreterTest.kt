package app.brujula.claude

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DictationInterpreterTest {
    private val ctx = dictationCtx(now = LocalDate.of(2026, 11, 18).atTime(10, 0))
    private val gateway = FakeClaudeGateway()
    private var online = true
    private val interpreter = DictationInterpreter(gateway, { ctx }, { online })

    private val twoActions = DictationResult(
        actions = listOf(
            DictationAction(
                type = "create_activity", title = "Dentista", activityType = "APPOINTMENT",
                date = "2026-11-19", start = "15:00", durationMin = 60, repeat = "NONE", copyToGoogle = true,
            ),
            DictationAction(type = "create_habit", title = "Tomar agua", habitMode = "EVERY", intervalMin = 60),
        ),
        reply = "Listo: dentista mañana a las 3:00 p. m. y agua cada hora.",
    )

    @Test fun cannedTwoActionsBecomeTwoValidatedItems() = runTest {
        gateway.dictation = GatewayResult.Ok(twoActions)
        val out = interpreter.interpret("mañana a las 3 dentista y recuérdame tomar agua cada hora") as InterpretOutcome.Ok
        assertEquals(2, out.items.size)
        assertTrue(out.items[0] is Validated.Activity)
        assertTrue(out.items[1] is Validated.Habit)
        assertEquals(twoActions.reply, out.reply)
        assertEquals(PromptBuilder.system(), gateway.lastSystem)
        assertEquals(PromptBuilder.user(ctx, "mañana a las 3 dentista y recuérdame tomar agua cada hora"), gateway.lastUser)
    }

    @Test fun invalidActionIsKeptAsInvalidNotDropped() = runTest {
        gateway.dictation = GatewayResult.Ok(
            DictationResult(listOf(DictationAction(type = "create_activity", title = "X", activityType = "PERSONAL", date = "2026-11-17", start = "10:00", durationMin = 30)), "Listo."),
        )
        val out = interpreter.interpret("ayer algo") as InterpretOutcome.Ok
        assertTrue(out.items.single() is Validated.Invalid)
    }

    @Test fun dictationInstantIsPassedToTheContextProvider() = runTest {
        val seen = mutableListOf<Long?>()
        val i = DictationInterpreter(gateway, { at -> seen += at; ctx })
        gateway.dictation = GatewayResult.Ok(DictationResult(emptyList(), "ok"))
        i.interpret("algo", 1234L)
        i.interpret("algo")
        assertEquals(listOf<Long?>(1234L, null), seen)
    }

    @Test fun configurationErrorIsItsOwnOutcomeNotTheUsersWording() = runTest {
        gateway.dictation = GatewayResult.Error(retryable = false, config = true)
        val out = interpreter.interpret("algo") as InterpretOutcome.Failed
        assertTrue(out.config)
        assertEquals(false, out.retryable)
        assertEquals("No pude hablar con Claude (error de configuración). Inténtalo más tarde.", out.message)
        assertTrue(!out.message.contains("otra forma"))
    }

    @Test fun noKeyMapsToNoKey() = runTest {
        gateway.dictation = GatewayResult.NoKey
        assertEquals(InterpretOutcome.NoKey, interpreter.interpret("algo"))
    }

    @Test fun invalidKeyMapsToInvalidKey() = runTest {
        gateway.dictation = GatewayResult.InvalidKey
        assertEquals(InterpretOutcome.InvalidKey, interpreter.interpret("algo"))
        assertEquals("Tu clave de Claude no funciona. Revísala en Tu rutina.", DictationInterpreter.INVALID_KEY)
    }

    @Test fun timeoutIsRetryableAndNotOffline() = runTest {
        gateway.dictation = GatewayResult.Timeout
        assertEquals(InterpretOutcome.Failed("Claude tardó demasiado. Inténtalo de nuevo.", retryable = true), interpreter.interpret("algo"))
    }

    @Test fun connectionFailureIsRetryable() = runTest {
        gateway.dictation = GatewayResult.Network
        val out = interpreter.interpret("algo") as InterpretOutcome.Failed
        assertTrue(out.retryable)
        assertEquals("Sin conexión. Lo interpreto en cuanto vuelva internet.", out.message)
    }

    @Test fun rateLimitOrServerErrorIsRetryable() = runTest {
        gateway.dictation = GatewayResult.Error(retryable = true)
        assertTrue((interpreter.interpret("algo") as InterpretOutcome.Failed).retryable)
    }

    @Test fun badResponseIsNotRetryable() = runTest {
        gateway.dictation = GatewayResult.Error(retryable = false)
        assertEquals(false, (interpreter.interpret("algo") as InterpretOutcome.Failed).retryable)
    }

    @Test fun refusalShowsTheRefusalCopy() = runTest {
        gateway.dictation = GatewayResult.Refused
        assertEquals(
            InterpretOutcome.Refused("No pude interpretar eso. Prueba diciéndolo de otra forma."),
            interpreter.interpret("algo"),
        )
    }

    @Test fun offlineSkipsTheCall() = runTest {
        online = false
        assertEquals(InterpretOutcome.Offline, interpreter.interpret("algo"))
        assertEquals(0, gateway.interpretCalls)
    }

    @Test fun contextFailureIsRetryableAndSkipsTheCall() = runTest {
        val broken = DictationInterpreter(gateway, { error("db") })
        assertTrue((broken.interpret("algo") as InterpretOutcome.Failed).retryable)
        assertEquals(0, gateway.interpretCalls)
    }

    @Test fun blankTextSkipsTheCall() = runTest {
        assertTrue(interpreter.interpret("   ") is InterpretOutcome.Failed)
        assertEquals(0, gateway.interpretCalls)
    }
}
