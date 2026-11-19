package app.brujula.claude

import app.brujula.activities.ActivityRepository
import app.brujula.calendar.CalendarSource
import app.brujula.calendar.ExternalEvent
import app.brujula.calendar.eventsOnLogicalDay
import app.brujula.core.time.Clock
import app.brujula.habits.HabitRepository
import app.brujula.routine.RoutineRepository
import app.brujula.routine.awakeWindow
import app.brujula.routine.dayTypeOf
import app.brujula.routine.logicalDay
import app.brujula.today.logicalOccurrences
import app.brujula.today.selectedEventsAroundOrEmpty
import app.brujula.today.spillOccurrences
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Arma el [DictationContext] con lo mismo que muestra Hoy: actividades propias del día lógico (más lo de ayer que cruza la
 * vigilia) y eventos externos con hora de los calendarios elegidos, sin los de todo el día ni las copias de Brújula.
 */
class DictationContextProvider(
    private val clock: Clock,
    private val routineRepository: RoutineRepository,
    private val activityRepository: ActivityRepository,
    private val habitRepository: HabitRepository,
    private val calendarSource: CalendarSource,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** [dictatedAt] (epoch ms): dictado de la cola; "hoy/mañana" se cuentan desde su día lógico, en la zona del teléfono. */
    suspend fun current(dictatedAt: Long? = null): DictationContext {
        val now = clock.now()
        val zone = zone()
        val dictated = dictatedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
        val today = logicalDay(dictated ?: now)
        val tomorrow = today.plusDays(1)
        val routine = routineRepository.routine.first()
        val activities = activityRepository.observeAll().first()
        val done = activityRepository.observeDoneKeys().first()
        val overrides = activityRepository.observeOverrides().first()
        val linked = activityRepository.observeGoogleEventIds().first()
        val raw = externalAround(today).filter { it.eventId !in linked }

        fun busyOn(day: LocalDate): List<BusyItem> {
            val window = routine.awakeWindow(day)
            val own = (spillOccurrences(day, activities, done, window, overrides) + logicalOccurrences(day, activities, done, overrides))
                .map { BusyItem(it.activity.title, it.span.start, it.span.end, external = false) }
            val external = eventsOnLogicalDay(day, raw, window).filter { !it.allDay }
                .map { BusyItem(it.displayTitle, it.span.start, it.span.end, external = true) }
            return (own + external).sortedBy { it.start }
        }

        return DictationContext(
            now = now,
            zone = zone,
            logicalToday = today,
            todayType = dayTypeOf(today),
            tomorrowType = dayTypeOf(tomorrow),
            routine = routine,
            busyToday = busyOn(today),
            busyTomorrow = busyOn(tomorrow),
            habitNames = habitRepository.observeHabits().first().filter { it.active }.map { it.name },
            dictatedAt = dictated,
        )
    }

    /** Un fallo al leer los calendarios no impide dictar: se sigue solo con lo propio. */
    private suspend fun externalAround(day: LocalDate): List<ExternalEvent> =
        calendarSource.selectedEventsAroundOrEmpty(routineRepository.calendarPrefs.selectedCalendarIds, day)
}
