package app.brujula.today

import app.brujula.activities.Activity
import app.brujula.activities.Occurrence
import app.brujula.activities.Overrides
import app.brujula.activities.occurrencesOn
import app.brujula.calendar.CalendarSource
import app.brujula.calendar.ExternalEvent
import app.brujula.core.time.TimeSpan
import app.brujula.routine.logicalDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** El día lógico empieza a las 04:00. */
internal val LOGICAL_DAY_START: LocalTime = LocalTime.of(4, 0)

/** Ocurrencias que empiezan en el día lógico [day] (04:00 a 03:59), sea su fecha de calendario ayer, hoy o mañana. */
fun logicalOccurrences(
    day: LocalDate,
    activities: List<Activity>,
    done: Set<Pair<Long, LocalDate>>,
    overrides: Overrides = emptyMap(),
): List<Occurrence> =
    (-1L..1L).flatMap { occurrencesOn(day.plusDays(it), activities, done, overrides) }
        .filter { logicalDay(it.span.start) == day }

/** Ocurrencias de ayer que no son del día lógico [day] pero aún cruzan su vigilia [window]. */
fun spillOccurrences(
    day: LocalDate,
    activities: List<Activity>,
    done: Set<Pair<Long, LocalDate>>,
    window: TimeSpan,
    overrides: Overrides = emptyMap(),
): List<Occurrence> =
    occurrencesOn(day.minusDays(1), activities, done, overrides)
        .filter { logicalDay(it.span.start) != day && it.span.overlaps(window) }

/**
 * Eventos de los calendarios elegidos alrededor del día lógico [day] (de −1 a +2 días desde las 04:00), suficiente para
 * [day] y el día siguiente. Vacío si no hay calendarios elegidos o falta el permiso de lectura.
 */
suspend fun CalendarSource.eventsAroundLogicalDay(selectedIds: Set<Long>?, day: LocalDate): List<ExternalEvent> {
    if (selectedIds.isNullOrEmpty() || !hasReadPermission()) return emptyList()
    val start = day.atTime(LOGICAL_DAY_START)
    return events(selectedIds, start.minusDays(1), start.plusDays(2))
}

/**
 * [eventsAroundLogicalDay] con los calendarios elegidos ([selectedIds]), fuera del hilo principal. Un fallo al leer los
 * calendarios (permiso revocado, proveedor caído) no impide seguir: da una lista vacía y se sigue solo con lo propio.
 */
suspend fun CalendarSource.selectedEventsAroundOrEmpty(selectedIds: Flow<Set<Long>?>, day: LocalDate): List<ExternalEvent> = try {
    val ids = selectedIds.first()
    withContext(Dispatchers.IO) { eventsAroundLogicalDay(ids, day) }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    emptyList()
}

/**
 * Eventos de los calendarios elegidos que se cruzan con [from, to), fuera del hilo principal: una sola consulta para
 * varios días. Mismas reglas de permiso y de error que [selectedEventsAroundOrEmpty].
 */
suspend fun CalendarSource.selectedEventsBetweenOrEmpty(
    selectedIds: Flow<Set<Long>?>,
    from: LocalDateTime,
    to: LocalDateTime,
): List<ExternalEvent> = try {
    val ids = selectedIds.first()
    withContext(Dispatchers.IO) {
        if (ids.isNullOrEmpty() || !hasReadPermission()) emptyList() else events(ids, from, to)
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    emptyList()
}
