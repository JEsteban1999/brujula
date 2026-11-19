package app.brujula.habits

import app.brujula.core.time.TimeSpan
import app.brujula.routine.Routine
import app.brujula.routine.awakeWindow
import app.brujula.routine.bedFor
import app.brujula.routine.wakeFor
import app.brujula.routine.dayTypeOf
import java.time.LocalDate
import java.time.LocalDateTime

/** Momentos de recordatorio del hábito en [day], solo dentro de la vigilia (puede cruzar medianoche). */
fun Habit.timesFor(day: LocalDate, routine: Routine): List<LocalDateTime> {
    if (!active || day.dayOfWeek !in days) return emptyList()
    val awake = routine.awakeWindow(day)
    if (window == HabitWindow.WORK) return workTimesFor(day, routine, awake)
    return when (val s = schedule) {
        is HabitSchedule.Every -> {
            if (s.intervalMin <= 0) return emptyList()
            generateSequence(awake.start.plusMinutes(s.intervalMin.toLong())) {
                it.plusMinutes(s.intervalMin.toLong())
            }.takeWhile { it < awake.end }.toList()
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
            }.filter { it in awake }.distinct().sorted()
        }
        is HabitSchedule.BeforeBed -> listOf(awake.end.minusMinutes(s.minutes.toLong())).filter { it in awake }
    }
}

/** Solo días laborales y dentro de los bloques de trabajo (cada bloque por separado). */
private fun Habit.workTimesFor(day: LocalDate, routine: Routine, awake: TimeSpan): List<LocalDateTime> {
    if (dayTypeOf(day).isRest) return emptyList()
    val blocks = routine.workBlocks
    return when (val s = schedule) {
        is HabitSchedule.Every -> {
            if (s.intervalMin <= 0) return emptyList()
            blocks.flatMap { (from, to) ->
                val end = day.atTime(to)
                generateSequence(day.atTime(from).plusMinutes(s.intervalMin.toLong())) {
                    it.plusMinutes(s.intervalMin.toLong())
                }.takeWhile { it < end }.toList()
            }.filter { it in awake }.distinct().sorted()
        }
        is HabitSchedule.AtTimes -> s.times.filter { t -> blocks.any { (from, to) -> t >= from && t < to } }
            .map { day.atTime(it) }.filter { it in awake }.distinct().sorted()
        is HabitSchedule.BeforeBed -> emptyList()
    }
}