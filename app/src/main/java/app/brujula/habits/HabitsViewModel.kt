package app.brujula.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.core.time.Clock
import app.brujula.reminders.ReminderResync
import app.brujula.routine.Routine
import app.brujula.routine.RoutineRepository
import app.brujula.routine.dayTypeOf
import app.brujula.routine.logicalDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

data class HabitCard(
    val habit: Habit,
    val count: Int,
    val nextAt: LocalDateTime?,
    /** Solo para `AtTimes`: hora y si ya se hizo esa vez. */
    val slots: List<Pair<LocalTime, Boolean>>,
)

data class HabitsUiState(
    val cards: List<HabitCard>,
    val exerciseStreak: Int,
    /** Lunes a domingo de la semana del día lógico actual. */
    val weekDone: List<Boolean>,
    /** Lunes a domingo: true si ese día es de descanso. */
    val weekRest: List<Boolean>,
)

private const val TICK_MS = 30_000L

class HabitsViewModel(
    private val clock: Clock,
    routineRepository: RoutineRepository,
    private val activityRepository: ActivityRepository,
    private val habitRepository: HabitRepository,
    private val resync: ReminderResync = ReminderResync { },
    scopeOverride: CoroutineScope? = null,
    private val tickMs: Long = TICK_MS,
) : ViewModel() {
    private val scope: CoroutineScope = scopeOverride ?: viewModelScope

    private val ticker = flow {
        while (true) {
            emit(clock.now())
            if (tickMs <= 0) awaitCancellation()
            delay(tickMs)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<HabitsUiState> = ticker
        .flatMapLatest { now ->
            val day = logicalDay(now)
            combine(
                routineRepository.routine,
                habitRepository.observeHabits(),
                habitRepository.observeCountsFor(day),
                activityRepository.observeAll(),
                activityRepository.observeDoneKeys(),
            ) { routine, habits, counts, activities, done ->
                build(now, day, routine, habits, counts, activities, done)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, HabitsUiState(emptyList(), 0, List(7) { false }, List(7) { false }))

    private fun build(
        now: LocalDateTime,
        day: LocalDate,
        routine: Routine,
        habits: List<Habit>,
        counts: Map<Long, Int>,
        activities: List<Activity>,
        done: Set<Pair<Long, LocalDate>>,
    ): HabitsUiState {
        val cards = habits.filter { it.active }.map { h ->
            val count = counts[h.id] ?: 0
            val times = h.timesFor(day, routine)
            HabitCard(
                habit = h,
                count = count,
                nextAt = times.firstOrNull { it > now },
                slots = if (h.schedule is HabitSchedule.AtTimes) {
                    times.mapIndexed { i, t -> t.toLocalTime() to (i < count) }
                } else {
                    emptyList()
                },
            )
        }
        val exerciseIds = activities.filter { it.type == ActivityType.EXERCISE }.map { it.id }.toSet()
        val doneDays = done.filter { it.first in exerciseIds }.map { it.second }.toSet()
        val monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return HabitsUiState(
            cards = cards,
            exerciseStreak = exerciseStreak(day, doneDays),
            weekDone = (0L..6L).map { monday.plusDays(it) in doneDays },
            weekRest = (0L..6L).map { dayTypeOf(monday.plusDays(it)).isRest },
        )
    }

    fun add(habitId: Long) {
        scope.launch { habitRepository.logDone(habitId, clock.now()) }
    }

    fun undo(habitId: Long) {
        scope.launch { habitRepository.undoLast(habitId, logicalDay(clock.now())) }
    }

    fun delete(habitId: Long) {
        scope.launch {
            habitRepository.delete(habitId)
            runCatching { resync.resync() }
        }
    }
}
