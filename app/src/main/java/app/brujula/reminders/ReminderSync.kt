package app.brujula.reminders

import app.brujula.activities.ActivityRepository
import app.brujula.core.time.Clock
import app.brujula.habits.HabitRepository
import app.brujula.routine.BedtimeChoice
import app.brujula.routine.RoutineRepository
import app.brujula.routine.logicalDay
import kotlinx.coroutines.flow.first
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
    /** Hora de dormir elegida (producción: `routineRepository.bedtimeChoice.first()`). */
    private val bedtime: suspend () -> BedtimeChoice? = { null },
) : ReminderResync {
    private val mutex = Mutex()

    /** Serializada: lectura, plan y replaceAll van juntos, asi dos resincronizaciones no se pisan. */
    override suspend fun resync() = mutex.withLock {
        val now = clock.now()
        val today = logicalDay(now)
        val counts = listOf(today, today.plusDays(1)).associateWith { habitRepo.observeCountsFor(it).first() }
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
            plannedDays = plannedDays(),
            bedtime = bedtime(),
        )
        scheduler.replaceAll(reminders)
    }
}
