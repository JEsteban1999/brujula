package app.brujula.calendar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Calendarios elegidos para importar. `null` = el usuario aun no eligio. Lectura defensiva ante datos danados. */
class CalendarPrefs(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey("selected_calendar_ids")

    val selectedCalendarIds: Flow<Set<Long>?> = dataStore.data.map { p -> p[key]?.let(::decode) }

    suspend fun setSelectedCalendarIds(ids: Set<Long>) {
        dataStore.edit { it[key] = ids.sorted().joinToString(",") }
    }

    private fun decode(text: String): Set<Long> =
        text.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
}
