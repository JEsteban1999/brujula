package app.brujula.claude

import com.anthropic.core.jsonMapper
import com.fasterxml.jackson.databind.JsonNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.anthropic.models.beta.messages.StructuredOutputConfig as BetaStructuredOutputConfig
import com.anthropic.models.messages.StructuredOutputConfig as NonBetaStructuredOutputConfig

/**
 * Candado contra la deriva: el schema escrito a mano debe ser idéntico (como árbol JSON) al que el SDK deriva de la clase.
 * La derivación solo se usa aquí, en la JVM: en ART revienta (Method.getAnnotatedReturnType no existe).
 * Si este test falla, se cambió una clase de salida sin actualizar Schemas.kt (o al revés).
 */
class SchemasTest {
    private val mapper = jsonMapper()

    /** El `format.schema` tal como va en el cuerpo HTTP. */
    private fun schemaOf(outputConfig: Any): JsonNode =
        mapper.readTree(mapper.writeValueAsString(outputConfig)).path("format").path("schema")

    @Test fun dictationSchemaMatchesTheSdkDerivedOne() {
        val derived = BetaStructuredOutputConfig.builder<DictationResult>().format(DictationResult::class.java).build()
        val handWritten = DictationCall.params("s", "u").outputConfig().get()
        val expected = schemaOf(derived.rawOutputConfig)
        assertTrue("El derivado no debe venir vacío", expected.path("properties").size() == 2)
        assertEquals(expected, schemaOf(handWritten))
        // El tipo plan_request y el día afectado viajan en ambos schemas, con la misma descripción.
        val props = expected.path("properties").path("actions").path("items").path("properties")
        assertTrue(props.path("type").path("description").asText().contains("plan_request"))
        assertTrue(props.path("date").path("description").asText().contains("plan_request: el día lógico afectado"))
        assertEquals(props, schemaOf(handWritten).path("properties").path("actions").path("items").path("properties"))
    }

    @Test fun classificationSchemaMatchesTheSdkDerivedOne() {
        val derived = NonBetaStructuredOutputConfig.builder<ClassificationResult>().format(ClassificationResult::class.java).build()
        val handWritten = ClassificationCall.params("s", "u").outputConfig().get()
        val expected = schemaOf(derived.rawOutputConfig)
        assertTrue(expected.path("properties").has("items"))
        assertEquals(expected, schemaOf(handWritten))
    }

    @Test fun suggestionSchemaMatchesTheSdkDerivedOne() {
        val derived = NonBetaStructuredOutputConfig.builder<SuggestionResult>().format(SuggestionResult::class.java).build()
        val handWritten = SuggestionCall.params("s", "u").outputConfig().get()
        val expected = schemaOf(derived.rawOutputConfig)
        assertEquals(setOf("suggestions"), expected.path("properties").fieldNames().asSequence().toSet())
        assertEquals(
            setOf("habitRef", "message", "change", "intervalMin", "fromTime", "times", "dailyTarget"),
            expected.path("properties").path("suggestions").path("items").path("properties").fieldNames().asSequence().toSet(),
        )
        assertEquals(expected, schemaOf(handWritten))
    }

    @Test fun planSchemaMatchesTheSdkDerivedOne() {
        val derived = BetaStructuredOutputConfig.builder<PlanResult>().format(PlanResult::class.java).build()
        val handWritten = PlanCall.params("s", "u").outputConfig().get()
        val expected = schemaOf(derived.rawOutputConfig)
        assertEquals(setOf("summary", "changes"), expected.path("properties").fieldNames().asSequence().toSet())
        assertEquals(
            setOf("op", "ref", "title", "activityType", "date", "start", "durationMin"),
            expected.path("properties").path("changes").path("items").path("properties").fieldNames().asSequence().toSet(),
        )
        assertEquals(expected, schemaOf(handWritten))
    }

    @Test fun formatTypeIsJsonSchema() {
        val d = mapper.readTree(mapper.writeValueAsString(DictationCall.params("s", "u").outputConfig().get()))
        val c = mapper.readTree(mapper.writeValueAsString(ClassificationCall.params("s", "u").outputConfig().get()))
        assertEquals("json_schema", d.path("format").path("type").asText())
        assertEquals("json_schema", c.path("format").path("type").asText())
    }
}
