package app.brujula.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.brujula.core.theme.ThemeMode
import app.brujula.reminders.ReminderResync
import app.brujula.routine.RoutineRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalTime

class FakeReminderSync : ReminderResync {
    var calls = 0
    override suspend fun resync() { calls++ }
}

class RoutineViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Env(val repo: RoutineRepository, val sync: FakeReminderSync, val vm: RoutineViewModel)

    private fun TestScope.env(): Env {
        val repo = RoutineRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "r.preferences_pb") })
        val sync = FakeReminderSync()
        return Env(repo, sync, RoutineViewModel(repo, sync, backgroundScope))
    }

    @Test fun wakeAndBedWrapAroundMidnight() = runTest {
        val e = env()
        e.repo.update { it.copy(bed = LocalTime.of(23, 45), wake = LocalTime.of(0, 0)) }
        e.vm.shiftBed(15)?.join()
        assertEquals(LocalTime.of(0, 0), e.repo.routine.first().bed)
        e.vm.shiftBed(-15)?.join()
        assertEquals(LocalTime.of(23, 45), e.repo.routine.first().bed)
        e.vm.shiftWake(-15)?.join()
        assertEquals(LocalTime.of(23, 45), e.repo.routine.first().wake)
        e.vm.shiftWake(15)?.join()
        assertEquals(LocalTime.of(0, 0), e.repo.routine.first().wake)
    }

    @Test fun goalOptions() = runTest {
        val e = env()
        assertEquals(listOf(300..360, 360..420, 420..480), RoutineViewModel.GOAL_OPTIONS)
        e.vm.setGoal(300..360)?.join()
        assertEquals(300..360, e.repo.routine.first().workGoalMin)
        e.vm.setGoal(420..480)?.join()
        assertEquals(420..480, e.repo.routine.first().workGoalMin)
        e.vm.setGoal(100..200)?.join()
        assertEquals(420..480, e.repo.routine.first().workGoalMin)
    }

    @Test fun changesTriggerResync() = runTest {
        val e = env()
        e.vm.shiftWake(15)?.join()
        assertEquals(1, e.sync.calls)
        e.vm.shiftBed(-15)?.join()
        assertEquals(2, e.sync.calls)
    }

    @Test fun themeAndFlexiblePersist() = runTest {
        val e = env()
        e.vm.setThemeMode(ThemeMode.DARK)?.join()
        e.vm.setFlexible(false)?.join()
        val r = e.repo.routine.first()
        assertEquals(ThemeMode.DARK, r.themeMode)
        assertEquals(false, r.flexibleWork)
    }

    @Test fun restHoursShiftPersistAndResync() = runTest {
        val e = env()
        e.vm.shiftRestWake(15).join()
        assertEquals(LocalTime.of(10, 15), e.repo.routine.first().restWake)
        assertEquals(1, e.sync.calls)
        e.vm.shiftRestBed(-15).join()
        assertEquals(LocalTime.of(0, 45), e.repo.routine.first().restBed)
        assertEquals(2, e.sync.calls)
    }
}
