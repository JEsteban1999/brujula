package app.brujula.claude

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.OutputConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Petición y lectura de la clasificación (Haiku), sin red ni clave real. */
class ClassificationCallTest {
    private val logged = mutableListOf<String>()
    private val originalWarn = DictationCall.warn

    @Before fun captureLog() { DictationCall.warn = { logged += it } }
    @After fun restoreLog() { DictationCall.warn = originalWarn }

    @Test fun paramsUseHaikuLowEffortAndNoThinking() {
        val p = ClassificationCall.params("SISTEMA", "USUARIO")
        assertEquals(ClaudeModels.CLASSIFICATION, p.model().asString())
        assertEquals(4096L, p.maxTokens())
        assertEquals(OutputConfig.Effort.LOW, p.outputConfig().get().effort().get())
        assertFalse(p.thinking().isPresent)
        assertTrue(p.messages().single().toString().contains("USUARIO"))
    }

    @Test fun schemaPropertyIsNamedIsWorkLikeTheDeserializedOne() {
        val out = ClassificationCall.params("s", "u").outputConfig().get()
        val schema = jsonMapper().writeValueAsString(out)
        assertTrue(schema, schema.contains("\"isWork\""))
        assertTrue(schema, schema.contains("\"title\"") && schema.contains("\"items\""))
        assertFalse("Sin nombre alterno por el prefijo is", schema.contains("\"work\""))
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

    @Test fun endTurnDeserializesIsWorkTrue() {
        val r = ClassificationCall.read(message("end_turn", textBlock("""{"items":[{"title":"X","isWork":true},{"title":"Y","isWork":false}]}""")))
        val items = (r as GatewayResult.Ok).value.items
        assertEquals(listOf(TitleMark("X", true), TitleMark("Y", false)), items)
    }

    @Test fun refusalIsRefused() {
        assertEquals(GatewayResult.Refused, ClassificationCall.read(message("refusal", "[]")))
    }

    @Test fun maxTokensIsAnError() {
        assertTrue(ClassificationCall.read(message("max_tokens", textBlock("{\"items\":["))) is GatewayResult.Error)
    }

    @Test fun unreadableJsonIsAnErrorAndLogsOnlyTheClass() {
        assertTrue(ClassificationCall.read(message("end_turn", textBlock("no es json secreto"))) is GatewayResult.Error)
        assertFalse(logged.single().contains("secreto"))
    }
}
