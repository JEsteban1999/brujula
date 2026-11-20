package app.brujula.planning

import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.Occurrence
import app.brujula.activities.Overrides
import app.brujula.activities.workMinutes
import app.brujula.calendar.CalendarSource
import app.brujula.calendar.ExternalEvent
import app.brujula.calendar.WorkClassificationRepository
import app.brujula.calendar.eventsOnLogicalDay
import app.brujula.core.time.TimeSpan
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitRepository
import app.brujula.routine.Routine
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

/** Todo lo que [buildDaySnapshot] necesita: datos ya leídos, sin acceso a repositorios ni calendarios. */
data class SnapshotInputs(
    val routine: Routine,
    val activities: List<Activity>,
    val done: Set<Pair<Long, LocalDate>>,
    val overrides: Overrides,
    /** Eventos externos ya sin copias de Brújula. */
    val external: List<ExternalEvent>,
    val isWork: (ExternalEvent) -> Boolean,
)

/** La única cuenta de un día (Hoy, Propuesta, Noche, Mañana y Semana): spill + lógicas con overrides, externos, trabajo y ejercicio. */
fun buildDaySnapshot(day: LocalDate, inputs: SnapshotInputs, water: Habit? = null, waterCount: Int? = null): DaySnapshot {
    val window = inputs.routine.awakeWindow(day)
    val logical = logicalOccurrences(day, inputs.activities, inputs.done, inputs.overrides)
    val spill = spillOccurrences(day, inputs.activities, inputs.done, window, inputs.overrides)

    val external = eventsOnLogicalDay(day, inputs.external, window).filter { !it.allDay }
        .map { FixedEvent(it, inputs.isWork(it)) }

    val logicalSpan = TimeSpan(day.atTime(LOGICAL_DAY_START), day.plusDays(1).atTime(LOGICAL_DAY_START))
    val minutes = workMinutes(logical, external.filter { it.countsAsWork }.map { it.event }, logicalSpan)

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
            workGoal = if (dayTypeOf(day).isRest) null else inputs.routine.workGoalMin,
            waterCount = waterCount,
            waterTarget = water?.dailyTarget,
            exercise = exercise,
        ),
    )
}

/** Foto de un día con las mismas reglas que Hoy. Si los calendarios fallan, sigue sin externos. */
open class DaySnapshotProvider(
    private val routineRepository: RoutineRepository,
    private val activityRepository: ActivityRepository,
    private val habitRepository: HabitRepository,
    private val calendarSource: CalendarSource,
    private val classifications: WorkClassificationRepository,
) {
    open suspend fun snapshot(day: LocalDate): DaySnapshot = build(day, withExternal = true)

    /** Lo mismo sin leer los calendarios del sistema: para cuando no hay tiempo de esperarlos (widget). */
    open suspend fun ownSnapshot(day: LocalDate): DaySnapshot = build(day, withExternal = false)

    private suspend fun build(day: LocalDate, withExternal: Boolean): DaySnapshot {
        val routine = routineRepository.routine.first()
        val activities = activityRepository.observeAll().first()
        val done = activityRepository.observeDoneKeys().first()
        val overrides = activityRepository.observeOverrides().first()
        val linked = activityRepository.observeGoogleEventIds().first()

        // Segunda defensa, como en Hoy: un evento enlazado a una actividad (copia de Brújula) nunca es externo.
        val raw = if (!withExternal) emptyList() else {
            calendarSource.selectedEventsAroundOrEmpty(routineRepository.calendarPrefs.selectedCalendarIds, day)
                .filter { it.eventId !in linked }
        }
        val marks = workMarks()

        val water = habitRepository.observeHabits().first().firstOrNull { it.active && it.icon == HabitIcon.WATER }
        val waterCount = water?.let { habitRepository.observeCountsFor(day).first()[it.id] ?: 0 }

        val inputs = SnapshotInputs(routine, activities, done, overrides, raw) { classifications.isWork(it, marks) }
        return buildDaySnapshot(day, inputs, water, waterCount)
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
