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
    /** Color del calendario de origen (ARGB); 0 si no se conoce. */
    val calendarColor: Int = 0,
) {
    val key: String get() = "$eventId:$instanceBegin"
}

/**
 * Eventos del dia logico [day]: los de horas que empiezan en el (04:00 a 03:59), los de todo el dia de esa fecha y,
 * si [awake] (la vigilia de [day]) no es null, los que empezaron el dia logico anterior y aun la cruzan.
 */
fun eventsOnLogicalDay(
    day: LocalDate,
    events: List<ExternalEvent>,
    awake: TimeSpan?,
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

private val DIACRITICS = Regex("""\p{Mn}+""")
private val WHITESPACE = Regex("[\\s\\p{Z}]+")

/** Clave para recordar si un evento cuenta como trabajo: titulo sin tildes, en minusculas, espacios colapsados. */
fun classificationKey(e: ExternalEvent): String =
    java.text.Normalizer.normalize(e.title, java.text.Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase(java.util.Locale.ROOT)
        .replace(WHITESPACE, " ")
        .trim()
