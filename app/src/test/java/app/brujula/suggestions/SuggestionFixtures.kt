package app.brujula.suggestions

import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitSchedule
import app.brujula.routine.Routine
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Datos comunes de las pruebas de sugerencias: hoy es el jueves 19 de noviembre de 2026. */
object SuggestionFixtures {
    val today: LocalDate = LocalDate.of(2026, 11, 19)
    val now: LocalDateTime = today.atTime(10, 0)
    val water = Habit(3, "Agua", HabitIcon.WATER, HabitSchedule.Every(90), 8)
    val balm = Habit(4, "Bálsamo", HabitIcon.LIP_BALM, HabitSchedule.AtTimes(listOf(9, 13, 17, 21).map { LocalTime.of(it, 0) }), 4)

    fun done(id: Long, at: LocalDateTime) = HabitLog(id, at, app.brujula.routine.logicalDay(at), skipped = false)
    fun skip(id: Long, at: LocalDateTime) = HabitLog(id, at, app.brujula.routine.logicalDay(at), skipped = true)

    fun ctx(
        habits: List<Habit> = listOf(water, balm),
        logs: List<HabitLog> = emptyList(),
        daysWithLogs: Int = 9,
        rejected: Set<Pair<Long, SuggestionKind>> = emptySet(),
        routine: Routine = Routine(),
    ) = SuggestionContext(
        now = now, today = today, daysWithLogs = daysWithLogs, routine = routine,
        stats = habitStats(today, habits, logs, routine), rejected = rejected,
    )
}
