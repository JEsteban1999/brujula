package app.brujula.reminders

import android.util.Log
import app.brujula.activities.ActivityRepository
import app.brujula.core.time.Clock
import app.brujula.habits.HabitRepository
import app.brujula.routine.RoutineRepository
import app.brujula.routine.logicalDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val TAG = "ResyncTrigger"
const val RESYNC_DEBOUNCE_MS = 500L
private const val RESYNC_RETRY_MS = 5_000L

/** Emite cada vez que cambian rutina, actividades, hechos, habitos, avisos atendidos, conteos del dia logico, planes aceptados o el aviso para dormir. */
fun reminderChanges(
    routineRepository: RoutineRepository,
    activityRepository: ActivityRepository,
    habitRepository: HabitRepository,
    clock: Clock,
    /** Días con plan aceptado (resumen de la mañana); producción: `proposalRepository.observeAcceptedDays()`. */
    acceptedDays: Flow<Set<LocalDate>> = flowOf(emptySet()),
): Flow<Unit> = combine(
    routineRepository.routine,
    activityRepository.observeAll(),
    activityRepository.observeDoneKeys(),
    habitRepository.observeHabits(),
    habitRepository.observeHandledKeys(),
    habitRepository.observeCountsFor(logicalDay(clock.now())),
    activityRepository.observeOverrides(),
    acceptedDays,
    routineRepository.bedtimeReminderOn,
) { _ -> }

/**
 * Reprograma al abrir la app y despues de cada cambio de [changes] (con debounce). Un error en el flujo
 * de cambios o en la resincronizacion se registra y el recolector sigue vivo.
 */
@OptIn(FlowPreview::class)
fun startReminderResync(
    scope: CoroutineScope,
    changes: Flow<*>,
    resync: ReminderResync,
    debounceMs: Long = RESYNC_DEBOUNCE_MS,
    retryDelayMs: Long = RESYNC_RETRY_MS,
): Job = scope.launch {
    changes
        .retryWhen { cause, _ ->
            if (cause is CancellationException) return@retryWhen false
            Log.e(TAG, "Fallo observando cambios; se reintenta", cause)
            delay(retryDelayMs)
            true
        }
        .debounce(debounceMs)
        .collect {
            try {
                resync.resync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Fallo al reprogramar avisos", e)
            }
        }
}
