package app.brujula.suggestions

import app.brujula.core.time.Clock
import app.brujula.habits.Habit
import app.brujula.habits.HabitRepository
import app.brujula.habits.timesFor
import app.brujula.routine.Routine
import app.brujula.routine.RoutineRepository
import app.brujula.routine.logicalDay
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime

/** Un registro de hábito tal como lo leen las sugerencias (sin la clave del aviso). */
data class HabitLog(val habitId: Long, val at: LocalDateTime, val logicalDay: LocalDate, val skipped: Boolean)

/**
 * Conteos agregados de un hábito activo en los últimos días (sin hoy): es todo lo que ve Claude de sus registros.
 * [daily]: hechos por día lógico, del más viejo al más nuevo. Por hora del reloj: hechos, saltados y avisos (de [timesFor]).
 */
data class HabitStat(
    val ref: String,
    val habit: Habit,
    val daily: List<Int>,
    val doneByHour: Map<Int, Int>,
    val skippedByHour: Map<Int, Int>,
    val remindersByHour: Map<Int, Int>,
)

const val SUGGESTION_DAYS = 14

/** Conteos de los hábitos activos en los días lógicos `today − days .. today − 1`. */
fun habitStats(today: LocalDate, habits: List<Habit>, logs: List<HabitLog>, routine: Routine, days: Int = SUGGESTION_DAYS): List<HabitStat> {
    val range = (days downTo 1).map { today.minusDays(it.toLong()) }
    val first = range.first()
    val last = range.last()
    val byHabit = logs.filter { it.logicalDay in first..last }.groupBy { it.habitId }
    return habits.filter { it.active }.map { h ->
        val mine = byHabit[h.id].orEmpty()
        val (skipped, done) = mine.partition { it.skipped }
        val perDay = done.groupingBy { it.logicalDay }.eachCount()
        HabitStat(
            ref = "H${h.id}",
            habit = h,
            daily = range.map { perDay[it] ?: 0 },
            doneByHour = done.groupingBy { it.at.hour }.eachCount().toSortedMap(),
            skippedByHour = skipped.groupingBy { it.at.hour }.eachCount().toSortedMap(),
            remindersByHour = range.flatMap { h.timesFor(it, routine) }.groupingBy { it.hour }.eachCount().toSortedMap(),
        )
    }
}

/** Lo que necesita una sugerencia: solo conteos agregados, la rutina y los cambios rechazados (D15). */
data class SuggestionContext(
    val now: LocalDateTime,
    val today: LocalDate,
    /** Días lógicos de los últimos 14 (sin hoy) con algún registro (hecho o saltado) de cualquier hábito. */
    val daysWithLogs: Int,
    val routine: Routine,
    val stats: List<HabitStat>,
    val rejected: Set<Pair<Long, SuggestionKind>>,
)

/** Arma el [SuggestionContext] con los datos locales. [rejected] lo conecta la Task 5 (descartadas en los últimos 30 días). */
class SuggestionContextProvider(
    private val clock: Clock,
    private val habitRepository: HabitRepository,
    private val routineRepository: RoutineRepository,
    private val rejected: suspend () -> Set<Pair<Long, SuggestionKind>> = { emptySet() },
) {
    suspend fun current(): SuggestionContext {
        val now = clock.now()
        val today = logicalDay(now)
        val routine = routineRepository.routine.first()
        val habits = habitRepository.observeHabits().first()
        // Solo cuentan los registros de hábitos activos: son los únicos que ve Claude.
        val activeIds = habits.filter { it.active }.map { it.id }.toSet()
        val logs = habitRepository.logsBetween(today.minusDays(SUGGESTION_DAYS.toLong()), today.minusDays(1))
            .filter { it.habitId in activeIds }
        return SuggestionContext(
            now = now,
            today = today,
            daysWithLogs = logs.map { it.logicalDay }.distinct().size,
            routine = routine,
            stats = habitStats(today, habits, logs, routine),
            rejected = rejected(),
        )
    }
}
