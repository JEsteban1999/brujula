package app.brujula.claude

import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration

/** La petición de planificación y la lectura de su respuesta, sin red ni clave real. */
class PlanCallTest {
    private val logged = mutableListOf<String>()
    private val originalWarn = DictationCall.warn

    @Before fun captureLog() { DictationCall.warn = { logged += it } }

    @After fun restoreLog() { DictationCall.warn = originalWarn }

    @Test fun paramsUseOpusMediumEffortFallbackAndTheHandWrittenSchema() {
        val p = PlanCall.params("SISTEMA", "USUARIO")
        assertEquals("claude-opus-5-5", p.model().asString())
        assertEquals(ClaudeModels.PLANNING, p.model().asString())
        assertEquals(16000L, p.maxTokens())
        val out = p.outputConfig().get()
        assertEquals(BetaOutputConfig.Effort.MEDIUM, out.effort().get())
        val schema = jsonMapper().writeValueAsString(out)
        assertTrue(schema, schema.contains("\"json_schema\""))
        assertTrue(schema.contains("\"additionalProperties\":false"))
        assertTrue(schema, schema.contains("\"durationMin\":{\"type\":[\"integer\",\"null\"]"))
        listOf("summary", "changes", "op", "ref", "title", "activityType", "date", "start", "durationMin").forEach {
            assertTrue("Falta $it en el schema: $schema", schema.contains("\"$it\":{"))
        }
        assertTrue(p.betas().get().contains(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01))
        assertTrue(p.fallbacks().get().isDefault())
        assertFalse("Sin parámetro thinking", p.thinking().isPresent)
        val system = p.system().get().asBetaTextBlockParams().single()
        assertEquals("SISTEMA", system.text())
        assertTrue("El system va cacheable", system.cacheControl().isPresent)
        assertTrue(p.messages().single().toString().contains("USUARIO"))
        assertEquals("Sin prefill: un solo mensaje de usuario", 1, p.messages().size)
    }

    @Test fun planHasItsOwn90sTimeout() {
        assertEquals(Duration.ofSeconds(90), PlanCall.TIMEOUT)
        assertEquals(Duration.ofSeconds(90), PlanCall.requestOptions().timeout().get().request())
    }

    private fun message(stopReason: String, content: String): BetaMessage {
        val json = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":$content,"stop_reason":"$stopReason","stop_sequence":null,"stop_details":null,
             "container":null,"context_management":null,
             "usage":{"input_tokens":10,"output_tokens":5}}
        """.trimIndent()
        return jsonMapper().readValue(json, BetaMessage::class.java)
    }

    private fun textBlock(text: String): String =
        """[{"type":"text","text":${jsonMapper().writeValueAsString(text)}}]"""

    @Test fun refusalIsRefused() {
        assertEquals(GatewayResult.Refused, PlanCall.read(message("refusal", "[]")))
    }

    @Test fun truncatedOutputIsARetryableError() {
        assertEquals(GatewayResult.Error(retryable = true), PlanCall.read(message("max_tokens", textBlock("{\"summary\":"))))
    }

    @Test fun endTurnWithTwoChangesIsParsed() {
        val body = """{"summary":"Pasé el gym a la tarde y agregué un bloque de trabajo.","changes":[
            {"op":"move","ref":"A7","title":null,"activityType":null,"date":"2026-11-19","start":"17:00","durationMin":null},
            {"op":"create","ref":null,"title":"Bloque de trabajo","activityType":"WORK","date":"2026-11-19","start":"15:00","durationMin":120}]}"""
        val ok = PlanCall.read(message("end_turn", textBlock(body))) as GatewayResult.Ok
        assertEquals(2, ok.value.changes.size)
        assertEquals(PlanChange(op = "move", ref = "A7", date = "2026-11-19", start = "17:00"), ok.value.changes[0])
        assertEquals(
            PlanChange(op = "create", title = "Bloque de trabajo", activityType = "WORK", date = "2026-11-19", start = "15:00", durationMin = 120),
            ok.value.changes[1],
        )
        assertTrue(ok.value.summary.startsWith("Pasé el gym"))
    }

    @Test fun emptyChangesAreOk() {
        val ok = PlanCall.read(message("end_turn", textBlock("""{"summary":"Tu día ya está bien.","changes":[]}"""))) as GatewayResult.Ok
        assertTrue(ok.value.changes.isEmpty())
    }

    @Test fun unparseableTextIsANonRetryableErrorAndLogsOnlyTheClass() {
        assertEquals(GatewayResult.Error(retryable = false), PlanCall.read(message("end_turn", textBlock("no es json secreto"))))
        val line = logged.single()
        assertTrue(line, line.startsWith("Respuesta de plan ilegible: "))
        assertFalse("Nunca el contenido", line.contains("secreto"))
    }

    @Test fun noSuchMethodErrorBuildingParamsIsAConfigErrorNotACrash() = runTest {
        val g = AnthropicClaudeGateway(readKey = { "sk-ant-test-0000" }, planParams = { _, _ -> throw NoSuchMethodError("getAnnotatedReturnType secreto") })
        assertEquals(GatewayResult.Error(retryable = false, config = true), g.plan("s", "u"))
        assertEquals(listOf("Plan falló: NoSuchMethodError"), logged)
    }

    @Test fun ordinaryExceptionsAreMappedWithThePlanLabel() = runTest {
        val g = AnthropicClaudeGateway(readKey = { "sk-ant-test-0000" }, planParams = { _, _ -> throw IllegalStateException("x") })
        assertEquals(GatewayResult.Error(retryable = false), g.plan("s", "u"))
        assertEquals(listOf("Plan falló: IllegalStateException"), logged)
    }

    @Test fun noKeyNeverBuildsParams() = runTest {
        val g = AnthropicClaudeGateway(readKey = { null }, planParams = { _, _ -> error("no debe llamarse") })
        assertEquals(GatewayResult.NoKey, g.plan("s", "u"))
    }

    @Test fun delegatingGatewayForwardsPlan() = runTest {
        val fake = FakeClaudeGateway(plan = GatewayResult.Ok(PlanResult(summary = "ok")))
        assertEquals(GatewayResult.Ok(PlanResult(summary = "ok")), DelegatingGateway { fake }.plan("S", "U"))
        assertEquals(1, fake.planCalls)
        assertEquals("U", fake.lastPlanUser)
    }
}
