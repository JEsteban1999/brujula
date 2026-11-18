package app.brujula

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import app.brujula.activities.ActivityRepository
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
    }
}

val Context.container: AppContainer
    get() = (applicationContext as BrujulaApp).container
