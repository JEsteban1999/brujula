package app.brujula.calendar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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

    private val copyKey = booleanPreferencesKey("copy_to_google_enabled")

    /** Interruptor global de la copia a Google Calendar (spec 8); activo por defecto. */
    val copyToGoogleEnabled: Flow<Boolean> = dataStore.data.map { p -> p[copyKey] ?: true }

    suspend fun setCopyToGoogleEnabled(on: Boolean) {
        dataStore.edit { it[copyKey] = on }
    }

    private fun decode(text: String): Set<Long> =
        text.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
}
