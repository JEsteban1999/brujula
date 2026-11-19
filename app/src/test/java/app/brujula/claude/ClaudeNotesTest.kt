package app.brujula.claude

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = app.brujula.TestBrujulaApp::class)
class ClaudeNotesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.store() = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "n.preferences_pb") }

    @Test fun setAndRead() = runTest {
        val ds = store()
        val notes = ClaudeNotes(ds)
        assertNull(notes.note.first())

        val fri = LocalDate.of(2026, 11, 20)
        notes.set(ClaudeNote("Te armé el viernes: \"foco\" en la mañana.\nY gimnasio a las 7.", fri, 42))
        assertEquals(ClaudeNote("Te armé el viernes: \"foco\" en la mañana.\nY gimnasio a las 7.", fri, 42), notes.note.first())

        notes.set(ClaudeNote("Deshice los cambios del plan.", fri, null))
        assertEquals(ClaudeNote("Deshice los cambios del plan.", fri, null), notes.note.first())

        // Un valor dañado no tumba la tarjeta: no hay nota.
        ds.edit { it[stringPreferencesKey(ClaudeNotes.KEY)] = "{dañado" }
        assertNull(notes.note.first())
    }
}
