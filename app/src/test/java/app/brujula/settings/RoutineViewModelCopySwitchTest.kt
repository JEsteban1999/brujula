package app.brujula.settings

import app.brujula.calendar.FakeCalendarSource
import app.brujula.routine.RoutineRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric porque el ViewModel registra el fallo con android.util.Log. */
@RunWith(RobolectricTestRunner::class)
class RoutineViewModelCopySwitchTest {
    @Test fun failingCopySwitchWriteDoesNotCrash() = runTest {
        val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.flowOf(androidx.datastore.preferences.core.emptyPreferences())
            override suspend fun updateData(
                transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
            ): androidx.datastore.preferences.core.Preferences = throw java.io.IOException("boom")
        }
        val vm = RoutineViewModel(RoutineRepository(store), FakeReminderSync(), backgroundScope, FakeCalendarSource())
        vm.setCopyToGoogleEnabled(false).join()
        vm.setCopyToGoogleEnabled(true).join()
    }
}
