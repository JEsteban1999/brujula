package app.brujula.calendar

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CalendarPrefsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun defaultsToNull() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "p.preferences_pb") }
        assertNull(CalendarPrefs(ds).selectedCalendarIds.first())
    }

    @Test fun saveAndReadIncludingEmptySet() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "p.preferences_pb") }
        val prefs = CalendarPrefs(ds)
        prefs.setSelectedCalendarIds(setOf(3L, 1L))
        assertEquals(setOf(1L, 3L), prefs.selectedCalendarIds.first())
        prefs.setSelectedCalendarIds(emptySet())
        assertEquals(emptySet<Long>(), prefs.selectedCalendarIds.first())
    }

    @Test fun corruptValueIsIgnoredPerItem() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "p.preferences_pb") }
        ds.edit { it[stringPreferencesKey("selected_calendar_ids")] = "4,x,,7" }
        assertEquals(setOf(4L, 7L), CalendarPrefs(ds).selectedCalendarIds.first())
    }
}
