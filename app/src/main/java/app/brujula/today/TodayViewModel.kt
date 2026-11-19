package app.brujula.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.Occurrence
import app.brujula.activities.WorkStatus
import app.brujula.activities.freeSlots
import app.brujula.activities.occurrencesOn
import app.brujula.activities.workMinutes
import app.brujula.activities.workStatus
import app.brujula.core.time.Clock
import app.brujula.core.time.TimeSpan
import app.brujula.core.time.formatDayEs
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitRepository
import app.brujula.reminders.PermissionStatus
import app.brujula.routine.DayKind
import app.brujula.routine.DayType
import app.brujula.routine.Routine
import app.brujula.routine.RoutineRepository
import app.brujula.routine.awakeWindow
import app.brujula.routine.dayTypeOf
import app.brujula.routine.logicalDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

data class HabitChip(val habitId: Long, val name: String, val icon: HabitIcon, val count: Int, val target: Int)

data class TimelineEntry(val occurrence: Occurrence, val isNow: Boolean)

data class TodayUiState(
    val dayLabel: String,
    val workMinutes: Int,
    /** Null en días de descanso: no hay meta. */
    val workGoal: IntRange?,
    val workStatus: WorkStatus?,
    val habits: List<HabitChip>,
    val timeline: List<TimelineEntry>,
    val freeSlots: List<TimeSpan>,
    val permissions: PermissionStatus,
    /** Día lógico mostrado (para prellenar formularios). */
    val day: LocalDate,
    val dayType: DayType = DayType(DayKind.WORKDAY),
)

private const val MAX_VISIBLE_HABITS = 2
private const val TICK_MS = 30_000L

class TodayViewModel(
    private val clock: Clock,
    routineRepository: RoutineRepository,
    private val activityRepository: ActivityRepository,
    private val habitRepository: HabitRepository,
    private val readPermissions: () -> PermissionStatus,
    scopeOverride: CoroutineScope? = null,
    private val tickMs: Long = TICK_MS,
) : ViewModel() {
    private val scope: CoroutineScope = scopeOverride ?: viewModelScope

    private val permissions = MutableStateFlow(readPermissions())

    private val ticker = flow {
        while (true) {
            emit(clock.now())
            if (tickMs <= 0) awaitCancellation()
            delay(tickMs)
        }
    }

    private val data = combine(
        routineRepository.routine,
        activityRepository.observeAll(),
        activityRepository.observeDoneKeys(),
    ) { routine, activities, done -> Triple(routine, activities, done) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<TodayUiState> = ticker
        .flatMapLatest { now ->
            val day = logicalDay(now)
            combine(
                data,
                habitRepository.observeHabits(),
                habitRepository.observeCountsFor(day),
                permissions,
            ) { (routine, activities, done), habits, counts, perms ->
                build(now, day, routine, activities, done, habits, counts, perms)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, initial())

    private fun initial(): TodayUiState {
        val day = logicalDay(clock.now())
        val type = dayTypeOf(day)
        val goal = if (type.isRest) null else Routine().workGoalMin
        return TodayUiState(
            dayLabel = formatDayEs(day), workMinutes = 0, workGoal = goal,
            workStatus = goal?.let { workStatus(0, it) }, habits = emptyList(), timeline = emptyList(),
            freeSlots = emptyList(), permissions = permissions.value, day = day, dayType = type,
        )
    }

    private fun build(
        now: LocalDateTime,
        day: LocalDate,
        routine: Routine,
        activities: List<Activity>,
        done: Set<Pair<Long, LocalDate>>,
        habits: List<app.brujula.habits.Habit>,
        counts: Map<Long, Int>,
        perms: PermissionStatus,
    ): TodayUiState {
        val window = routine.awakeWindow(day)
        // Dia logico: todo lo que empieza en el (04:00 a 03:59), ya sea por fecha de calendario de ayer, hoy o manana,
        // mas lo de ayer que aun cruza la vigilia de hoy.
        val logical = (-1L..1L).flatMap { occurrencesOn(day.plusDays(it), activities, done) }
            .filter { logicalDay(it.span.start) == day }
        val spill = occurrencesOn(day.minusDays(1), activities, done)
            .filter { logicalDay(it.span.start) != day && it.span.overlaps(window) }
        val timeline = (spill + logical).sortedBy { it.span.start }
        val minutes = workMinutes(logical)
        val type = dayTypeOf(day)
        val goal = if (type.isRest) null else routine.workGoalMin
        return TodayUiState(
            dayLabel = formatDayEs(day),
            workMinutes = minutes,
            workGoal = goal,
            workStatus = goal?.let { workStatus(minutes, it) },
            habits = habits
                .filter { it.active && it.dailyTarget > 0 && day.dayOfWeek in it.days }
                .take(MAX_VISIBLE_HABITS)
                .map { HabitChip(it.id, it.name, it.icon, counts[it.id] ?: 0, it.dailyTarget) },
            timeline = timeline.map { TimelineEntry(it, now in it.span) },
            freeSlots = freeSlots(window, timeline.map { it.span }),
            permissions = perms,
            day = day,
            dayType = type,
        )
    }

    fun addHabit(habitId: Long) {
        scope.launch { habitRepository.logDone(habitId, clock.now()) }

    }
    /** Alterna hecho para la ocurrencia de [day] (el dia de inicio de esa ocurrencia). */
    fun toggleDone(activityId: Long, day: LocalDate) {
        scope.launch {
            val done = activityRepository.observeDoneKeys().first().contains(activityId to day)
            activityRepository.setDone(activityId, day, !done)
        }
    }

    fun refreshPermissions() {
        permissions.value = readPermissions()
    }
}
