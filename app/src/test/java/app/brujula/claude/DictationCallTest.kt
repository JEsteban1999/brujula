package app.brujula.claude

import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.core.http.Headers
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.StructuredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La petición y la lectura de la respuesta, sin red ni clave real. */
class DictationCallTest {
    @Test fun structuredParamsBuildWithDerivedSchema() {
        // Si la derivación del schema desde DictationResult fallara, build() lanzaría aquí.
        val p = DictationCall.params("SISTEMA", "USUARIO")
        assertEquals(ClaudeModels.DICTATION, p.model().asString())
        assertEquals(4096L, p.maxTokens())
        val out = p.outputConfig().get()
        assertEquals(BetaOutputConfig.Effort.LOW, out.effort().get())
        // Serializado como va en el cuerpo HTTP.
        val schema = jsonMapper().writeValueAsString(out)
        assertTrue(schema, schema.contains("\"json_schema\""))
        assertTrue(schema.contains("\"additionalProperties\":false"))
        assertTrue("Los opcionales de Kotlin van como nulables", schema.contains("\"durationMin\":{\"type\":[\"integer\",\"null\"]"))
        listOf("actions", "reply", "activityType", "durationMin", "beforeBedMin", "question").forEach {
            assertTrue("Falta $it en el schema: $schema", schema.contains("\"$it\""))
        }
        assertTrue(p.fallbacks().get().isDefault())
        assertTrue(p.betas().get().contains(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01))
        assertFalse("Sin parámetro thinking", p.thinking().isPresent)
        val system = p.system().get().asBetaTextBlockParams().single()
        assertEquals("SISTEMA", system.text())
        assertTrue("El system va cacheable", system.cacheControl().isPresent)
        assertTrue(p.messages().single().toString().contains("USUARIO"))
    }

    private fun message(stopReason: String, content: String): StructuredMessage<DictationResult> {
        val json = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":$content,"stop_reason":"$stopReason","stop_sequence":null,"stop_details":null,
             "container":null,"context_management":null,
             "usage":{"input_tokens":10,"output_tokens":5}}
        """.trimIndent()
        val raw = jsonMapper().readValue(json, BetaMessage::class.java)
        // El constructor es internal en Kotlin (público en bytecode): es el mismo que usa el SDK al recibir la respuesta.
        @Suppress("UNCHECKED_CAST")
        return StructuredMessage::class.java.getConstructor(Class::class.java, BetaMessage::class.java)
            .newInstance(DictationResult::class.java, raw) as StructuredMessage<DictationResult>
    }

    private fun textBlock(text: String): String =
        """[{"type":"text","text":${jsonMapper().writeValueAsString(text)}}]"""

    @Test fun endTurnIsParsedIntoDictationResult() {
        val body = """{"actions":[{"type":"create_habit","title":"Tomar agua","activityType":null,"date":null,"start":null,
            "durationMin":null,"repeat":null,"copyToGoogle":null,"habitMode":"EVERY","intervalMin":60,"times":null,
            "beforeBedMin":null,"workOnly":false,"question":null}],"reply":"Listo."}"""
        val r = DictationCall.read(message("end_turn", textBlock(body)))
        val ok = r as GatewayResult.Ok
        assertEquals("Listo.", ok.value.reply)
        assertEquals(60, ok.value.actions.single().intervalMin)
        assertEquals("EVERY", ok.value.actions.single().habitMode)
    }

    @Test fun refusalIsRefusedWithoutReadingContent() {
        assertEquals(GatewayResult.Refused, DictationCall.read(message("refusal", "[]")))
    }

    @Test fun truncatedOutputIsANonRetryableError() {
        assertEquals(GatewayResult.Error(retryable = false), DictationCall.read(message("max_tokens", textBlock("{\"actions\":["))))
    }

    @Test fun unparseableTextIsANonRetryableError() {
        assertEquals(GatewayResult.Error(retryable = false), DictationCall.read(message("end_turn", textBlock("no es json"))))
    }

    @Test fun endTurnWithoutTextIsANonRetryableError() {
        assertEquals(GatewayResult.Error(retryable = false), DictationCall.read(message("end_turn", "[]")))
    }

    private val headers = Headers.builder().build()
    private val body = JsonValue.from(emptyMap<String, Any>())

    @Test fun exceptionsAreMappedByType() {
        assertEquals(GatewayResult.Network, DictationCall.failureOf(AnthropicIoException("sin red")))
        assertEquals(GatewayResult.InvalidKey, DictationCall.failureOf(UnauthorizedException.builder().headers(headers).body(body).build()))
        assertEquals(GatewayResult.InvalidKey, DictationCall.failureOf(PermissionDeniedException.builder().headers(headers).body(body).build()))
        assertEquals(GatewayResult.Error(retryable = true), DictationCall.failureOf(RateLimitException.builder().headers(headers).body(body).build()))
        assertEquals(GatewayResult.Error(retryable = true), DictationCall.failureOf(InternalServerException.builder().statusCode(500).headers(headers).body(body).build()))
        assertEquals(GatewayResult.Error(retryable = true), DictationCall.failureOf(OverloadedException.builder().statusCode(529).headers(headers).body(body).build()))
        assertEquals(GatewayResult.Error(retryable = false), DictationCall.failureOf(BadRequestException.builder().headers(headers).body(body).build()))
        assertEquals(GatewayResult.Error(retryable = false), DictationCall.failureOf(IllegalStateException("x")))
    }
}
