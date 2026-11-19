package app.brujula.claude

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * Salida estructurada de Claude al planificar un día: solo los cambios (con referencias del contexto) y un resumen. La vista
 * previa, las etiquetas y los totales se calculan en la app (D2).
 *
 * El schema que se envía es el escrito a mano [Schemas.PLAN]; estas descripciones deben ser idénticas (SchemasTest lo
 * comprueba contra lo que el SDK derivaría de esta clase, solo en la JVM). Los valores por defecto dan el constructor sin
 * argumentos que usa Jackson.
 */
@JsonClassDescription("Cambios propuestos para el día y un resumen corto para el usuario.")
data class PlanResult(
    @field:JsonPropertyDescription(
        "En español colombiano, 1 a 3 frases, tuteo, sin emojis: qué cambias y por qué. Aquí van también las sugerencias sobre eventos externos.",
    )
    val summary: String = "",
    @field:JsonPropertyDescription("Solo lo que cambia: primero los move y skip, después los create. Vacío si no hace falta cambiar nada.")
    val changes: List<PlanChange> = emptyList(),
)

@JsonClassDescription("Un cambio. Solo se llenan los campos que aplican a su operación; los demás van en null.")
data class PlanChange(
    @field:JsonPropertyDescription("create | move | skip")
    val op: String = "",
    @field:JsonPropertyDescription("move y skip: la referencia A… de un bloque propio movible, tal como aparece en la lista. Nunca E…. create: null.")
    val ref: String? = null,
    @field:JsonPropertyDescription("Solo create: nombre corto del bloque, sin fecha ni hora. Ej.: \"Bloque de trabajo\".")
    val title: String? = null,
    @field:JsonPropertyDescription("Solo create: WORK | EXERCISE | PERSONAL")
    val activityType: String? = null,
    @field:JsonPropertyDescription("create y move: fecha de calendario del nuevo inicio, yyyy-MM-dd.")
    val date: String? = null,
    @field:JsonPropertyDescription("create y move: nueva hora de inicio en 24 h, HH:mm.")
    val start: String? = null,
    @field:JsonPropertyDescription("create: duración en minutos (5 a 720). move: la nueva duración, o null para dejar la misma.")
    val durationMin: Int? = null,
)
