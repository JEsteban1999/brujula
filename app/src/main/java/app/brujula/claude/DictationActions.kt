package app.brujula.claude

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * Salida estructurada de Claude para un dictado. El SDK deriva el JSON schema de esta clase (Jackson + victools,
 * con la nulabilidad de Kotlin: los campos no nulos quedan como obligatorios) y deserializa la respuesta en ella.
 * Los valores por defecto dan el constructor sin argumentos que usa Jackson.
 */
@JsonClassDescription("Acciones que la app debe aplicar para el dictado y la respuesta corta para el usuario.")
data class DictationResult(
    @field:JsonPropertyDescription("Una acción por cada cosa pedida en el dictado, en el orden en que se dijeron.")
    val actions: List<DictationAction> = emptyList(),
    @field:JsonPropertyDescription("Respuesta corta en español (1 o 2 frases, tuteo, sin emojis) que dice qué se agendó o qué falta.")
    val reply: String = "",
)

@JsonClassDescription("Una acción. Solo se llenan los campos que aplican a su tipo; los demás van en null.")
data class DictationAction(
    @field:JsonPropertyDescription("create_activity | create_habit | need_more_info | plan_request | unsupported")
    val type: String = "",
    @field:JsonPropertyDescription("Nombre corto de la actividad o del hábito, sin fecha ni hora. Ej.: \"Dentista\", \"Tomar agua\".")
    val title: String? = null,
    @field:JsonPropertyDescription("Solo create_activity: WORK | EXERCISE | APPOINTMENT | PERSONAL")
    val activityType: String? = null,
    @field:JsonPropertyDescription("create_activity: fecha de calendario del inicio, yyyy-MM-dd. plan_request: el día lógico afectado, yyyy-MM-dd.")
    val date: String? = null,
    @field:JsonPropertyDescription("Solo create_activity: hora de inicio en 24 h, HH:mm.")
    val start: String? = null,
    @field:JsonPropertyDescription("Solo create_activity: duración en minutos (5 a 720).")
    val durationMin: Int? = null,
    @field:JsonPropertyDescription("Solo create_activity: NONE | DAILY | WEEKDAYS | WORKDAYS | WEEKLY")
    val repeat: String? = null,
    @field:JsonPropertyDescription("Solo create_activity: copiar a Google Calendar (true para citas).")
    val copyToGoogle: Boolean? = null,
    @field:JsonPropertyDescription("Solo create_habit: EVERY | AT_TIMES | BEFORE_BED")
    val habitMode: String? = null,
    @field:JsonPropertyDescription("Solo create_habit con EVERY: cada cuántos minutos (15 a 480).")
    val intervalMin: Int? = null,
    @field:JsonPropertyDescription("Solo create_habit con AT_TIMES: horas fijas en 24 h, HH:mm.")
    val times: List<String>? = null,
    @field:JsonPropertyDescription("Solo create_habit con BEFORE_BED: minutos antes de acostarse (15 a 180).")
    val beforeBedMin: Int? = null,
    @field:JsonPropertyDescription("Solo create_habit: true si solo aplica en horario de trabajo.")
    val workOnly: Boolean? = null,
    @field:JsonPropertyDescription("need_more_info: la pregunta, una sola y corta. unsupported: una frase que explica por qué no se puede.")
    val question: String? = null,
)

/** Una acción ya validada localmente; solo [Activity] y [Habit] se aplican ([PlanRequest] abre la Propuesta, nunca cambia nada). */
sealed interface Validated {
    data class Activity(val activity: app.brujula.activities.Activity) : Validated
    data class Habit(val habit: app.brujula.habits.Habit) : Validated
    /** Un cambio o un armado de día pedido por voz: [day] es el día lógico afectado y [text] lo dictado. */
    data class PlanRequest(val day: java.time.LocalDate, val text: String) : Validated
    data class Question(val text: String) : Validated
    data class Unsupported(val text: String) : Validated
    data class Invalid(val reason: String) : Validated
}
