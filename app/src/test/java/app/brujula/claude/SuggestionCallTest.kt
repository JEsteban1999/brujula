package app.brujula.claude

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.OutputConfig
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration

/** La petición de sugerencias (Haiku 5.5, endpoint no beta) y la lectura de su respuesta, sin red ni clave real. */
class SuggestionCallTest {
    private val logged = mutableListOf<String>()
    private val originalWarn = DictationCall.warn

    @Before fun captureLog() { DictationCall.warn = { logged += it } }

    @After fun restoreLog() { DictationCall.warn = originalWarn }

    @Test fun paramsUseHaikuMediumEffortNoFallbacksAndTheHandWrittenSchema() {
        val p = SuggestionCall.params("SISTEMA", "USUARIO")
        assertEquals("claude-haiku-5-5", p.model().asString())
        assertEquals(ClaudeModels.SUGGESTIONS, p.model().asString())
        assertEquals(4000L, p.maxTokens())
        val out = p.outputConfig().get()
        assertEquals(OutputConfig.Effort.MEDIUM, out.effort().get())
        val schema = jsonMapper().writeValueAsString(out)
        assertTrue(schema, schema.contains("\"json_schema\""))
        assertTrue(schema, schema.contains("\"additionalProperties\":false"))
        assertTrue(schema, schema.contains("\"fromTime\":{\"type\":[\"string\",\"null\"]"))
        assertFalse("Sin parámetro thinking", p.thinking().isPresent)
        val body = jsonMapper().writeValueAsString(p._body())
        assertFalse("Sin fallbacks: $body", body.contains("\"fallbacks\""))
        assertFalse("Sin temperatura: $body", body.contains("\"temperature\""))
        assertFalse("Sin thinking: $body", body.contains("\"thinking\""))
        assertTrue(body.contains("SISTEMA"))
        assertEquals("Sin prefill: un solo mensaje de usuario", 1, p.messages().size)
        assertTrue(p.messages().single().toString().contains("USUARIO"))
    }

    @Test fun suggestionHasItsOwn60sTimeout() {
        assertEquals(Duration.ofSeconds(60), SuggestionCall.TIMEOUT)
        assertEquals(Duration.ofSeconds(60), SuggestionCall.requestOptions().timeout().get().request())
    }

    private fun message(stopReason: String, content: String): Message {
        val json = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5",
             "content":$content,"stop_reason":"$stopReason","stop_sequence":null,
             "usage":{"input_tokens":10,"output_tokens":5}}
        """.trimIndent()
        return jsonMapper().readValue(json, Message::class.java)
    }

    private fun textBlock(text: String): String =
        """[{"type":"text","text":${jsonMapper().writeValueAsString(text)}}]"""

    @Test fun refusalIsRefused() {
        assertEquals(GatewayResult.Refused, SuggestionCall.read(message("refusal", "[]")))
    }

    @Test fun truncatedOutputIsARetryableError() {
        assertEquals(GatewayResult.Error(retryable = true), SuggestionCall.read(message("max_tokens", textBlock("{\"suggestions\":["))))
    }

    @Test fun endTurnWithOneSuggestionIsParsed() {
        val body = """{"suggestions":[{"habitRef":"H3","message":"Por las tardes casi no tomas agua. ¿Te aviso más seguido después de las 3 pm?",
            "change":"interval","intervalMin":60,"fromTime":"15:00","times":null,"dailyTarget":null}]}"""
        val ok = SuggestionCall.read(message("end_turn", textBlock(body))) as GatewayResult.Ok
        assertEquals(
            SuggestionItem(
                habitRef = "H3", message = "Por las tardes casi no tomas agua. ¿Te aviso más seguido después de las 3 pm?",
                change = "interval", intervalMin = 60, fromTime = "15:00",
            ),
            ok.value.suggestions.single(),
        )
    }

    @Test fun unparseableTextIsANonRetryableErrorAndLogsOnlyTheClass() {
        assertEquals(GatewayResult.Error(retryable = false), SuggestionCall.read(message("end_turn", textBlock("no es json secreto"))))
        val line = logged.single()
        assertTrue(line, line.startsWith("Respuesta de sugerencia ilegible: "))
        assertFalse(line.contains("secreto"))
    }

    @Test fun noSuchMethodErrorBuildingParamsIsAConfigErrorNotACrash() = runTest {
        val g = AnthropicClaudeGateway(readKey = { "sk-ant-test-0000" }, suggestionParams = { _, _ -> throw NoSuchMethodError("x") })
        assertEquals(GatewayResult.Error(retryable = false, config = true), g.suggest("s", "u"))
        assertEquals(listOf("Sugerencia falló: NoSuchMethodError"), logged)
    }

    @Test fun ordinaryExceptionsAreMappedWithTheSuggestionLabel() = runTest {
        val g = AnthropicClaudeGateway(readKey = { "sk-ant-test-0000" }, suggestionParams = { _, _ -> throw IllegalStateException("x") })
        assertEquals(GatewayResult.Error(retryable = false), g.suggest("s", "u"))
        assertEquals(listOf("Sugerencia falló: IllegalStateException"), logged)
    }

    @Test fun noKeyNeverBuildsParams() = runTest {
        val g = AnthropicClaudeGateway(readKey = { null }, suggestionParams = { _, _ -> error("no debe llamarse") })
        assertEquals(GatewayResult.NoKey, g.suggest("s", "u"))
    }

    @Test fun delegatingGatewayForwardsSuggest() = runTest {
        val fake = FakeClaudeGateway(suggestion = GatewayResult.Ok(SuggestionResult()))
        assertEquals(GatewayResult.Ok(SuggestionResult()), DelegatingGateway { fake }.suggest("S", "U"))
        assertEquals(1, fake.suggestCalls)
        assertEquals("U", fake.lastSuggestUser)
    }
}
