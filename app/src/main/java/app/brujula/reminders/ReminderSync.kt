package app.brujula.reminders

import app.brujula.activities.ActivityRepository
import app.brujula.core.time.Clock
import app.brujula.habits.HabitRepository
import app.brujula.routine.RoutineRepository
import app.brujula.routine.logicalDay
import kotlinx.coroutines.flow.first

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
) : ReminderResync {
    override suspend fun resync() {
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
        )
        scheduler.replaceAll(reminders)
    }
}
