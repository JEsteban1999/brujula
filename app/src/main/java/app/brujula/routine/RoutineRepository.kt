package app.brujula.routine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.brujula.core.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime

/** Rutina en DataStore Preferences; las horas se guardan como "HH:mm". */
class RoutineRepository(private val dataStore: DataStore<Preferences>) {
    private object Keys {
        val wake = stringPreferencesKey("wake")
        val bed = stringPreferencesKey("bed")
        val restWake = stringPreferencesKey("rest_wake")
        val restBed = stringPreferencesKey("rest_bed")
        val workBlocks = stringPreferencesKey("work_blocks")
        val flexible = booleanPreferencesKey("flexible_work")
        val goalMin = intPreferencesKey("work_goal_min")
        val goalMax = intPreferencesKey("work_goal_max")
        val theme = stringPreferencesKey("theme_mode")
        val seeded = booleanPreferencesKey("defaults_seeded")
        val exerciseMigrated = booleanPreferencesKey("exercise_workdays_migrated")
    }

    val routine: Flow<Routine> = dataStore.data.map(::read)

    /** Marca de primer arranque: los datos por defecto ya se sembraron una vez. */
    suspend fun isSeeded(): Boolean = dataStore.data.first()[Keys.seeded] ?: false

    suspend fun isExerciseMigrated(): Boolean = dataStore.data.first()[Keys.exerciseMigrated] ?: false

    suspend fun markExerciseMigrated() {
        dataStore.edit { it[Keys.exerciseMigrated] = true }
    }

    suspend fun markSeeded() {
        dataStore.edit { it[Keys.seeded] = true }
    }

    suspend fun update(transform: (Routine) -> Routine) {
        dataStore.edit { p ->
            val r = transform(read(p))
            p[Keys.wake] = r.wake.toString()
            p[Keys.bed] = r.bed.toString()
            p[Keys.restWake] = r.restWake.toString()
            p[Keys.restBed] = r.restBed.toString()
            p[Keys.workBlocks] = r.workBlocks.joinToString(",") { "${it.first}-${it.second}" }
            p[Keys.flexible] = r.flexibleWork
            p[Keys.goalMin] = r.workGoalMin.first
            p[Keys.goalMax] = r.workGoalMin.last
            p[Keys.theme] = r.themeMode.name
        }
    }

    private fun read(p: Preferences): Routine {
        val d = Routine()
        return Routine(
            wake = p[Keys.wake]?.let(LocalTime::parse) ?: d.wake,
            bed = p[Keys.bed]?.let(LocalTime::parse) ?: d.bed,
            restWake = p[Keys.restWake]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: d.restWake,
            restBed = p[Keys.restBed]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: d.restBed,
            workBlocks = p[Keys.workBlocks]?.let(::decodeBlocks) ?: d.workBlocks,
            flexibleWork = p[Keys.flexible] ?: d.flexibleWork,
            workGoalMin = (p[Keys.goalMin] ?: d.workGoalMin.first)..(p[Keys.goalMax] ?: d.workGoalMin.last),
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
        )
    }

    private fun decodeBlocks(text: String): List<Pair<LocalTime, LocalTime>> =
        text.split(",").filter { it.isNotBlank() }.map {
            val (a, b) = it.split("-")
            LocalTime.parse(a) to LocalTime.parse(b)
        }
}
