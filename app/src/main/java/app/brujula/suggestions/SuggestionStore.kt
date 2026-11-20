package app.brujula.suggestions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.brujula.data.db.decodeSchedule
import app.brujula.data.db.encode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

enum class SuggestionStatus { PENDING, ACCEPTED, DISMISSED, UNDONE, STALE }

data class StoredSuggestion(
    val id: Long,
    val suggestion: HabitSuggestion,
    val status: SuggestionStatus,
    val decidedAt: LocalDateTime?,
)

/** Días que una sugerencia descartada (o deshecha) no se vuelve a proponer para el mismo hábito y tipo de cambio. */
const val REJECTION_DAYS = 30L

/**
 * Sugerencias de hábitos en DataStore (D14): un JSON con las últimas [MAX_ENTRIES] (la más nueva primero) y las marcas de
 * última corrida e intento. Un JSON dañado se lee como vacío y un elemento ilegible se salta; nunca lanza.
 */
class SuggestionStore(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey("habit_suggestions")
    private val backupKey = stringPreferencesKey("habit_suggestions_backup")

    val entries: Flow<List<StoredSuggestion>> = dataStore.data.map { parse(it[key]).entries }

    /** Guarda [s] como la sugerencia pendiente; la pendiente anterior pasa a `STALE`. */
    suspend fun savePending(s: HabitSuggestion, id: Long): StoredSuggestion {
        val stored = StoredSuggestion(id, s, SuggestionStatus.PENDING, null)
        update { state ->
            val older = state.entries.filter { it.id != id }.map {
                if (it.status == SuggestionStatus.PENDING) it.copy(status = SuggestionStatus.STALE) else it
            }
            state.copy(entries = (listOf(stored) + older).take(MAX_ENTRIES))
        }
        return stored
    }

    suspend fun setStatus(id: Long, status: SuggestionStatus, at: LocalDateTime) {
        update { state ->
            state.copy(entries = state.entries.map { if (it.id == id) it.copy(status = status, decidedAt = at) else it })
        }
    }

    suspend fun lastRun(): LocalDateTime? = read().lastRun

    suspend fun lastAttempt(): LocalDateTime? = read().lastAttempt

    /** Anota un intento de pedir sugerencias; [success] (Claude respondió) también mueve la última corrida. */
    suspend fun markAttempt(at: LocalDateTime, success: Boolean) {
        update { it.copy(lastAttempt = at, lastRun = if (success) at else it.lastRun) }
    }

    /** Hábito y tipo de cambio descartados o deshechos en los últimos [REJECTION_DAYS] días. */
    suspend fun rejected(now: LocalDateTime): Set<Pair<Long, SuggestionKind>> {
        val limit = now.minusDays(REJECTION_DAYS)
        return read().entries
            .filter { (it.status == SuggestionStatus.DISMISSED || it.status == SuggestionStatus.UNDONE) && it.decidedAt?.let { d -> d > limit } == true }
            .map { it.suggestion.habitId to it.suggestion.kind }
            .toSet()
    }

    private suspend fun read(): State = parse(dataStore.data.first()[key])

    private suspend fun update(change: (State) -> State) {
        dataStore.edit { prefs ->
            val parsed = parse(prefs[key])
            // Algo ilegible se respalda antes de escribir: nunca se pisa sin copia.
            if (parsed.damaged) prefs[backupKey] = prefs[key].orEmpty()
            prefs[key] = encode(change(parsed))
        }
    }

    private data class State(
        val entries: List<StoredSuggestion> = emptyList(),
        val lastRun: LocalDateTime? = null,
        val lastAttempt: LocalDateTime? = null,
        val damaged: Boolean = false,
    )

    private fun parse(raw: String?): State {
        if (raw.isNullOrBlank()) return State()
        return try {
            val root = JSONObject(raw)
            val array = root.optJSONArray("items") ?: JSONArray()
            var damaged = false
            val items = (0 until array.length()).mapNotNull { i ->
                runCatching { decodeEntry(array.getJSONObject(i)) }.getOrNull().also { if (it == null) damaged = true }
            }
            State(
                entries = items.take(MAX_ENTRIES),
                lastRun = root.optString("lastRun").toDateTimeOrNull(),
                lastAttempt = root.optString("lastAttempt").toDateTimeOrNull(),
                damaged = damaged,
            )
        } catch (e: Exception) {
            State(damaged = true)
        }
    }

    private fun encode(state: State): String = JSONObject().apply {
        state.lastRun?.let { put("lastRun", it.toString()) }
        state.lastAttempt?.let { put("lastAttempt", it.toString()) }
        put("items", JSONArray().apply { state.entries.forEach { put(encodeEntry(it)) } })
    }.toString()

    private fun encodeEntry(e: StoredSuggestion): JSONObject = JSONObject().apply {
        val s = e.suggestion
        put("id", e.id)
        put("status", e.status.name)
        e.decidedAt?.let { put("decidedAt", it.toString()) }
        put("habitId", s.habitId)
        put("habitName", s.habitName)
        put("kind", s.kind.name)
        put("message", s.message)
        put("before", encodeChange(s.before))
        put("after", encodeChange(s.after))
        put("createdAt", s.createdAt.toString())
    }

    private fun encodeChange(c: HabitChange) = JSONObject().put("schedule", c.schedule.encode()).put("target", c.dailyTarget)

    private fun decodeChange(o: JSONObject) = HabitChange(decodeSchedule(o.getString("schedule")), o.getInt("target"))

    private fun decodeEntry(o: JSONObject): StoredSuggestion = StoredSuggestion(
        id = o.getLong("id"),
        status = SuggestionStatus.valueOf(o.getString("status")),
        decidedAt = o.optString("decidedAt").toDateTimeOrNull(),
        suggestion = HabitSuggestion(
            habitId = o.getLong("habitId"),
            habitName = o.getString("habitName"),
            kind = SuggestionKind.valueOf(o.getString("kind")),
            message = o.getString("message"),
            before = decodeChange(o.getJSONObject("before")),
            after = decodeChange(o.getJSONObject("after")),
            createdAt = LocalDateTime.parse(o.getString("createdAt")),
        ),
    )

    private fun String.toDateTimeOrNull(): LocalDateTime? = if (isEmpty()) null else runCatching { LocalDateTime.parse(this) }.getOrNull()

    companion object {
        const val MAX_ENTRIES = 20
    }
}
