package app.brujula.activities

import app.brujula.core.time.TimeSpan
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

enum class ActivityType(val labelEs: String) {
    WORK("Trabajo"), EXERCISE("Ejercicio"), APPOINTMENT("Cita"), PERSONAL("Personal")
}

enum class Repeat(val labelEs: String) {
    NONE("No se repite"), DAILY("Todos los días"), WEEKDAYS("Lunes a viernes"), WEEKLY("Cada semana")
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
)

fun Activity.occursOn(day: LocalDate): Boolean {
    if (day < date) return false
    return when (repeat) {
        Repeat.NONE -> day == date
        Repeat.DAILY -> true
        Repeat.WEEKDAYS -> day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY
        Repeat.WEEKLY -> day.dayOfWeek == date.dayOfWeek
    }
}

data class Occurrence(val activity: Activity, val day: LocalDate, val span: TimeSpan, val done: Boolean)

fun occurrencesOn(
    day: LocalDate,
    activities: List<Activity>,
    doneKeys: Set<Pair<Long, LocalDate>>,
): List<Occurrence> = activities
    .filter { it.occursOn(day) }
    .map { a ->
        val start = day.atTime(a.start)
        Occurrence(a, day, TimeSpan(start, start.plusMinutes(a.durationMin.toLong())), (a.id to day) in doneKeys)
    }
    .sortedBy { it.span.start }
