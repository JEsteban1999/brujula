package app.brujula

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import app.brujula.activities.ActivityRepository
import app.brujula.calendar.CalendarSource
import app.brujula.calendar.WorkClassificationRepository
import app.brujula.calendar.DeviceCalendarSource
import app.brujula.core.time.Clock
import app.brujula.core.time.SystemClock
import app.brujula.reminders.AlarmReminderScheduler
import app.brujula.reminders.ReminderResync
import app.brujula.reminders.ReminderScheduler
import app.brujula.reminders.ReminderSync
import android.util.Log
import app.brujula.reminders.reminderChanges
import app.brujula.reminders.startReminderResync
import kotlinx.coroutines.CoroutineExceptionHandler
import app.brujula.data.db.BrujulaDatabase
import app.brujula.data.db.Migrations
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
    val workClassificationRepository: WorkClassificationRepository by lazy {
        WorkClassificationRepository(database.workClassificationDao())
    }
    val routineRepository: RoutineRepository by lazy { RoutineRepository(context.applicationContext.routineDataStore) }

    /** Calendarios del telefono (solo lectura); las pruebas lo sustituyen por uno falso. */
    var calendarSource: CalendarSource = DeviceCalendarSource(context.applicationContext)

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
    /** Un fallo no atrapado en segundo plano se registra en vez de tumbar el proceso. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e("BrujulaApp", "Fallo en segundo plano", e) },
    )

    override fun onCreate() {
        super.onCreate()
        startBackgroundWork()
    }

    /** Trabajo de arranque en segundo plano; las pruebas lo anulan. */
    protected open fun startBackgroundWork() {
        appScope.launch {
            runCatching { Seed.ensureDefaults(container.database, container.routineRepository, LocalDate.now()) }
                .onFailure { Log.e("BrujulaApp", "Fallo al sembrar valores por defecto", it) }
            runCatching { Migrations.seedWellness(container.database, container.routineRepository) }
                .onFailure { Log.e("BrujulaApp", "Fallo al sembrar bienestar", it) }
            runCatching { Migrations.exerciseToWorkdays(container.database, container.routineRepository) }
                .onFailure { Log.e("BrujulaApp", "Fallo al migrar el ejercicio", it) }
        }
        launchReminderResync()
    }

    /** Reprograma al abrir la app y cada vez que cambian rutina, actividades, habitos o registros. */
    private fun launchReminderResync() {
        val c = container
        startReminderResync(
            appScope,
            reminderChanges(c.routineRepository, c.activityRepository, c.habitRepository, c.clock),
            ReminderResync { c.reminderSync.resync() },
        )
    }
}

val Context.container: AppContainer
    get() = (applicationContext as BrujulaApp).container
