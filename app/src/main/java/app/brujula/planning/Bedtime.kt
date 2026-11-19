package app.brujula.planning

import app.brujula.activities.ActivityType
import app.brujula.routine.Routine
import app.brujula.routine.logicalDay
import app.brujula.routine.awakeWindow
import app.brujula.routine.wakeFor
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

data class BedtimeSuggestion(val bedtime: LocalDateTime, val sleepMinutes: Int, val remindAt: LocalDateTime)

const val SLEEP_TARGET_MIN = 8 * 60
const val BEDTIME_REMIND_BEFORE_MIN = 30
/** Margen entre levantarse y lo primero fijo del día siguiente. */
private const val WAKE_MARGIN_MIN = 30L
private val EARLIEST_WAKE: LocalTime = LocalTime.of(5, 0)
private const val STEP_MIN = 15

/**
 * Hora sugerida para dormir la noche de [day] (D12).
 *
 * Se toma la hora de levantarse del día siguiente; si [nextDayFirstStart] (lo primero *fijo*, ver [firstFixedStart]) empieza antes de esa hora + 30 min, se usa ese
 * inicio − 30 min, nunca antes de las 05:00. La hora de dormir es la más temprana entre la de acostarse de la rutina y esa
 * hora de levantarse − 8 h, redondeada hacia abajo a 15 min; [notBefore] (p. ej. ahora) la corre a ese instante redondeado
 * hacia arriba a 15 min. El aviso es 30 min antes. Todo en fecha y hora completas: cruzar la medianoche no cambia nada.
 */
fun suggestBedtime(
    day: LocalDate,
    routine: Routine,
    nextDayFirstStart: LocalDateTime?,
    notBefore: LocalDateTime? = null,
): BedtimeSuggestion {
    val next = day.plusDays(1)
    val usualWake = next.atTime(routine.wakeFor(next))
    val wake = if (nextDayFirstStart != null && nextDayFirstStart < usualWake.plusMinutes(WAKE_MARGIN_MIN)) {
        minOf(usualWake, maxOf(nextDayFirstStart.minusMinutes(WAKE_MARGIN_MIN), next.atTime(EARLIEST_WAKE)))
    } else {
        usualWake
    }
    val usualBed = routine.awakeWindow(day).end
    var bedtime = floorToStep(minOf(usualBed, wake.minusMinutes(SLEEP_TARGET_MIN.toLong())))
    if (notBefore != null && bedtime < notBefore) bedtime = ceilToStep(notBefore)
    val sleep = Duration.between(bedtime, wake).toMinutes().toInt().coerceAtLeast(0)
    return BedtimeSuggestion(bedtime, sleep, bedtime.minusMinutes(BEDTIME_REMIND_BEFORE_MIN.toLong()))
}

/**
 * Inicio de lo primero fijo del día lógico de [snapshot] (D12): eventos externos con hora (la foto ya quita los de todo el
 * día y las copias de Brújula) y citas propias. Rutina, trabajo, ejercicio y personales no mueven la hora de levantarse.
 */
fun firstFixedStart(snapshot: DaySnapshot): LocalDateTime? {
    val appointments = snapshot.own.filter { it.activity.type == ActivityType.APPOINTMENT }.map { it.span.start }
    val external = snapshot.external.map { it.event.span.start }
    return (appointments + external).filter { logicalDay(it) == snapshot.day }.minOrNull()
}

private fun floorToStep(t: LocalDateTime): LocalDateTime {
    val m = t.truncatedTo(ChronoUnit.MINUTES)
    return m.minusMinutes((m.minute % STEP_MIN).toLong())
}

private fun ceilToStep(t: LocalDateTime): LocalDateTime {
    val floor = floorToStep(t)
    return if (floor < t) floor.plusMinutes(STEP_MIN.toLong()) else floor
}
