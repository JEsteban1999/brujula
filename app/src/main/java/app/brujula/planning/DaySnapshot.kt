package app.brujula.planning

import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.Occurrence
import app.brujula.activities.workMinutes
import app.brujula.calendar.CalendarSource
import app.brujula.calendar.ExternalEvent
import app.brujula.calendar.WorkClassificationRepository
import app.brujula.calendar.eventsOnLogicalDay
import app.brujula.core.time.TimeSpan
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitRepository
import app.brujula.routine.RoutineRepository
import app.brujula.routine.awakeWindow
import app.brujula.routine.dayTypeOf
import app.brujula.today.LOGICAL_DAY_START
import app.brujula.today.logicalOccurrences
import app.brujula.today.selectedEventsAroundOrEmpty
import app.brujula.today.spillOccurrences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.LocalDate

enum class ExerciseState { DONE, PENDING, NONE }

/**
 * Cómo va un día: minutos de trabajo (propio + externo marcado como trabajo, solo dentro del día lógico), la meta (null en
 * descanso), los vasos de agua del primer hábito activo con ícono WATER (null si no hay) y el ejercicio del día lógico.
 */
data class DaySummary(
    val workMinutes: Int,
    val workGoal: IntRange?,
    val waterCount: Int?,
    val waterTarget: Int?,
    val exercise: ExerciseState,
)

/** Evento externo con hora (solo lectura) y si cuenta como trabajo según la clasificación guardada. */
data class FixedEvent(val event: ExternalEvent, val countsAsWork: Boolean)

data class DaySnapshot(
    val day: LocalDate,
    /** Lo de ayer que cruza la vigilia (spill) más las ocurrencias del día lógico, con overrides, por hora. */
    val own: List<Occurrence>,
    /** Eventos externos con hora del día lógico, sin los de todo el día ni las copias de Brújula, por hora. */
    val external: List<FixedEvent>,
    val summary: DaySummary,
)

/** Foto de un día con las mismas reglas que Hoy. Si los calendarios fallan, sigue sin externos. */
class DaySnapshotProvider(
    private val routineRepository: RoutineRepository,
    private val activityRepository: ActivityRepository,
    private val habitRepository: HabitRepository,
    private val calendarSource: CalendarSource,
    private val classifications: WorkClassificationRepository,
) {
    suspend fun snapshot(day: LocalDate): DaySnapshot {
        val routine = routineRepository.routine.first()
        val activities = activityRepository.observeAll().first()
        val done = activityRepository.observeDoneKeys().first()
        val overrides = activityRepository.observeOverrides().first()
        val linked = activityRepository.observeGoogleEventIds().first()
        val window = routine.awakeWindow(day)

        val logical = logicalOccurrences(day, activities, done, overrides)
        val spill = spillOccurrences(day, activities, done, window, overrides)

        // Segunda defensa, como en Hoy: un evento enlazado a una actividad (copia de Brújula) nunca es externo.
        val raw = calendarSource.selectedEventsAroundOrEmpty(routineRepository.calendarPrefs.selectedCalendarIds, day)
            .filter { it.eventId !in linked }
        val marks = workMarks()
        val external = eventsOnLogicalDay(day, raw, window).filter { !it.allDay }
            .map { FixedEvent(it, classifications.isWork(it, marks)) }

        val logicalSpan = TimeSpan(day.atTime(LOGICAL_DAY_START), day.plusDays(1).atTime(LOGICAL_DAY_START))
        val minutes = workMinutes(logical, external.filter { it.countsAsWork }.map { it.event }, logicalSpan)

        val water = habitRepository.observeHabits().first().firstOrNull { it.active && it.icon == HabitIcon.WATER }
        val waterCount = water?.let { habitRepository.observeCountsFor(day).first()[it.id] ?: 0 }

        val exercises = logical.filter { it.activity.type == ActivityType.EXERCISE }
        val exercise = when {
            exercises.isEmpty() -> ExerciseState.NONE
            exercises.any { it.done } -> ExerciseState.DONE
            else -> ExerciseState.PENDING
        }

        return DaySnapshot(
            day = day,
            own = (spill + logical).sortedBy { it.span.start },
            external = external,
            summary = DaySummary(
                workMinutes = minutes,
                workGoal = if (dayTypeOf(day).isRest) null else routine.workGoalMin,
                waterCount = waterCount,
                waterTarget = water?.dailyTarget,
                exercise = exercise,
            ),
        )
    }

    /** Sin marcas legibles, ningún externo cuenta como trabajo (como en Hoy). */
    private suspend fun workMarks(): Map<String, Boolean> = try {
        classifications.observe().first()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyMap()
    }
}
