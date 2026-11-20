package app.brujula.habits

import app.brujula.core.time.TimeSpan
import app.brujula.routine.Routine
import app.brujula.routine.awakeWindow
import app.brujula.routine.bedFor
import app.brujula.routine.wakeFor
import app.brujula.routine.dayTypeOf
import java.time.LocalDate
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/** Momentos de recordatorio del hábito en [day], solo dentro de la vigilia (puede cruzar medianoche). */
fun Habit.timesFor(day: LocalDate, routine: Routine): List<LocalDateTime> {
    if (!active || day.dayOfWeek !in days) return emptyList()
    val awake = routine.awakeWindow(day)
    if (window == HabitWindow.WORK) return workTimesFor(day, routine, awake)
    return when (val s = schedule) {
        is HabitSchedule.Every -> {
            if (s.intervalMin <= 0) return emptyList()
            val f = s.faster?.takeIf { it.intervalMin > 0 } ?: return every(awake.start, awake.end, s.intervalMin)
            val fromAt = clockTimeOn(day, f.from, routine)
            val base = every(awake.start, minOf(awake.end, fromAt), s.intervalMin)
            val fast = generateSequence(fromAt) { it.plusMinutes(f.intervalMin.toLong()) }.takeWhile { it < awake.end }.toList()
            // Un aviso base pegado al primero del tramo (a menos de medio intervalo rápido) sobra.
            val last = base.lastOrNull()
            val dropLast = fast.isNotEmpty() && last != null && Duration.between(last, fromAt).toMinutes() * 2 < f.intervalMin
            (if (dropLast) base.dropLast(1) else base) + fast
        }
        is HabitSchedule.AtTimes -> s.times.map { clockTimeOn(day, it, routine) }.filter { it in awake }.distinct().sorted()
        is HabitSchedule.BeforeBed -> listOf(awake.end.minusMinutes(s.minutes.toLong())).filter { it in awake }
    }
}

/** Desde [start] (sin incluirlo) cada [intervalMin] minutos, mientras sea antes de [end]. */
private fun every(start: LocalDateTime, end: LocalDateTime, intervalMin: Int): List<LocalDateTime> =
    generateSequence(start.plusMinutes(intervalMin.toLong())) { it.plusMinutes(intervalMin.toLong()) }.takeWhile { it < end }.toList()

/**
 * Una hora del reloj llevada a la vigilia de [day]: antes de acostarse (pasada la medianoche) cae en la madrugada siguiente;
 * antes de levantarse se corre a levantarse. Lo que quede fuera de la vigilia lo filtra quien llama.
 */
internal fun clockTimeOn(day: LocalDate, t: LocalTime, routine: Routine): LocalDateTime {
    val w = routine.wakeFor(day)
    val b = routine.bedFor(day)
    val crossesMidnight = b <= w
    return when {
        crossesMidnight && t < b -> day.plusDays(1).atTime(t)
        t < w -> day.atTime(w)
        else -> day.atTime(t)
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