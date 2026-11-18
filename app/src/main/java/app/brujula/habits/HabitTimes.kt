package app.brujula.habits

import app.brujula.routine.Routine
import app.brujula.routine.awakeWindow
import java.time.LocalDate
import java.time.LocalDateTime

/** Momentos de recordatorio del hábito en [day], solo dentro de la vigilia (puede cruzar medianoche). */
fun Habit.timesFor(day: LocalDate, routine: Routine): List<LocalDateTime> {
    if (!active || day.dayOfWeek !in days) return emptyList()
    val window = routine.awakeWindow(day)
    return when (val s = schedule) {
        is HabitSchedule.Every -> {
            if (s.intervalMin <= 0) return emptyList()
            generateSequence(window.start.plusMinutes(s.intervalMin.toLong())) {
                it.plusMinutes(s.intervalMin.toLong())
            }.takeWhile { it < window.end }.toList()
        }
        is HabitSchedule.AtTimes ->
            s.times.map { day.atTime(it) }.filter { it in window }.sorted()
    }
}