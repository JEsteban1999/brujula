package app.brujula.routine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.brujula.calendar.CalendarPrefs
import app.brujula.core.theme.ThemeMode
import app.brujula.planning.BEDTIME_REMIND_BEFORE_MIN
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Hora de dormir elegida para la noche del día lógico [day] (D12). [bedtime] puede caer después de las 00:00 (otra fecha de
 * calendario, mismo día lógico). [remind]: avisar 30 min antes ([remindAt]); viene encendido por defecto.
 */
data class BedtimeChoice(val day: LocalDate, val bedtime: LocalDateTime, val sleepMinutes: Int, val remind: Boolean) {
    val remindAt: LocalDateTime get() = bedtime.minusMinutes(BEDTIME_REMIND_BEFORE_MIN.toLong())
}

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
        val exercisePrompts = stringPreferencesKey("exercise_prompt_answered")
        val wellnessSeeded = booleanPreferencesKey("wellness_seeded")
        val exerciseMigrated = booleanPreferencesKey("exercise_workdays_migrated")
        val nightNotice = stringPreferencesKey("night_notice")
        val bedDay = stringPreferencesKey("bedtime_day")
        val bedAt = stringPreferencesKey("bedtime_at")
        val bedSleep = intPreferencesKey("bedtime_sleep_min")
        val bedRemind = booleanPreferencesKey("bedtime_remind")
    }

    val routine: Flow<Routine> = dataStore.data.map(::read)

    /** Última hora de dormir elegida (o `null` si nunca se abrió "¿Armamos mañana?" o está ilegible). */
    val bedtimeChoice: Flow<BedtimeChoice?> = dataStore.data.map(::readBedtime)

    suspend fun setBedtimeChoice(c: BedtimeChoice) {
        dataStore.edit { p ->
            p[Keys.bedDay] = c.day.toString()
            p[Keys.bedAt] = c.bedtime.toString()
            p[Keys.bedSleep] = c.sleepMinutes
            p[Keys.bedRemind] = c.remind
        }
    }

    private fun readBedtime(p: Preferences): BedtimeChoice? = runCatching {
        BedtimeChoice(
            day = LocalDate.parse(p[Keys.bedDay] ?: return null),
            bedtime = LocalDateTime.parse(p[Keys.bedAt] ?: return null),
            sleepMinutes = p[Keys.bedSleep] ?: return null,
            remind = p[Keys.bedRemind] ?: true,
        )
    }.getOrNull()

    /** Calendarios del telefono elegidos, en el mismo DataStore. */
    val calendarPrefs = CalendarPrefs(dataStore)

    /** Marca de primer arranque: los datos por defecto ya se sembraron una vez. */
    suspend fun isSeeded(): Boolean = dataStore.data.first()[Keys.seeded] ?: false

    suspend fun isWellnessSeeded(): Boolean = dataStore.data.first()[Keys.wellnessSeeded] ?: false

    suspend fun markWellnessSeeded() {
        dataStore.edit { it[Keys.wellnessSeeded] = true }
    }

    suspend fun isExerciseMigrated(): Boolean = dataStore.data.first()[Keys.exerciseMigrated] ?: false

    suspend fun markExerciseMigrated() {
        dataStore.edit { it[Keys.exerciseMigrated] = true }
    }

    /** Días (lógicos) en que ya se respondió el aviso de ejercicio; solo se guardan los últimos 14. */
    val exercisePromptAnswered: Flow<Set<LocalDate>> = dataStore.data.map { decodeDays(it[Keys.exercisePrompts]) }

    suspend fun markExercisePromptAnswered(day: LocalDate) {
        dataStore.edit { p ->
            val days = (decodeDays(p[Keys.exercisePrompts]) + day).sortedDescending().take(14)
            p[Keys.exercisePrompts] = days.joinToString(",")
        }
    }

    private fun decodeDays(text: String?): Set<LocalDate> =
        text.orEmpty().split(",").mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()

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
            p[Keys.nightNotice] = r.nightNotice.toString()
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
            nightNotice = p[Keys.nightNotice]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: d.nightNotice,
        )
    }

    private fun decodeBlocks(text: String): List<Pair<LocalTime, LocalTime>> =
        text.split(",").filter { it.isNotBlank() }.map {
            val (a, b) = it.split("-")
            LocalTime.parse(a) to LocalTime.parse(b)
        }
}
