package app.brujula.activities.form

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.calendar.ExternalEvent
import app.brujula.calendar.FakeCalendarSource
import app.brujula.core.time.FixedClock
import app.brujula.core.time.TimeSpan
import app.brujula.data.db.BrujulaDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class ActivityFormViewModelTest {
    private lateinit var db: BrujulaDatabase
    private lateinit var repo: ActivityRepository
    private val today = LocalDate.of(2026, 11, 18)
    private val tomorrow = today.plusDays(1)
    private val clock = FixedClock(LocalDateTime.of(2026, 11, 18, 10, 40))
    private var resyncs = 0
    private val calendar = FakeCalendarSource()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ActivityRepository(db.activityDao())
        // Mañana hay "Ejercicio en casa" 19:00–20:00.
        runBlocking {
            repo.upsert(
                Activity(
                    title = "Ejercicio en casa", type = ActivityType.EXERCISE, date = tomorrow,
                    start = LocalTime.of(19, 0), durationMin = 60,
                ),
            )
        }
    }

    @After fun tearDown() { db.close() }

    private fun TestScope.newVm(id: Long? = null, prefill: LocalDateTime? = null) = ActivityFormViewModel(
        activityId = id,
        prefillStart = prefill,
        clock = clock,
        activityRepository = repo,
        resync = { resyncs++ },
        calendarSource = calendar,
        selectedCalendarIds = flowOf(setOf(1L)),
        scopeOverride = backgroundScope,
    )

    @Test fun newFormStartsAtNextQuarterHour() = runTest {
        assertEquals(LocalTime.of(10, 45), newVm().state.value.start)
    }

    @Test fun prefillFromFreeSlot() = runTest {
        assertEquals(LocalTime.of(15, 0), newVm(prefill = LocalDateTime.of(2026, 11, 18, 15, 0)).state.value.start)
    }

    @Test fun appointmentTurnsGoogleCopyOnUnlessTouched() = runTest {
        val vm = newVm(); vm.setType(ActivityType.APPOINTMENT); assertTrue(vm.state.value.copyToGoogle)
        vm.setCopyToGoogle(false); vm.setType(ActivityType.WORK); vm.setType(ActivityType.APPOINTMENT)
        assertFalse(vm.state.value.copyToGoogle)
    }

    @Test fun liveConflict() = runTest {
        val vm = newVm(); vm.setDateChoice(DateChoice.TOMORROW); vm.shiftStart(495); vm.setDuration(60)
        assertEquals("Ejercicio en casa", vm.state.first { it.conflict != null }.conflict!!.activity.title)
    }

    @Test fun conflictsWithExternalEventNamingItsSource() = runTest {
        calendar.eventList = listOf(
            ExternalEvent(1, 0, 1, "Personal", "Google", "Dentista", TimeSpan(today.atTime(15, 0), today.atTime(16, 0)), false, null),
            ExternalEvent(2, 0, 1, "Personal", "Google", "Festivo", TimeSpan(today.atStartOfDay(), tomorrow.atStartOfDay()), true, null),
        )
        val vm = newVm(prefill = today.atTime(15, 30))
        val s = vm.state.first { it.externalConflict != null }
        assertEquals("Choca con Dentista (Google).", s.conflictMessage)
        vm.shiftStart(-60) // 14:30-15:30 aun solapa
        assertEquals("Dentista", vm.state.value.externalConflict?.title)
        vm.shiftStart(-60) // 13:30-14:30 libre; el de todo el dia no choca
        assertNull(vm.state.value.externalConflict)
        assertNull(vm.state.value.conflictMessage)
    }

    @Test fun noConflictWhenFree() = runTest {
        val vm = newVm(); vm.setDateChoice(DateChoice.TOMORROW); vm.shiftStart(495) // 19:00: choca
        vm.state.first { it.conflict != null } // prueba que el VM ya observa la lista del repositorio
        vm.shiftStart(60) // 20:00, justo al terminar el bloque
        assertEquals(LocalTime.of(20, 0), vm.state.value.start)
        assertNull(vm.state.value.conflict)
    }

    @Test fun overnightSpillConflictsWithNextDayCandidate() = runTest {
        // Mañana 23:30 + 1 h termina 00:30 del día siguiente; un candidato pasado mañana 00:15 debe chocar.
        repo.upsert(Activity(title = "Cine", type = ActivityType.PERSONAL, date = tomorrow, start = LocalTime.of(23, 30), durationMin = 60))
        val vm = newVm(prefill = tomorrow.plusDays(1).atTime(0, 15))
        vm.setDuration(30)
        assertEquals("Cine", vm.state.first { it.conflict != null }.conflict!!.activity.title)
    }

    @Test fun candidateCrossingMidnightConflictsWithNextDay() = runTest {
        repo.upsert(Activity(title = "Madrugón", type = ActivityType.WORK, date = tomorrow, start = LocalTime.of(0, 15), durationMin = 30))
        val vm = newVm(prefill = today.atTime(23, 30)) // 23:30 + 1 h cruza a mañana 00:30
        vm.setDuration(60)
        assertEquals("Madrugón", vm.state.first { it.conflict != null }.conflict!!.activity.title)
    }

    @Test fun durationIsAlwaysPositive() = runTest {
        val vm = newVm()
        vm.setDuration(0); assertEquals(1, vm.state.value.durationMin)
        vm.setDuration(-5); assertEquals(1, vm.state.value.durationMin)
        vm.setDuration(100000); assertEquals(720, vm.state.value.durationMin)
    }

    @Test fun emptyTitleBlocksSave() = runTest {
        val vm = newVm(); vm.save()
        assertEquals("Escribe qué vas a hacer", vm.state.value.titleError); assertFalse(vm.state.value.saved)
        assertEquals(1, repo.observeAll().first().size) // solo la sembrada
    }

    @Test fun saveAndEditRoundTrip() = runTest {
        val vm = newVm(); vm.setTitle("Dentista"); vm.setType(ActivityType.APPOINTMENT)
        vm.setDateChoice(DateChoice.TOMORROW)
        vm.shiftStart(15 * 60 - (10 * 60 + 45)) // 15:00
        vm.setDuration(30)
        vm.save()
        vm.state.first { it.saved }
        val saved = repo.observeAll().first().single { it.title == "Dentista" }
        assertEquals(ActivityType.APPOINTMENT, saved.type)
        assertEquals(tomorrow, saved.date); assertEquals(LocalTime.of(15, 0), saved.start); assertEquals(30, saved.durationMin)
        assertEquals(15, saved.remindBeforeMin); assertTrue(saved.copyToGoogle)
        assertEquals(1, resyncs)

        val edit = newVm(id = saved.id)
        val s = edit.state.first { it.isEdit }
        assertEquals("Dentista", s.title); assertEquals(ActivityType.APPOINTMENT, s.type)
        assertEquals(DateChoice.TOMORROW, s.dateChoice); assertEquals(LocalTime.of(15, 0), s.start)
        assertEquals(30, s.durationMin); assertTrue(s.remind); assertTrue(s.copyToGoogle)
        // Primero prueba que la lista está cargada: a las 19:00 choca con "Ejercicio en casa".
        edit.shiftStart(4 * 60)
        assertEquals("Ejercicio en casa", edit.state.first { it.conflict != null }.conflict!!.activity.title)
        // De vuelta a las 15:00 se solapa consigo misma, pero se ignora.
        edit.shiftStart(-4 * 60)
        assertEquals(LocalTime.of(15, 0), edit.state.value.start)
        assertNull(edit.state.value.conflict)

        edit.setTitle("Dentista Ruiz"); edit.save(); edit.state.first { it.saved }
        val all = repo.observeAll().first().filter { it.type == ActivityType.APPOINTMENT }
        assertEquals(1, all.size); assertEquals("Dentista Ruiz", all[0].title); assertEquals(saved.id, all[0].id)
    }

    @Test fun saveWithConflictIsAllowed() = runTest {
        val vm = newVm(); vm.setTitle("Reunión"); vm.setDateChoice(DateChoice.TOMORROW); vm.shiftStart(495); vm.setDuration(60)
        vm.state.first { it.conflict != null }
        vm.save(); vm.state.first { it.saved }
        assertEquals(2, repo.observeAll().first().size)
    }

    @Test fun remindOffSavesNoReminder() = runTest {
        val vm = newVm(); vm.setTitle("Leer"); vm.setRemind(false); vm.save(); vm.state.first { it.saved }
        assertNull(repo.observeAll().first().single { it.title == "Leer" }.remindBeforeMin)
    }

    @Test fun deleteRemovesAndFinishes() = runTest {
        val id = repo.observeAll().first().single().id
        val vm = newVm(id = id); vm.state.first { it.isEdit }
        vm.delete(); vm.state.first { it.saved }
        assertTrue(repo.observeAll().first().isEmpty())
        assertEquals(1, resyncs)
    }

    @Test fun doubleTapSaveCreatesOneActivity() = runTest {
        val vm = newVm(); vm.setTitle("Una sola"); vm.save(); vm.save()
        vm.state.first { it.saved }
        assertEquals(1, repo.observeAll().first().count { it.title == "Una sola" })
        assertEquals(1, resyncs)
    }

    @Test fun defaultAtMidnightUsesLogicalDayLabel() = runTest {
        clock.current = LocalDateTime.of(2026, 11, 19, 0, 20) // día lógico: 18
        val vm = newVm()
        val s = vm.state.value
        assertEquals(LocalTime.of(0, 30), s.start); assertEquals(LocalDate.of(2026, 11, 19), s.date)
        assertEquals(DateChoice.TODAY, s.dateChoice)
        vm.setDateChoice(DateChoice.TOMORROW) // lógico 19 -> 00:30 del 20 natural
        assertEquals(DateChoice.TOMORROW, vm.state.value.dateChoice)
        assertEquals(LocalDate.of(2026, 11, 20), vm.state.value.date)
        vm.setDateChoice(DateChoice.TODAY)
        assertEquals(LocalDate.of(2026, 11, 19), vm.state.value.date)
    }

    @Test fun roundingFrom2350GoesToNextCalendarDay() = runTest {
        clock.current = LocalDateTime.of(2026, 11, 18, 23, 50)
        val s = newVm().state.value
        assertEquals(LocalTime.of(0, 0), s.start); assertEquals(LocalDate.of(2026, 11, 19), s.date)
        assertEquals(DateChoice.TODAY, s.dateChoice) // 00:00 del 19 sigue siendo el día lógico 18
    }

    @Test fun shiftAcrossFourAmKeepsChosenLogicalDay() = runTest {
        clock.current = LocalDateTime.of(2026, 11, 19, 0, 20)
        val vm = newVm(); vm.shiftStart(3 * 60 + 30) // 04:00
        assertEquals(LocalTime.of(4, 0), vm.state.value.start)
        assertEquals(DateChoice.TODAY, vm.state.value.dateChoice)
        assertEquals(LocalDate.of(2026, 11, 18), vm.state.value.date)
    }

    @Test fun editOfMissingActivityStaysUsableAsNew() = runTest {
        val vm = newVm(id = 9999L)
        vm.state.first { it.ready }
        assertFalse(vm.state.value.isEdit)
        vm.setTitle("Nueva"); vm.save(); vm.state.first { it.saved }
        assertEquals(2, repo.observeAll().first().size)
    }

    @Test fun saveBeforeEditLoadedIsIgnored() = runTest {
        val id = repo.observeAll().first().single().id
        val vm = newVm(id = id)
        assertFalse(vm.state.value.ready)
        vm.save()
        assertFalse(vm.state.value.saving)
        vm.state.first { it.ready }
        assertEquals(1, repo.observeAll().first().size)
    }

    @Test fun failingCalendarPrefsDoNotBreakTheForm() = runTest {
        val vm = ActivityFormViewModel(
            activityId = null, prefillStart = null, clock = clock, activityRepository = repo,
            resync = { resyncs++ }, calendarSource = calendar,
            selectedCalendarIds = kotlinx.coroutines.flow.flow { throw java.io.IOException("datastore") },
            scopeOverride = backgroundScope,
        )
        vm.setTitle("Leer")
        vm.save()
        vm.state.first { it.saved }
        assertTrue(repo.observeAll().first().any { it.title == "Leer" })
    }

    private val realScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Espera en reloj real (Room corre en hilos reales, no en el planificador de la prueba). */
    private suspend fun awaitReal(cond: suspend () -> Boolean) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        kotlinx.coroutines.withTimeout(10_000) { while (!cond()) kotlinx.coroutines.delay(25) }
    }

    @Test fun copyWithoutPermissionSavesAndExposesStatus() = runTest {
        val writer = app.brujula.calendar.FakeGoogleCalendarWriter(permission = false)
        val vm = ActivityFormViewModel(
            activityId = null, prefillStart = null, clock = clock, activityRepository = repo,
            resync = { resyncs++ }, calendarSource = calendar, selectedCalendarIds = flowOf(setOf(1L)),
            googleCopy = app.brujula.calendar.GoogleCopySync(writer, repo), noticeMillis = 10, googleScope = realScope,
            scopeOverride = backgroundScope,
        )
        vm.setTitle("Dentista"); vm.setType(ActivityType.APPOINTMENT)
        vm.save()
        val s = vm.state.first { it.saved }
        assertEquals(app.brujula.calendar.GoogleCopyStatus.NO_PERMISSION, s.googleStatus)
        assertEquals(0, writer.inserts)
        assertTrue(repo.observeAll().first().any { it.title == "Dentista" })
    }

    @Test fun copyWithPermissionStoresLinkAndDeleteRemovesCopy() = runTest {
        val writer = app.brujula.calendar.FakeGoogleCalendarWriter()
        fun vm(id: Long? = null) = ActivityFormViewModel(
            activityId = id, prefillStart = null, clock = clock, activityRepository = repo,
            resync = { resyncs++ }, calendarSource = calendar, selectedCalendarIds = flowOf(setOf(1L)),
            googleCopy = app.brujula.calendar.GoogleCopySync(writer, repo), noticeMillis = 10, googleScope = realScope,
            scopeOverride = backgroundScope,
        )
        val v = vm()
        v.setTitle("Dentista"); v.setType(ActivityType.APPOINTMENT); v.save()
        v.state.first { it.saved }
        val saved = repo.observeAll().first().first { it.title == "Dentista" }
        assertEquals(100L, saved.googleEventId)
        val e = vm(saved.id)
        e.state.first { it.ready }
        e.delete()
        e.state.first { it.saved }
        awaitReal { writer.events.isEmpty() }
        assertTrue(writer.events.isEmpty())
    }

    @Test fun slowGoogleWriteClosesFormAndFinishesInBackground() = runTest {
        val writer = app.brujula.calendar.FakeGoogleCalendarWriter().apply { insertDelayMs = 1_500 }
        val vm = ActivityFormViewModel(
            activityId = null, prefillStart = null, clock = clock, activityRepository = repo,
            resync = { resyncs++ }, calendarSource = calendar, selectedCalendarIds = flowOf(setOf(1L)),
            googleCopy = app.brujula.calendar.GoogleCopySync(writer, repo), noticeMillis = 10,
            googleScope = realScope, googleTimeoutMillis = 200, scopeOverride = backgroundScope,
        )
        vm.setTitle("Dentista"); vm.setType(ActivityType.APPOINTMENT); vm.save()
        val s = vm.state.first { it.saved }
        assertNull(s.googleNotice)
        assertNull(repo.observeAll().first().first { it.title == "Dentista" }.googleEventId)
        awaitReal { repo.observeAll().first().first { it.title == "Dentista" }.googleEventId == 100L }
        assertEquals(1, writer.inserts)
    }

    @Test fun linkedExternalEventIsNotAConflict() = runTest {
        val id = repo.upsert(
            Activity(title = "Copia", type = ActivityType.APPOINTMENT, date = tomorrow, start = LocalTime.of(9, 0), durationMin = 30),
        )
        repo.setGoogleEventId(id, 9L)
        val span = TimeSpan(tomorrow.atTime(14, 0), tomorrow.atTime(15, 0))
        calendar.eventList = listOf(
            ExternalEvent(9L, 0, 1, "Personal", "Google", "Copia", span, false, null),
            ExternalEvent(10L, 0, 1, "Personal", "Google", "Ajena", span, false, null),
        )
        val vm = newVm(prefill = tomorrow.atTime(14, 0))
        assertEquals("Ajena", vm.state.first { it.externalConflict?.title == "Ajena" }.externalConflict?.title)
    }

    private fun TestScope.vmNoPermission(selected: kotlinx.coroutines.flow.Flow<Set<Long>?>, writer: app.brujula.calendar.FakeGoogleCalendarWriter, sync: app.brujula.calendar.GoogleCopySync) =
        ActivityFormViewModel(
            activityId = null, prefillStart = null, clock = clock, activityRepository = repo,
            resync = { resyncs++ }, calendarSource = calendar, selectedCalendarIds = selected,
            googleCopy = sync, noticeMillis = 5_000, googleScope = realScope, scopeOverride = backgroundScope,
        )

    @Test fun noticeIsShownAndHeldOnlyWhenCalendarsWereConnected() = runTest {
        val writer = app.brujula.calendar.FakeGoogleCalendarWriter(permission = false)
        val vm = vmNoPermission(flowOf(setOf(1L)), writer, app.brujula.calendar.GoogleCopySync(writer, repo))
        vm.setTitle("Dentista"); vm.setType(ActivityType.APPOINTMENT)
        vm.save()
        val s = vm.state.first { it.googleStatus == app.brujula.calendar.GoogleCopyStatus.NO_PERMISSION }
        assertEquals("No se pudo copiar: falta el permiso de calendario. Actívalo en Tu rutina.", s.googleNotice)
        vm.state.first { it.saved }
        assertTrue(testScheduler.currentTime >= 5_000)
    }

    @Test fun noNoticeAndNoHoldWhenCalendarsWereNeverConnected() = runTest {
        val writer = app.brujula.calendar.FakeGoogleCalendarWriter(permission = false)
        val vm = vmNoPermission(flowOf(null), writer, app.brujula.calendar.GoogleCopySync(writer, repo))
        vm.setTitle("Dentista"); vm.setType(ActivityType.APPOINTMENT)
        vm.save()
        val s = vm.state.first { it.saved }
        assertNull(s.googleNotice)
        assertTrue(testScheduler.currentTime < 5_000)
        assertTrue(repo.observeAll().first().any { it.title == "Dentista" })
    }
}
