package app.brujula.suggestions

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.brujula.claude.FakeClaudeGateway
import app.brujula.claude.GatewayResult
import app.brujula.claude.SuggestionItem
import app.brujula.claude.SuggestionResult
import app.brujula.core.time.FixedClock
import app.brujula.data.db.BrujulaDatabase
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitRepository
import app.brujula.habits.HabitSchedule
import app.brujula.routine.RoutineRepository
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** Servicio de sugerencias armado con el gateway falso, Room en memoria y DataStore en archivo (cada prueba en su carpeta). */
class SuggestionHarness(scope: CoroutineScope, dir: File, db: BrujulaDatabase, tag: String = "s") {
    val clock = FixedClock(SuggestionFixtures.now)
    val gateway = FakeClaudeGateway()
    val habits = HabitRepository(db.habitDao())
    val routine = RoutineRepository(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "$tag-routine.preferences_pb") })
    val store = SuggestionStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "$tag-suggestions.preferences_pb") })
    var key = true
    var online = true
    var resyncs = 0
    val provider = SuggestionContextProvider(clock, habits, routine, rejected = { store.rejected(clock.now()) })
    val suggester = HabitSuggester(gateway, contextProvider = { provider.current() }, isOnline = { online })
    val service = SuggestionService(
        store, suggester, habits, resync = { resyncs++ }, clock = clock, syncScope = scope,
        hasKey = { key }, isOnline = { online },
    )

    suspend fun water(): Long =
        habits.upsert(Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8))

    /** Un hecho al mediodía en cada uno de los [days] días anteriores a hoy. */
    suspend fun seedLogs(habitId: Long, days: Int = 7, today: java.time.LocalDate = SuggestionFixtures.today) {
        repeat(days) { habits.logDone(habitId, today.minusDays(it + 1L).atTime(12, 0)) }
    }

    fun answer(vararg items: SuggestionItem) {
        gateway.suggestion = GatewayResult.Ok(SuggestionResult(items.toList()))
    }

    fun faster(id: Long, min: Int = 60, from: String = "15:00") = SuggestionItem(
        habitRef = "H$id", message = "Por las tardes casi no tomas agua. ¿Te aviso más seguido después de las 3 pm?",
        change = "interval", intervalMin = min, fromTime = from,
    )

    fun target(id: Long, n: Int = 10) = SuggestionItem(habitRef = "H$id", message = "¿Subimos la meta?", change = "target", dailyTarget = n)
}
