package app.brujula.activities

import app.brujula.core.time.TimeSpan
import app.brujula.routine.dayTypeOf
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class ActivityType(val labelEs: String) {
    WORK("Trabajo"), EXERCISE("Ejercicio"), APPOINTMENT("Cita"), PERSONAL("Personal")
}

enum class Repeat(val labelEs: String) {
    NONE("No se repite"), DAILY("Todos los días"), WEEKDAYS("Lunes a viernes"), WORKDAYS("Días laborales (sin festivos)"), WEEKLY("Cada semana")
}

data class Activity(
    val id: Long = 0,
    val title: String,
    val type: ActivityType,
    val date: LocalDate,
    val start: LocalTime,
    val durationMin: Int,
    val repeat: Repeat = Repeat.NONE,
    val remindBeforeMin: Int? = 15,
    val copyToGoogle: Boolean = type == ActivityType.APPOINTMENT,
    val googleEventId: Long? = null,
    val origin: ActivityOrigin = ActivityOrigin.UNKNOWN,
)

/** De donde vino la actividad; las filas anteriores a la fase 3A quedan en UNKNOWN. */
enum class ActivityOrigin { UNKNOWN, DICTATION, PLAN }

/**
 * Cambio de una sola ocurrencia de una serie. [day] es la fecha de la ocurrencia en la serie (la misma clave que los
 * hechos); [start] y [durationMin] reemplazan lo de la serie solo ese dia, y [skipped] la oculta.
 */
data class OccurrenceOverride(
    val activityId: Long,
    val day: LocalDate,
    val start: LocalDateTime?,
    val durationMin: Int?,
    val skipped: Boolean,
)

typealias Overrides = Map<Pair<Long, LocalDate>, OccurrenceOverride>

fun Activity.occursOn(day: LocalDate): Boolean {
    if (day < date) return false
    return when (repeat) {
        Repeat.NONE -> day == date
        Repeat.DAILY -> true
        Repeat.WEEKDAYS -> day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY
        Repeat.WORKDAYS -> !dayTypeOf(day).isRest
        Repeat.WEEKLY -> day.dayOfWeek == date.dayOfWeek
    }
}

data class Occurrence(val activity: Activity, val day: LocalDate, val span: TimeSpan, val done: Boolean)

fun occurrencesOn(
    day: LocalDate,
    activities: List<Activity>,
    doneKeys: Set<Pair<Long, LocalDate>>,
    overrides: Overrides = emptyMap(),
): List<Occurrence> = activities
    .filter { it.occursOn(day) }
    .mapNotNull { a ->
        val o = overrides[a.id to day]
        if (o?.skipped == true) return@mapNotNull null
        val start = o?.start ?: day.atTime(a.start)
        val minutes = (o?.durationMin ?: a.durationMin).toLong()
        Occurrence(a, day, TimeSpan(start, start.plusMinutes(minutes)), (a.id to day) in doneKeys)
    }
    .sortedBy { it.span.start }
