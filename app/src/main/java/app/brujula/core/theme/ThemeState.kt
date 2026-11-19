package app.brujula.core.theme

import app.brujula.core.time.Clock
import app.brujula.routine.Routine
import app.brujula.routine.logicalDay
import app.brujula.routine.wakeFor
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val THEME_TICK_MS = 30_000L

private const val ROUTINE_FALLBACK_MS = 1_500L

/**
 * La rutina guardada, con red de seguridad: si el flujo falla se usa la rutina por defecto,
 * y si no emite nada en [timeoutMs] se sigue con los valores por defecto (y se actualiza
 * cuando llegue la real).
 */
internal fun routineOrDefault(routine: Flow<Routine>, timeoutMs: Long): Flow<Routine> = channelFlow {
    val fallback = launch {
        delay(timeoutMs)
        send(Routine())
    }
    routine
        .catch { e ->
            runCatching { Log.w("Brujula", "No se pudo leer la rutina; uso valores por defecto", e) }
            emit(Routine())
        }
        .collect {
            fallback.cancel()
            send(it)
        }
}

/**
 * Si el tema es oscuro, recalculado al cambiar la rutina y cada [tickMs] con [clock]
 * (así el modo Automático cambia solo a las 21:00 y a la hora de levantarse).
 * Emite al cargar la rutina (o la de por defecto si falla o tarda demasiado).
 * Es un flujo frío: al volver a suscribirse emite al instante con la hora actual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun darkFlow(
    routine: Flow<Routine>,
    clock: Clock,
    tickMs: Long = THEME_TICK_MS,
    fallbackAfterMs: Long = ROUTINE_FALLBACK_MS,
): Flow<Boolean> =
    routineOrDefault(routine, fallbackAfterMs).flatMapLatest { r ->
        flow {
            while (true) {
                emit(Unit)
                delay(tickMs)
            }
        }.map { resolveDark(r.themeMode, clock.now().toLocalTime(), r.wakeFor(logicalDay(clock.now()))) }
    }.distinctUntilChanged()
