package app.brujula.planning

import app.brujula.core.time.Clock
import app.brujula.routine.BedtimeChoice
import app.brujula.routine.Routine
import app.brujula.routine.RoutineRepository
import app.brujula.routine.logicalDay
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Hora de dormir de la noche de [day] (D12), sin depender de "ahora": así la clave del aviso es la misma en cada
 * resincronización. Es la única función que usan los avisos, la pantalla de la noche y la confirmación del plan.
 */
fun bedtimeFor(day: LocalDate, routine: Routine, tomorrow: DaySnapshot): BedtimeSuggestion =
    suggestBedtime(day, routine, firstFixedStart(tomorrow), notBefore = null)

/** Fuente de la hora de dormir de esta noche (día lógico) y del interruptor "Aviso para dormir". */
class BedtimeSource(
    private val clock: Clock,
    private val routineRepository: RoutineRepository,
    private val snapshots: DaySnapshotProvider,
) {
    /** La sugerencia de esta noche con el interruptor actual; se calcula con lo que hay en la agenda ahora mismo. */
    suspend fun choice(): BedtimeChoice {
        val today = logicalDay(clock.now())
        val routine = routineRepository.routine.first()
        val s = bedtimeFor(today, routine, snapshots.snapshot(today.plusDays(1)))
        return BedtimeChoice(today, s.bedtime, s.sleepMinutes, routineRepository.bedtimeReminderOn.first())
    }

    /** Cuándo suena el aviso de esta noche: `null` si está apagado o ya pasó. */
    suspend fun remindAt(): LocalDateTime? = choice().takeIf { it.remind }?.remindAt?.takeIf { it > clock.now() }
}
