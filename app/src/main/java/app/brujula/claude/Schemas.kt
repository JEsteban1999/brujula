package app.brujula.claude

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.messages.JsonOutputFormat
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule

/**
 * JSON schemas escritos a mano para la salida estructurada de Claude.
 *
 * No se usa la derivación del SDK desde la clase (`format(Class)` / `outputConfig(Class)`): pasa por victools, que llama a
 * `Method.getAnnotatedReturnType()`, y ART no tiene ese método (NoSuchMethodError en el teléfono). Estos mapas reproducen
 * exactamente lo que el SDK derivaba de [DictationResult], [ClassificationResult] y [PlanResult]; SchemasTest lo compara en la JVM.
 *
 * Igual que en el derivado, todas las propiedades son obligatorias: las nulables de Kotlin admiten `null` en su `type`.
 */
internal object Schemas {
    private const val DRAFT = "https://json-schema.org/draft/2020-12/schema"

    private fun prop(type: String, description: String) = mapOf("type" to type, "description" to description)

    private fun nullable(type: String, description: String) = mapOf("type" to listOf(type, "null"), "description" to description)

    private fun obj(description: String, properties: Map<String, Any>) = mapOf(
        "type" to "object",
        "properties" to properties,
        "required" to properties.keys.sorted(),
        "description" to description,
        "additionalProperties" to false,
    )

    private val ACTION = obj(
        "Una acción. Solo se llenan los campos que aplican a su tipo; los demás van en null.",
        mapOf(
            "type" to prop("string", "create_activity | create_habit | need_more_info | plan_request | unsupported"),
            "title" to nullable("string", "Nombre corto de la actividad o del hábito, sin fecha ni hora. Ej.: \"Dentista\", \"Tomar agua\"."),
            "activityType" to nullable("string", "Solo create_activity: WORK | EXERCISE | APPOINTMENT | PERSONAL"),
            "date" to nullable("string", "create_activity: fecha de calendario del inicio, yyyy-MM-dd. plan_request: el día lógico afectado, yyyy-MM-dd."),
            "start" to nullable("string", "Solo create_activity: hora de inicio en 24 h, HH:mm."),
            "durationMin" to nullable("integer", "Solo create_activity: duración en minutos (5 a 720)."),
            "repeat" to nullable("string", "Solo create_activity: NONE | DAILY | WEEKDAYS | WORKDAYS | WEEKLY"),
            "copyToGoogle" to nullable("boolean", "Solo create_activity: copiar a Google Calendar (true para citas)."),
            "habitMode" to nullable("string", "Solo create_habit: EVERY | AT_TIMES | BEFORE_BED"),
            "intervalMin" to nullable("integer", "Solo create_habit con EVERY: cada cuántos minutos (15 a 480)."),
            "times" to mapOf(
                "type" to listOf("array", "null"),
                "items" to mapOf("type" to "string"),
                "description" to "Solo create_habit con AT_TIMES: horas fijas en 24 h, HH:mm.",
            ),
            "beforeBedMin" to nullable("integer", "Solo create_habit con BEFORE_BED: minutos antes de acostarse (15 a 180)."),
            "workOnly" to nullable("boolean", "Solo create_habit: true si solo aplica en horario de trabajo."),
            "question" to nullable(
                "string",
                "need_more_info: la pregunta, una sola y corta. unsupported: una frase que explica por qué no se puede.",
            ),
        ),
    )

    /** Schema de [DictationResult] / [DictationAction]. */
    val DICTATION: Map<String, Any> = mapOf("\$schema" to DRAFT) + obj(
        "Acciones que la app debe aplicar para el dictado y la respuesta corta para el usuario.",
        mapOf(
            "actions" to mapOf(
                "type" to "array",
                "items" to ACTION,
                "description" to "Una acción por cada cosa pedida en el dictado, en el orden en que se dijeron.",
            ),
            "reply" to prop("string", "Respuesta corta en español (1 o 2 frases, tuteo, sin emojis) que dice qué se agendó o qué falta."),
        ),
    )

    private val TITLE_MARK = obj(
        "Clasificación de un título.",
        mapOf(
            "title" to prop("string", "El título exactamente como se recibió."),
            // Mismo nombre que @JsonProperty("isWork") en TitleMark (no "work").
            "isWork" to prop("boolean", "true si es trabajo; false si no lo es o hay duda."),
        ),
    )

    /** Schema de [ClassificationResult] / [TitleMark]. */
    val CLASSIFICATION: Map<String, Any> = mapOf("\$schema" to DRAFT) + obj(
        "Una clasificación por cada título recibido.",
        mapOf(
            "items" to mapOf(
                "type" to "array",
                "items" to TITLE_MARK,
                "description" to "Un elemento por título, con el título copiado exactamente como se recibió.",
            ),
        ),
    )

    private val PLAN_CHANGE = obj(
        "Un cambio. Solo se llenan los campos que aplican a su operación; los demás van en null.",
        mapOf(
            "op" to prop("string", "create | move | skip"),
            "ref" to nullable(
                "string",
                "move y skip: la referencia A… de un bloque propio movible, tal como aparece en la lista. Nunca E…. create: null.",
            ),
            "title" to nullable("string", "Solo create: nombre corto del bloque, sin fecha ni hora. Ej.: \"Bloque de trabajo\"."),
            "activityType" to nullable("string", "Solo create: WORK | EXERCISE | PERSONAL"),
            "date" to nullable("string", "create y move: fecha de calendario del nuevo inicio, yyyy-MM-dd."),
            "start" to nullable("string", "create y move: nueva hora de inicio en 24 h, HH:mm."),
            "durationMin" to nullable("integer", "create: duración en minutos (5 a 720). move: la nueva duración, o null para dejar la misma."),
        ),
    )

    /** Schema de [PlanResult] / [PlanChange]. */
    val PLAN: Map<String, Any> = mapOf("\$schema" to DRAFT) + obj(
        "Cambios propuestos para el día y un resumen corto para el usuario.",
        mapOf(
            "summary" to prop(
                "string",
                "En español colombiano, 1 a 3 frases, tuteo, sin emojis: qué cambias y por qué. Aquí van también las sugerencias sobre eventos externos.",
            ),
            "changes" to mapOf(
                "type" to "array",
                "items" to PLAN_CHANGE,
                "description" to "Solo lo que cambia: primero los move y skip, después los create. Vacío si no hace falta cambiar nada.",
            ),
        ),
    )

    /** Formato `json_schema` para el endpoint beta (dictado y planificación). */
    fun betaFormat(schema: Map<String, Any>): BetaJsonOutputFormat =
        BetaJsonOutputFormat.builder()
            .schema(BetaJsonOutputFormat.Schema.builder().apply { schema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build())
            .build()

    /** Formato `json_schema` para el endpoint no beta (clasificación). */
    fun format(schema: Map<String, Any>): JsonOutputFormat =
        JsonOutputFormat.builder()
            .schema(JsonOutputFormat.Schema.builder().apply { schema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }.build())
            .build()
}

/** Lee el texto JSON de la respuesta en las clases de Kotlin (sin pasar por la lectura tipada del SDK). */
internal object StructuredJson {
    val mapper: ObjectMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    /** Lanza si el texto no es JSON válido para [type] (o es `null`). */
    fun <T : Any> parse(text: String, type: Class<T>): T =
        mapper.readValue(text, type) ?: throw IllegalArgumentException("JSON nulo")
}
