package app.brujula.claude

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * Salida estructurada de Claude al sugerir un cambio de hábito: como mucho una sugerencia, con la referencia `H{id}` del
 * contexto. La app la valida (ver `suggestions.validate`) y nunca la aplica sin que el usuario diga que sí.
 *
 * El schema que se envía es el escrito a mano [Schemas.SUGGESTION]; estas descripciones deben ser idénticas (SchemasTest lo
 * comprueba contra lo que el SDK derivaría de esta clase, solo en la JVM). Los valores por defecto dan el constructor sin
 * argumentos que usa Jackson. Ninguna propiedad Boolean ni prefijo `is`.
 */
@JsonClassDescription("Como mucho una sugerencia de cambio para un hábito que ya existe.")
data class SuggestionResult(
    @field:JsonPropertyDescription("Una sugerencia o ninguna. Vacía si no hay un patrón claro.")
    val suggestions: List<SuggestionItem> = emptyList(),
)

@JsonClassDescription("Una sugerencia. Solo se llenan los campos que aplican a su cambio; los demás van en null.")
data class SuggestionItem(
    @field:JsonPropertyDescription("La referencia H… del hábito, tal como aparece en la lista.")
    val habitRef: String = "",
    @field:JsonPropertyDescription(
        "Para el usuario: 1 o 2 frases en español colombiano, tuteo, sin emojis, que terminan en una pregunta.",
    )
    val message: String = "",
    @field:JsonPropertyDescription("interval | times | target")
    val change: String = "",
    @field:JsonPropertyDescription(
        "Solo interval: cada cuántos minutos avisar (15 a 240). Con fromTime, es el intervalo desde esa hora; sin fromTime, el de todo el día.",
    )
    val intervalMin: Int? = null,
    @field:JsonPropertyDescription(
        "Solo interval, opcional: desde qué hora avisar más seguido, en 24 h, HH:mm, dentro de la vigilia de un día laboral. null cambia el intervalo de todo el día.",
    )
    val fromTime: String? = null,
    @field:JsonPropertyDescription("Solo times: las nuevas horas fijas en 24 h, HH:mm (1 a 12, sin repetir).")
    val times: List<String>? = null,
    @field:JsonPropertyDescription("Solo target: la nueva meta diaria (1 a 20).")
    val dailyTarget: Int? = null,
)
