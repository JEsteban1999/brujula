package app.brujula.calendar

import app.brujula.core.time.TimeSpan
import app.brujula.routine.logicalDay
import java.time.LocalDate

/** Paquete con el que Brujula marca sus propias copias en Google Calendar. */
const val BRUJULA_PACKAGE = "app.brujula"

data class DeviceCalendar(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String,
    val color: Int,
    val isPrimaryGoogle: Boolean,
)

val DeviceCalendar.sourceLabel: String
    get() = when {
        accountType == "com.google" -> "Google"
        accountType.contains("outlook", ignoreCase = true) || accountType.contains("microsoft", ignoreCase = true) -> "Outlook"
        else -> accountName
    }

data class ExternalEvent(
    val eventId: Long,
    val instanceBegin: Long,
    val calendarId: Long,
    val calendarName: String,
    val source: String,
    val title: String,
    val span: TimeSpan,
    val allDay: Boolean,
    val organizer: String?,
) {
    val key: String get() = "$eventId:$instanceBegin"
}

/**
 * Eventos del dia logico [day]: los de horas que empiezan en el (04:00 a 03:59), los de todo el dia de esa fecha y,
 * si se pasa [awake] (la vigilia de [day]), los que empezaron el dia logico anterior y aun la cruzan.
 */
fun eventsOnLogicalDay(
    day: LocalDate,
    events: List<ExternalEvent>,
    awake: TimeSpan? = null,
): List<ExternalEvent> = events
    .filter { e ->
        if (e.allDay) {
            e.span.start.toLocalDate() == day
        } else {
            val startDay = logicalDay(e.span.start)
            startDay == day || (awake != null && startDay == day.minusDays(1) && e.span.overlaps(awake))
        }
    }
    .distinctBy { it.key }
    .sortedBy { it.span.start }
