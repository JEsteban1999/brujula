package app.brujula.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.brujula.calendar.DeviceCalendar
import app.brujula.claude.ClaudeKeyStore
import app.brujula.claude.FakeClaudeGateway
import app.brujula.claude.FakeSecretCipher
import app.brujula.claude.KeyTestResult
import app.brujula.calendar.FakeCalendarSource
import app.brujula.core.theme.ThemeMode
import app.brujula.reminders.ReminderResync
import app.brujula.routine.RoutineRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val repo = RoutineRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.newFolder(), "c.preferences_pb") })
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
        val repo = RoutineRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "m.preferences_pb") })
        repo.calendarPrefs.setSelectedCalendarIds(setOf(1L, 99L))
        val e = Env(repo, FakeReminderSync(), RoutineViewModel(repo, FakeReminderSync(), backgroundScope, FakeCalendarSource(calendarList = listOf(cal(1)))))
        e.vm.refreshCalendars().join()
        assertEquals(listOf(1L), e.vm.calendars.value.groups.single().items.filter { it.selected }.map { it.calendar.id })
        e.vm.toggleCalendar(1).join()
        assertEquals(setOf(99L), e.repo.calendarPrefs.selectedCalendarIds.first()) // el id de la cuenta ausente se conserva
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

    @Test fun zeroCalendarsDoesNotPersistAndLaterSelectsAll() = runTest {
        val (e, src) = calEnv(FakeCalendarSource(calendarList = emptyList()))
        e.vm.refreshCalendars().join()
        assertTrue(e.vm.calendars.value.hasPermission)
        assertEquals(null, e.repo.calendarPrefs.selectedCalendarIds.first())
        src.calendarList = listOf(cal(1), cal(2))
        e.vm.refreshCalendars().join()
        assertEquals(setOf(1L, 2L), e.repo.calendarPrefs.selectedCalendarIds.first())
        assertTrue(e.vm.calendars.value.groups.flatMap { it.items }.all { it.selected })
    }

    @Test fun failedPrefsReadWritesNothing() = runTest {
        val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            var writes = 0
            override val data = kotlinx.coroutines.flow.flow<androidx.datastore.preferences.core.Preferences> { throw java.io.IOException("boom") }
            override suspend fun updateData(
                transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
            ): androidx.datastore.preferences.core.Preferences { writes++; return transform(androidx.datastore.preferences.core.emptyPreferences()) }
        }
        val repo = RoutineRepository(store)
        val vm = RoutineViewModel(repo, FakeReminderSync(), backgroundScope, FakeCalendarSource(calendarList = listOf(cal(1))))
        vm.refreshCalendars().join()
        assertEquals(0, store.writes)
        assertTrue(vm.calendars.value.hasPermission)
    }

    @Test fun deniedShowsSettingsHintGrantedClearsIt() = runTest {
        val (e, src) = calEnv(FakeCalendarSource(permission = false, calendarList = listOf(cal(1))))
        e.vm.onPermissionResult(false).join()
        assertTrue(e.vm.calendars.value.showSettingsHint)
        src.permission = true
        e.vm.onPermissionResult(true).join()
        assertFalse(e.vm.calendars.value.showSettingsHint)
        assertTrue(e.vm.calendars.value.hasPermission)
    }

    @Test fun toggleKeepsStoredIdsOfTemporarilyMissingAccounts() = runTest {
        val repo = RoutineRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "k.preferences_pb") })
        repo.calendarPrefs.setSelectedCalendarIds(setOf(1L, 99L))
        val vm = RoutineViewModel(repo, FakeReminderSync(), backgroundScope, FakeCalendarSource(calendarList = listOf(cal(1), cal(2))))
        vm.refreshCalendars().join()
        vm.toggleCalendar(2).join()
        assertEquals(setOf(1L, 2L, 99L), repo.calendarPrefs.selectedCalendarIds.first())
        vm.toggleCalendar(1).join()
        assertEquals(setOf(2L, 99L), repo.calendarPrefs.selectedCalendarIds.first())
    }

    // --- interruptor global de la copia (M5) y reconciliacion (I3) ---
    private class Reconciles { var count = 0 }

    private fun TestScope.copyEnv(name: String, counter: Reconciles, granted: Boolean = true): Pair<Env, FakeCalendarSource> {
        val repo = RoutineRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, name) })
        val src = FakeCalendarSource(permission = granted, calendarList = listOf(cal(1)))
        val vm = RoutineViewModel(
            repo, FakeReminderSync(), backgroundScope, src,
            syncScope = backgroundScope, reconcileGoogle = { counter.count++ },
        )
        return Env(repo, FakeReminderSync(), vm) to src
    }

    @Test fun copySwitchDefaultsOnAndPersists() = runTest {
        val (e, _) = copyEnv("s.preferences_pb", Reconciles())
        assertTrue(e.vm.copyToGoogleEnabled.first())
        e.vm.setCopyToGoogleEnabled(false).join()
        assertFalse(e.repo.calendarPrefs.copyToGoogleEnabled.first())
        assertFalse(e.vm.copyToGoogleEnabled.first { !it })
    }

    @Test fun turningCopySwitchOnReconcilesButOffDoesNot() = runTest {
        val counter = Reconciles()
        val (e, _) = copyEnv("s2.preferences_pb", counter)
        e.vm.setCopyToGoogleEnabled(false).join()
        assertEquals(0, counter.count)
        e.vm.setCopyToGoogleEnabled(true).join()
        assertEquals(1, counter.count)
    }

    @Test fun grantingPermissionReconcilesDenyingDoesNot() = runTest {
        val counter = Reconciles()
        val (e, _) = copyEnv("s3.preferences_pb", counter)
        e.vm.onPermissionResult(false).join()
        assertEquals(0, counter.count)
        e.vm.onPermissionResult(true).join()
        assertEquals(1, counter.count)
    }

    // --- Clave de Claude ---

    private class ClaudeEnv(val keys: ClaudeKeyStore, val gateway: FakeClaudeGateway, val vm: RoutineViewModel)

    private fun TestScope.claudeEnv(result: KeyTestResult = KeyTestResult.OK, onKeySaved: () -> Unit = {}): ClaudeEnv {
        val repo = RoutineRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.newFolder(), "r.preferences_pb") })
        val keys = ClaudeKeyStore(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.newFolder(), "c.preferences_pb") }, FakeSecretCipher(),
        )
        val gateway = FakeClaudeGateway(result)
        return ClaudeEnv(keys, gateway, RoutineViewModel(repo, scopeOverride = backgroundScope, claudeKeyStore = keys, claudeGateway = gateway, onKeySaved = onKeySaved))
    }

    @Test fun claudeKeyTrimmedAndSaved() = runTest {
        val e = claudeEnv()
        e.vm.saveClaudeKey("  sk-ant-test-0000 \n").join()
        assertEquals("sk-ant-test-0000", e.keys.read())
        assertTrue(e.vm.claude.value.hasKey)
        assertEquals("0000", e.vm.claude.value.lastFour)
    }

    @Test fun claudeBlankKeyClears() = runTest {
        val e = claudeEnv()
        e.vm.saveClaudeKey("sk-ant-test-0000").join()
        e.vm.saveClaudeKey("   ").join()
        assertNull(e.keys.read())
        assertFalse(e.vm.claude.value.hasKey)
        assertNull(e.vm.claude.value.lastFour)
    }

    @Test fun claudeTestMapsResults() = runTest {
        for ((result, status) in listOf(
            KeyTestResult.OK to ClaudeStatus.CONNECTED,
            KeyTestResult.INVALID to ClaudeStatus.INVALID,
            KeyTestResult.NETWORK to ClaudeStatus.NETWORK,
            KeyTestResult.ERROR to ClaudeStatus.ERROR,
        )) {
            val e = claudeEnv(result)
            e.vm.saveClaudeKey("sk-ant-test-0000").join()
            e.vm.testClaudeKey().join()
            assertEquals(status, e.vm.claude.value.status)
        }
    }

    @Test fun claudeTestWithoutKeyDoesNotCallGateway() = runTest {
        val e = claudeEnv()
        e.vm.testClaudeKey().join()
        assertEquals(0, e.gateway.calls)
        assertEquals(ClaudeStatus.IDLE, e.vm.claude.value.status)
    }

    @Test fun savingNewKeyResetsStatus() = runTest {
        val e = claudeEnv()
        e.vm.saveClaudeKey("sk-ant-test-0000").join()
        e.vm.testClaudeKey().join()
        e.vm.saveClaudeKey("sk-ant-test-1111").join()
        assertEquals(ClaudeStatus.IDLE, e.vm.claude.value.status)
    }

    @Test fun claudeStateFollowsStoreWhenKeyIsWipedElsewhere() = runTest {
        val e = claudeEnv()
        e.vm.saveClaudeKey("sk-ant-test-0000").join()
        e.keys.clear()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { e.vm.claude.first { !it.hasKey } }
        }
        assertFalse(e.vm.claude.value.hasKey)
    }

    @Test fun testResultFromBeforeANewKeyWasSavedIsIgnored() = runTest {
        val e = claudeEnv(KeyTestResult.OK)
        e.vm.saveClaudeKey("sk-ant-test-0000").join()
        val gate = CompletableDeferred<Unit>().also { e.gateway.testGate = it }
        val test = e.vm.testClaudeKey()
        runCurrent()
        assertEquals(ClaudeStatus.TESTING, e.vm.claude.value.status)
        e.vm.saveClaudeKey("sk-ant-test-1111").join()
        gate.complete(Unit)
        test.join()
        assertEquals(ClaudeStatus.IDLE, e.vm.claude.value.status)
    }

    @Test fun testResultFromBeforeTheKeyWasClearedIsIgnored() = runTest {
        val e = claudeEnv(KeyTestResult.OK)
        e.vm.saveClaudeKey("sk-ant-test-0000").join()
        val gate = CompletableDeferred<Unit>().also { e.gateway.testGate = it }
        val test = e.vm.testClaudeKey()
        runCurrent()
        e.vm.saveClaudeKey("").join()
        gate.complete(Unit)
        test.join()
        assertEquals(ClaudeSectionState(), e.vm.claude.value)
    }

    @Test fun savingAKeyTriggersAPendingDrainButClearingDoesNot() = runTest {
        var drains = 0
        val e = claudeEnv(onKeySaved = { drains++ })
        e.vm.saveClaudeKey("sk-ant-test-0000").join()
        assertEquals(1, drains)
        e.vm.saveClaudeKey("  ").join()
        assertEquals(1, drains)
    }

    @Test fun shiftNightNoticeClampsAndResyncs() = runTest {
        val e = env()
        assertEquals(LocalTime.of(22, 45), e.repo.routine.first().nightNotice)
        e.vm.shiftNightNotice(15).join()
        assertEquals(LocalTime.of(23, 0), e.repo.routine.first().nightNotice)
        assertEquals(1, e.sync.calls)
        e.vm.shiftNightNotice(60).join()
        assertEquals(LocalTime.of(23, 45), e.repo.routine.first().nightNotice)
        e.vm.shiftNightNotice(15).join()
        assertEquals("No da la vuelta a la medianoche", LocalTime.of(23, 45), e.repo.routine.first().nightNotice)
        e.repo.update { it.copy(nightNotice = LocalTime.of(18, 15)) }
        e.vm.shiftNightNotice(-15).join()
        assertEquals(LocalTime.of(18, 0), e.repo.routine.first().nightNotice)
        e.vm.shiftNightNotice(-15).join()
        assertEquals(LocalTime.of(18, 0), e.repo.routine.first().nightNotice)
        assertEquals(5, e.sync.calls)
    }
}
