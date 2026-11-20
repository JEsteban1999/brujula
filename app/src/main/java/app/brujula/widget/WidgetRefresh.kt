package app.brujula.widget

import android.util.Log
import app.brujula.activities.ActivityRepository
import app.brujula.calendar.WorkClassificationRepository
import app.brujula.habits.HabitRepository
import app.brujula.routine.RoutineRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

private const val TAG = "WidgetRefresh"
const val WIDGET_DEBOUNCE_MS = 1_000L
private const val WIDGET_RETRY_MS = 5_000L

/** Emite cuando cambia algo que el widget muestra: rutina, actividades, hechos, overrides, hábitos, registros, enlaces y clasificaciones. */
fun widgetChanges(
    routineRepository: RoutineRepository,
    activityRepository: ActivityRepository,
    habitRepository: HabitRepository,
    classifications: WorkClassificationRepository,
): Flow<Unit> = combine(
    listOf<Flow<Any?>>(
        routineRepository.routine,
        activityRepository.observeAll(),
        activityRepository.observeDoneKeys(),
        activityRepository.observeOverrides(),
        habitRepository.observeHabits(),
        habitRepository.observeLogCount(),
        activityRepository.observeGoogleEventIds(),
        classifications.observe(),
    ),
) { }

/**
 * Repinta el widget después de cada cambio de [changes] (con debounce) y solo si hay widgets puestos. Un error en el
 * flujo o en [update] se registra y el recolector sigue vivo.
 */
@OptIn(FlowPreview::class)
fun startWidgetRefresh(
    scope: CoroutineScope,
    changes: Flow<*>,
    hasWidgets: () -> Boolean,
    update: suspend () -> Unit,
    debounceMs: Long = WIDGET_DEBOUNCE_MS,
): Job = scope.launch {
    changes
        .retryWhen { cause, _ ->
            if (cause is CancellationException) return@retryWhen false
            Log.e(TAG, "Fallo observando cambios; se reintenta", cause)
            delay(WIDGET_RETRY_MS)
            true
        }
        .debounce(debounceMs)
        .collect {
            try {
                if (hasWidgets()) update()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Fallo al actualizar el widget", e)
            }
        }
}
