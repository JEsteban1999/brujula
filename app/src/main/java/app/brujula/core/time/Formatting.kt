package app.brujula.core.time

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

private val ES = Locale.forLanguageTag("es")

/** "Miércoles 18 de noviembre". */
fun formatDayEs(day: LocalDate): String {
    val weekday = day.dayOfWeek.getDisplayName(TextStyle.FULL, ES)
        .replaceFirstChar { it.titlecase(ES) }
    val month = day.month.getDisplayName(TextStyle.FULL, ES).lowercase(ES)
    return "$weekday ${day.dayOfMonth} de $month"
}

/** "9:05" / "19:00". */
fun formatTime(t: LocalTime): String = "%d:%02d".format(t.hour, t.minute)

/** "2 h 30", "45 min", "6 h". */
fun formatMinutes(min: Int): String {
    val h = min / 60
    val m = min % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h %02d".format(m)
    }
}
