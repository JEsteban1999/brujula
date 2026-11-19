package app.brujula.core.theme

import app.brujula.core.time.Clock
import app.brujula.routine.Routine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

private const val THEME_TICK_MS = 30_000L

/**
 * Si el tema es oscuro, recalculado al cambiar la rutina y cada [tickMs] con [clock]
 * (así el modo Automático cambia solo a las 21:00 y a la hora de levantarse).
 * No emite nada hasta que la rutina guardada se haya cargado.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun darkFlow(routine: Flow<Routine>, clock: Clock, tickMs: Long = THEME_TICK_MS): Flow<Boolean> =
    routine.flatMapLatest { r ->
        flow {
            while (true) {
                emit(Unit)
                delay(tickMs)
            }
        }.map { resolveDark(r.themeMode, clock.now().toLocalTime(), r.wake) }
    }.distinctUntilChanged()
