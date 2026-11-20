package app.brujula.suggestions

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.brujula.habits.FasterStretch
import app.brujula.habits.HabitSchedule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class SuggestionStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = SuggestionFixtures.now
    private val raw = stringPreferencesKey("habit_suggestions")

    private fun suggestion(habitId: Long = 3, kind: SuggestionKind = SuggestionKind.INTERVAL) = HabitSuggestion(
        habitId = habitId, habitName = "Agua", kind = kind, message = "¿Te aviso más seguido?",
        before = HabitChange(HabitSchedule.Every(90), 8),
        after = HabitChange(HabitSchedule.Every(90, FasterStretch(LocalTime.of(15, 0), 60)), 8),
        createdAt = now,
    )

    @Test fun savePendingSupersedesPrevious() = runTest {
        val store = SuggestionStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "a.preferences_pb") })
        store.savePending(suggestion(3), 1)
        val second = store.savePending(suggestion(4, SuggestionKind.TARGET), 2)
        assertEquals(SuggestionStatus.PENDING, second.status)
        val all = store.entries.first()
        assertEquals(listOf(2L, 1L), all.map { it.id })
        assertEquals(listOf(SuggestionStatus.PENDING, SuggestionStatus.STALE), all.map { it.status })
        // Lo guardado se lee igual (horario con tramo incluido).
        assertEquals(suggestion(3), all.last().suggestion)
        store.setStatus(2, SuggestionStatus.ACCEPTED, now.plusMinutes(5))
        assertEquals(now.plusMinutes(5), store.entries.first().first().decidedAt)
    }

    @Test fun historyCappedAt20() = runTest {
        val store = SuggestionStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "b.preferences_pb") })
        (1L..25L).forEach { store.savePending(suggestion(), it) }
        val all = store.entries.first()
        assertEquals(20, all.size)
        assertEquals(25L, all.first().id)
        assertEquals(6L, all.last().id)
    }

    @Test fun damagedJsonIsEmpty() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "c.preferences_pb") }
        val store = SuggestionStore(ds)
        ds.edit { it[raw] = "{esto no es json" }
        assertEquals(emptyList<StoredSuggestion>(), store.entries.first())
        assertNull(store.lastRun())
        assertNull(store.lastAttempt())
        assertEquals(emptySet<Pair<Long, SuggestionKind>>(), store.rejected(now))
        // Escribir sobre algo ilegible funciona.
        store.savePending(suggestion(), 1)
        assertEquals(1, store.entries.first().size)

        // Un elemento dañado se salta; los demás se conservan.
        val root = JSONObject(ds.data.first()[raw]!!)
        root.getJSONArray("items").put(JSONObject().put("id", "x").put("status", "NOPE"))
        ds.edit { it[raw] = root.toString() }
        assertEquals(listOf(1L), store.entries.first().map { it.id })
    }

    @Test fun lastRunAndAttemptPersist() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "d.preferences_pb") }
        val store = SuggestionStore(ds)
        assertNull(store.lastRun())
        store.markAttempt(now.minusDays(8), success = true)
        store.markAttempt(now.minusDays(2), success = false)
        val again = SuggestionStore(ds)
        assertEquals(now.minusDays(8), again.lastRun())
        assertEquals(now.minusDays(2), again.lastAttempt())
    }

    @Test fun rejectedAreDismissedOrUndoneInLast30Days() = runTest {
        val store = SuggestionStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "e.preferences_pb") })
        store.savePending(suggestion(3, SuggestionKind.INTERVAL), 1)
        store.savePending(suggestion(4, SuggestionKind.TIMES), 2)
        store.savePending(suggestion(5, SuggestionKind.TARGET), 3)
        store.savePending(suggestion(6, SuggestionKind.TARGET), 4)
        store.setStatus(1, SuggestionStatus.DISMISSED, now)
        store.setStatus(2, SuggestionStatus.UNDONE, now.minusDays(10))
        store.setStatus(3, SuggestionStatus.ACCEPTED, now)
        store.setStatus(4, SuggestionStatus.STALE, now)
        assertEquals(setOf(3L to SuggestionKind.INTERVAL, 4L to SuggestionKind.TIMES), store.rejected(now.plusDays(1)))
        assertEquals(setOf(3L to SuggestionKind.INTERVAL), store.rejected(now.plusDays(25)))
        assertEquals(emptySet<Pair<Long, SuggestionKind>>(), store.rejected(now.plusDays(31)))
    }
}
