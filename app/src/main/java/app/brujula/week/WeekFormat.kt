package app.brujula.week

import app.brujula.activities.WorkStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

private val MONTHS = listOf(
    "enero", "febrero", "marzo", "abril", "mayo", "junio",
    "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
)
private val WEEKDAYS = mapOf(
    DayOfWeek.MONDAY to "lunes", DayOfWeek.TUESDAY to "martes", DayOfWeek.WEDNESDAY to "miércoles",
    DayOfWeek.THURSDAY to "jueves", DayOfWeek.FRIDAY to "viernes", DayOfWeek.SATURDAY to "sábado", DayOfWeek.SUNDAY to "domingo",
)

internal fun weekdayName(day: LocalDate): String = WEEKDAYS.getValue(day.dayOfWeek)

/** `16 – 22 de noviembre`, `30 de noviembre – 6 de diciembre`, `28 de diciembre de 2026 – 3 de enero de 2027`. */
fun weekRangeLabel(monday: LocalDate): String {
    val sunday = monday.plusDays(6)
    val m1 = MONTHS[monday.monthValue - 1]
    val m2 = MONTHS[sunday.monthValue - 1]
    return when {
        monday.year != sunday.year -> "${monday.dayOfMonth} de $m1 de ${monday.year} – ${sunday.dayOfMonth} de $m2 de ${sunday.year}"
        monday.month != sunday.month -> "${monday.dayOfMonth} de $m1 – ${sunday.dayOfMonth} de $m2"
        else -> "${monday.dayOfMonth} – ${sunday.dayOfMonth} de $m2"
    }
}

fun weekTitle(monday: LocalDate, currentMonday: LocalDate): String = when (monday) {
    currentMonday -> "Tu semana"
    currentMonday.minusDays(7) -> "Semana pasada"
    currentMonday.plusDays(7) -> "Próxima semana"
    else -> "Semana"
}

private fun hoursText(minutes: Int): String {
    val text = String.format(Locale.ROOT, "%.1f", minutes / 60.0)
    return text.removeSuffix(".0")
}

/** Una cifra decimal con punto, sin `.0`; `–` en 0 (390 → `6.5h`, 360 → `6h`). */
fun barLabel(minutes: Int): String = if (minutes <= 0) "–" else hoursText(minutes) + "h"

fun goalLabel(range: IntRange): String = "Meta: ${hoursText(range.first)}–${hoursText(range.last)} h por día"

private fun capitalized(s: String) = s.replaceFirstChar { it.uppercase() }

fun dayDetailTitle(day: LocalDate, today: LocalDate): String =
    if (day == today) "Hoy, ${weekdayName(day)} ${day.dayOfMonth}" else "${capitalized(weekdayName(day))} ${day.dayOfMonth}"

fun workStatusLabel(status: WorkStatus): String = when (status) {
    WorkStatus.LOW -> "Muy por debajo"
    WorkStatus.BELOW_GOAL -> "Debajo de tu meta"
    WorkStatus.IN_GOAL -> "En tu meta"
    WorkStatus.ABOVE_GOAL -> "Sobre tu meta"
}

/** Para voz (TalkBack): `6 h 5 min`, `6 h`, `45 min`; con resto siempre dice los minutos. */
internal fun durationText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}

/** Para tarjetas: `6 h 05`, `6 h`, `45 min`. */
fun compactDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h ${"%02d".format(m)}"
    }
}
