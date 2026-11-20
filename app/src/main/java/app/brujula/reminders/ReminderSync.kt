package app.brujula.reminders

import app.brujula.activities.ActivityRepository
import app.brujula.core.time.Clock
import app.brujula.habits.HabitRepository
import app.brujula.routine.BedtimeChoice
import app.brujula.routine.RoutineRepository
import app.brujula.routine.logicalDay
import app.brujula.sleep.SleepRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

fun interface ReminderResync {
    suspend fun resync()
}

/** Recalcula los avisos de las proximas 24 h y reemplaza los programados. */
class ReminderSync(
    private val clock: Clock,
    private val routineRepo: RoutineRepository,
    private val activityRepo: ActivityRepository,
    private val habitRepo: HabitRepository,
    private val scheduler: ReminderScheduler,
    /** Días con un plan ACCEPTED (producción: `proposalRepository.acceptedDays()`). */
    private val plannedDays: suspend () -> Set<LocalDate> = { emptySet() },
    /** Hora de dormir de esta noche y su interruptor, calculada en cada resincronización (producción: `BedtimeSource.choice`). */
    private val bedtime: suspend () -> BedtimeChoice? = { null },
    /** Quita un aviso ya publicado por su clave (producción: `Notifications.cancel`). */
    private val cancelPosted: (String) -> Unit = {},
    /** Registros de sueño de las noches entre las fechas dadas (producción: `sleepRepository.observeRange`). */
    private val sleepRecords: suspend (LocalDate, LocalDate) -> Map<LocalDate, SleepRecord> = { _, _ -> emptyMap() },
    /** Tope para leer la hora de dormir (lee los calendarios del sistema); sin respuesta, esta pasada va sin ella. */
    private val bedtimeTimeoutMs: Long = BEDTIME_TIMEOUT_MS,
) : ReminderResync {
    private val mutex = Mutex()

    /**
     * La hora de dormir con tope de tiempo. Corre aparte (no en el ámbito de quien llama) para que una lectura que bloquea
     * el hilo tampoco retenga el Mutex ni el `goAsync` de los receptores: al vencer, se deja de esperar y se cancela.
     */
    private suspend fun bedtimeWithin(): BedtimeChoice? {
        val read = bedtimeScope.async { bedtime() }
        return withTimeoutOrNull(bedtimeTimeoutMs) { read.await() } ?: run {
            read.cancel()
            null
        }
    }

    /** Serializada: lectura, plan y replaceAll van juntos, asi dos resincronizaciones no se pisan. */
    override suspend fun resync() = mutex.withLock {
        val now = clock.now()
        val today = logicalDay(now)
        val counts = listOf(today, today.plusDays(1)).associateWith { habitRepo.observeCountsFor(it).first() }
        val planned = plannedDays()
        val reminders = ReminderPlanner.plan(
            now = now,
            routine = routineRepo.routine.first(),
            habits = habitRepo.observeHabits().first(),
            habitCounts = counts,
            handledKeys = habitRepo.observeHandledKeys().first(),
            activities = activityRepo.observeAll().first(),
            doneKeys = activityRepo.observeDoneKeys().first(),
            exercisePromptAnswered = routineRepo.exercisePromptAnswered.first(),
            overrides = activityRepo.observeOverrides().first(),
            plannedDays = planned,
            bedtime = bedtimeWithin(),
            sleep = sleepRecords(today.minusDays(1), today),
        )
        // Si mañana ya tiene plan aceptado, el aviso "¿Armamos mañana?" que siga publicado ya no tiene sentido (M5). Solo
        // los días recientes: un aviso de hace semanas ya no puede estar publicado.
        planned.filter { it >= today.minusDays(1) }.forEach { cancelPosted(ReminderPlanner.nightKey(it.minusDays(1))) }
        scheduler.replaceAll(reminders)
    }
}

private const val BEDTIME_TIMEOUT_MS = 3_000L

/** Lecturas de la hora de dormir que quedaron colgadas no frenan a nadie; los fallos ya se atrapan dentro de `bedtime`. */
private val bedtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
