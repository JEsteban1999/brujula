package app.brujula.claude

import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.core.http.Headers
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.UnprocessableEntityException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration

/** La petición y la lectura de la respuesta, sin red ni clave real. */
class DictationCallTest {
    private val logged = mutableListOf<String>()
    private val originalWarn = DictationCall.warn

    @Before fun captureLog() { DictationCall.warn = { logged += it } }

    @After fun restoreLog() { DictationCall.warn = originalWarn }

    @Test fun structuredParamsUseTheHandWrittenSchema() {
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
        // Candado del schema: si un campo se renombra o Jackson lo omite (como pasó con isWork), el modelo no lo verá.
        listOf(
            "actions", "reply", "type", "title", "activityType", "date", "start", "durationMin", "repeat", "copyToGoogle",
            "habitMode", "intervalMin", "times", "beforeBedMin", "workOnly", "question",
        ).forEach {
            assertTrue("Falta $it en el schema: $schema", schema.contains("\"$it\":{"))
        }
        assertTrue("Falta una @JsonPropertyDescription en el schema: $schema", schema.contains("Nombre corto de la actividad o del hábito"))
        assertTrue(p.fallbacks().get().isDefault())
        assertTrue(p.betas().get().contains(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01))
        assertFalse("Sin parámetro thinking", p.thinking().isPresent)
        val system = p.system().get().asBetaTextBlockParams().single()
        assertEquals("SISTEMA", system.text())
        assertTrue("El system va cacheable", system.cacheControl().isPresent)
        assertTrue(p.messages().single().toString().contains("USUARIO"))
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

    @Test fun unknownPropertiesAndOmittedNullablesAreTolerated() {
        val body = """{"actions":[{"type":"need_more_info","question":"¿A qué hora?","extra":1}],"reply":"Dime la hora.","otro":true}"""
        val ok = DictationCall.read(message("end_turn", textBlock(body))) as GatewayResult.Ok
        assertEquals("¿A qué hora?", ok.value.actions.single().question)
        assertEquals(null, ok.value.actions.single().title)
    }

    @Test fun jsonNullIsANonRetryableError() {
        assertEquals(GatewayResult.Error(retryable = false), DictationCall.read(message("end_turn", textBlock("null"))))
        assertFalse(logged.single().contains("null"))
    }

    @Test fun refusalIsRefusedWithoutReadingContent() {
        assertEquals(GatewayResult.Refused, DictationCall.read(message("refusal", "[]")))
    }

    @Test fun truncatedOutputIsARetryableError() {
        assertEquals(GatewayResult.Error(retryable = true), DictationCall.read(message("max_tokens", textBlock("{\"actions\":["))))
    }

    @Test fun unparseableTextIsANonRetryableErrorAndLogsOnlyTheClass() {
        assertEquals(GatewayResult.Error(retryable = false), DictationCall.read(message("end_turn", textBlock("no es json secreto"))))
        val line = logged.single()
        assertTrue(line, line.contains("Exception"))
        assertFalse("Nunca el contenido", line.contains("secreto") || line.contains("no es json"))
    }

    @Test fun endTurnWithoutTextIsANonRetryableError() {
        assertEquals(GatewayResult.Error(retryable = false), DictationCall.read(message("end_turn", "[]")))
    }

    @Test fun dictationHasItsOwn60sTimeout() {
        assertEquals(Duration.ofSeconds(60), DictationCall.requestOptions().timeout().get().request())
    }

    private val headers = Headers.builder().build()
    private val body = JsonValue.from(emptyMap<String, Any>())

    @Test fun exceptionsAreMappedByType() {
        assertEquals(GatewayResult.Network, DictationCall.failureOf(AnthropicIoException("sin red")))
        assertEquals(GatewayResult.Network, DictationCall.failureOf(AnthropicIoException("sin red", UnknownHostException("api.anthropic.com"))))
        assertEquals(GatewayResult.InvalidKey, DictationCall.failureOf(UnauthorizedException.builder().headers(headers).body(body).build()))
        assertEquals(GatewayResult.InvalidKey, DictationCall.failureOf(PermissionDeniedException.builder().headers(headers).body(body).build()))
        assertEquals(GatewayResult.Error(retryable = true), DictationCall.failureOf(RateLimitException.builder().headers(headers).body(body).build()))
        assertEquals(GatewayResult.Error(retryable = true), DictationCall.failureOf(InternalServerException.builder().statusCode(500).headers(headers).body(body).build()))
        assertEquals(GatewayResult.Error(retryable = true), DictationCall.failureOf(OverloadedException.builder().statusCode(529).headers(headers).body(body).build()))
        assertEquals(GatewayResult.Error(retryable = false, config = true), DictationCall.failureOf(BadRequestException.builder().headers(headers).body(body).build()))
        assertEquals(GatewayResult.Error(retryable = false, config = true), DictationCall.failureOf(NotFoundException.builder().headers(headers).body(body).build()))
        // Otros 4xx no son de configuración.
        assertEquals(GatewayResult.Error(retryable = false), DictationCall.failureOf(UnprocessableEntityException.builder().headers(headers).body(body).build()))
        assertEquals(GatewayResult.Error(retryable = false), DictationCall.failureOf(IllegalStateException("x")))
    }

    @Test fun configErrorLogHasClassAndStatusOnly() {
        DictationCall.failureOf(BadRequestException.builder().headers(headers).body(JsonValue.from(mapOf("error" to "sk-ant-secreto"))).build())
        assertEquals(listOf("Dictado falló: BadRequestException (HTTP 400)"), logged)
    }

    @Test fun timeoutIsNotOffline() {
        // Así lo envuelve el cliente OkHttp del SDK: AnthropicIoException con la IOException original como causa.
        assertEquals(GatewayResult.Timeout, DictationCall.failureOf(AnthropicIoException("timeout", SocketTimeoutException("timeout"))))
        assertEquals(GatewayResult.Timeout, DictationCall.failureOf(AnthropicIoException("timeout", InterruptedIOException("timeout"))))
    }

    @Test fun failureLogHasClassAndStatusOnly() {
        DictationCall.failureOf(RateLimitException.builder().headers(headers).body(JsonValue.from(mapOf("error" to "sk-ant-secreto"))).build())
        DictationCall.failureOf(AnthropicIoException("mensaje privado"))
        assertEquals(listOf("Dictado falló: RateLimitException (HTTP 429)", "Dictado falló: AnthropicIoException"), logged)
    }
}
