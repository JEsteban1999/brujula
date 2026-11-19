package app.brujula.habits

import app.brujula.routine.Routine
import app.brujula.routine.awakeWindow
import app.brujula.routine.bedFor
import app.brujula.routine.wakeFor
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
        is HabitSchedule.AtTimes -> {
            val w = routine.wakeFor(day)
            val b = routine.bedFor(day)
            val crossesMidnight = b <= w
            // Antes de acostarse (pasada la medianoche) cae en la madrugada siguiente; antes de levantarse se corre a levantarse.
            s.times.map {
                when {
                    crossesMidnight && it < b -> day.plusDays(1).atTime(it)
                    it < w -> day.atTime(w)
                    else -> day.atTime(it)
                }
            }.filter { it in window }.distinct().sorted()
        }
    }
}