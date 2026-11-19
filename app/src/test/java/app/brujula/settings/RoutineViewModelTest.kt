package app.brujula.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.brujula.calendar.DeviceCalendar
import app.brujula.calendar.FakeCalendarSource
import app.brujula.core.theme.ThemeMode
import app.brujula.reminders.ReminderResync
import app.brujula.routine.RoutineRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun cal(id: Long, account: String = "ana@gmail.com", type: String = "com.google") =
        DeviceCalendar(id, "Cal $id", account, type, 0, false)

    private fun TestScope.calEnv(src: FakeCalendarSource): Pair<Env, FakeCalendarSource> {
        val repo = RoutineRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "c.preferences_pb") })
        return Env(repo, FakeReminderSync(), RoutineViewModel(repo, FakeReminderSync(), backgroundScope, src)) to src
    }

    @Test fun noPermissionMeansEmptyGroups() = runTest {
        val (e, _) = calEnv(FakeCalendarSource(permission = false, calendarList = listOf(cal(1))))
        e.vm.refreshCalendars().join()
        assertFalse(e.vm.calendars.value.hasPermission)
        assertTrue(e.vm.calendars.value.groups.isEmpty())
        assertEquals(null, e.repo.calendarPrefs.selectedCalendarIds.first())
    }

    @Test fun firstTimeWithPermissionSelectsAllAndSaves() = runTest {
        val (e, _) = calEnv(FakeCalendarSource(calendarList = listOf(cal(1), cal(2, "o@outlook.com", "com.microsoft.office.outlook"))))
        e.vm.onPermissionResult(true).join()
        val s = e.vm.calendars.value
        assertTrue(s.hasPermission)
        assertEquals(listOf("Google", "Outlook"), s.groups.map { it.source })
        assertTrue(s.groups.flatMap { it.items }.all { it.selected })
        assertEquals(setOf(1L, 2L), e.repo.calendarPrefs.selectedCalendarIds.first())
    }

    @Test fun toggleRemovesAndAdds() = runTest {
        val (e, _) = calEnv(FakeCalendarSource(calendarList = listOf(cal(1), cal(2))))
        e.vm.refreshCalendars().join()
        e.vm.toggleCalendar(1).join()
        assertEquals(setOf(2L), e.repo.calendarPrefs.selectedCalendarIds.first())
        assertEquals(listOf(false, true), e.vm.calendars.value.groups.single().items.map { it.selected })
        e.vm.toggleCalendar(1).join()
        assertEquals(setOf(1L, 2L), e.repo.calendarPrefs.selectedCalendarIds.first())
    }

    @Test fun missingSavedIdIsIgnored() = runTest {
        val (e, _) = calEnv(FakeCalendarSource(calendarList = listOf(cal(1))))
        e.repo.calendarPrefs.setSelectedCalendarIds(setOf(1L, 99L))
        e.vm.refreshCalendars().join()
        assertEquals(listOf(1L), e.vm.calendars.value.groups.single().items.filter { it.selected }.map { it.calendar.id })
        e.vm.toggleCalendar(1).join()
        assertEquals(emptySet<Long>(), e.repo.calendarPrefs.selectedCalendarIds.first())
    }

    @Test fun revokedPermissionFallsBackToNoPermission() = runTest {
        val (e, src) = calEnv(FakeCalendarSource(calendarList = listOf(cal(1))))
        e.vm.refreshCalendars().join()
        assertTrue(e.vm.calendars.value.hasPermission)
        src.permission = false
        e.vm.refreshCalendars().join()
        assertFalse(e.vm.calendars.value.hasPermission)
        assertTrue(e.vm.calendars.value.groups.isEmpty())
    }
}
