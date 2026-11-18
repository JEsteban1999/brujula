package app.brujula

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import app.brujula.activities.ActivityRepository
import app.brujula.core.time.Clock
import app.brujula.core.time.SystemClock
import app.brujula.reminders.AlarmReminderScheduler
import app.brujula.reminders.ReminderResync
import app.brujula.reminders.ReminderScheduler
import app.brujula.reminders.ReminderSync
import app.brujula.routine.logicalDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import app.brujula.data.db.BrujulaDatabase
import app.brujula.data.db.Seed
import app.brujula.habits.HabitRepository
import app.brujula.routine.RoutineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

private val Context.routineDataStore by preferencesDataStore(name = "routine")

/** Contenedor de dependencias; se ira llenando en tareas siguientes. */
class AppContainer(val context: Context) {
    val database: BrujulaDatabase by lazy { BrujulaDatabase.create(context) }
    val activityRepository: ActivityRepository by lazy { ActivityRepository(database.activityDao()) }
    val habitRepository: HabitRepository by lazy { HabitRepository(database.habitDao()) }
    val routineRepository: RoutineRepository by lazy { RoutineRepository(context.applicationContext.routineDataStore) }

    /** Reloj de los receptores; las pruebas lo sustituyen. */
    var clock: Clock = SystemClock

    private var schedulerOverride: ReminderScheduler? = null
    private var syncOverride: ReminderResync? = null
    private val defaultScheduler: ReminderScheduler by lazy { AlarmReminderScheduler(context.applicationContext) }
    private val defaultSync: ReminderResync by lazy {
        ReminderSync(clock, routineRepository, activityRepository, habitRepository, reminderScheduler)
    }

    /** Sustituibles en pruebas; por defecto, alarmas reales. */
    var reminderScheduler: ReminderScheduler
        get() = schedulerOverride ?: defaultScheduler
        set(value) { schedulerOverride = value }
    var reminderSync: ReminderResync
        get() = syncOverride ?: defaultSync
        set(value) { syncOverride = value }
}

open class BrujulaApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        startBackgroundWork()
    }

    /** Trabajo de arranque en segundo plano; las pruebas lo anulan. */
    protected open fun startBackgroundWork() {
        appScope.launch {
            Seed.ensureDefaults(container.database, container.routineRepository, LocalDate.now())
        }
        startReminderResync()
    }

    /** Reprograma al abrir la app y cada vez que cambian rutina, actividades, habitos o registros. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun startReminderResync() {
        val c = container
        appScope.launch {
            combine(
                c.routineRepository.routine,
                c.activityRepository.observeAll(),
                c.activityRepository.observeDoneKeys(),
                c.habitRepository.observeHabits(),
                c.habitRepository.observeHandledKeys(),
                c.habitRepository.observeCountsFor(logicalDay(java.time.LocalDateTime.now())),
            ) { _ -> }
                .debounce(500)
                .collect { runCatching { c.reminderSync.resync() } }
        }
    }
}

val Context.container: AppContainer
    get() = (applicationContext as BrujulaApp).container
