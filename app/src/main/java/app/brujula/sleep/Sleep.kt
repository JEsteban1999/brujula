package app.brujula.sleep

import app.brujula.core.time.TimeSpan
import app.brujula.routine.Routine
import app.brujula.routine.awakeWindow
import app.brujula.routine.logicalDay
import app.brujula.routine.wakeFor
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Lo que se sabe de una noche; [night] es su día lógico (la noche del jueves es jueves aunque te acuestes el viernes a la 00:30). */
data class SleepRecord(val night: LocalDate, val bedAt: LocalDateTime?, val wakeAt: LocalDateTime?)

/** Minutos después de la hora de levantarse en que abrir la app aún cuenta como despertar. */
const val WAKE_GRACE_MIN = 120
private const val BED_EARLY_MARGIN_H = 6L
private const val MAX_SLEEP_H = 16L
private val DAY_START: LocalTime = LocalTime.of(4, 0)

/** D2: lo planeado sale de la rutina; `bedAt` y `wakeAt` lo ajustan solo si son plausibles. */
fun sleepSpan(night: LocalDate, routine: Routine, record: SleepRecord?): TimeSpan {
    val bed0 = routine.awakeWindow(night).end
    val next = night.plusDays(1)
    val wake0 = next.atTime(routine.wakeFor(next))
    val bed = record?.bedAt?.takeIf { it >= bed0.minusHours(BED_EARLY_MARGIN_H) && it < wake0 } ?: bed0
    val wake = record?.wakeAt?.takeIf { it > bed && it <= bed.plusHours(MAX_SLEEP_H) } ?: wake0
    return if (wake <= bed) TimeSpan(bed0, wake0) else TimeSpan(bed, wake)
}

fun sleepMinutes(night: LocalDate, routine: Routine, record: SleepRecord?): Int = sleepSpan(night, routine, record).minutes.toInt()

/** La noche que termina con [at] como primer despertar: solo entre las 04:00 y 2 h después de la hora de levantarse. */
fun wakeNightFor(at: LocalDateTime, routine: Routine): LocalDate? {
    val d = logicalDay(at)
    val from = d.atTime(DAY_START)
    val to = d.atTime(routine.wakeFor(d)).plusMinutes(WAKE_GRACE_MIN.toLong())
    return if (at >= from && at <= to) d.minusDays(1) else null
}

/** `Ya me acuesto` solo cuenta dentro del día lógico del aviso (a las 00:40 sigue siendo la noche anterior). */
fun bedtimeNightFor(at: LocalDateTime, reminderDay: LocalDate): LocalDate? = logicalDay(at).takeIf { it == reminderDay }
