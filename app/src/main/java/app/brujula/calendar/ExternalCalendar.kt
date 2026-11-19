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

    /** Título para mostrar; el título queda vacío si el evento no tiene, para no clasificarlo como trabajo. */
    val displayTitle: String get() = title.ifBlank { "(Sin título)" }
}

/**
 * Eventos del dia logico [day]: los de horas que empiezan en el (04:00 a 03:59), los de todo el dia cuyo rango de fechas
 * [inicio, fin) lo incluye (el fin es exclusivo, como lo guarda CalendarContract) y, si [awake] (la vigilia de [day]) no es
 * null, los de horas que empezaron un dia logico anterior y aun cruzan esa vigilia.
 */
fun eventsOnLogicalDay(
    day: LocalDate,
    events: List<ExternalEvent>,
    awake: TimeSpan?,
): List<ExternalEvent> = events
    .filter { e ->
        if (e.allDay) {
            val first = e.span.start.toLocalDate()
            val endExclusive = e.span.end.toLocalDate()
            day == first || (day > first && day < endExclusive)
        } else {
            val startDay = logicalDay(e.span.start)
            startDay == day || (awake != null && startDay < day && e.span.overlaps(awake))
        }
    }
    .distinctBy { it.key }
    .sortedBy { it.span.start }

private val DIACRITICS = Regex("""\p{Mn}+""")
private val WHITESPACE = Regex("[\\s\\p{Z}]+")

/** Clave para recordar si un evento cuenta como trabajo: titulo sin tildes, en minusculas, espacios colapsados. */
fun classificationKey(e: ExternalEvent): String = normalizeTitle(e.title)

/** Misma normalizacion que [classificationKey], para un titulo suelto. */
fun normalizeTitle(title: String): String =
    java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase(java.util.Locale.ROOT)
        .replace(WHITESPACE, " ")
        .trim()
