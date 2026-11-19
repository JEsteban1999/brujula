package app.brujula.activities.form

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.core.time.FixedClock
import app.brujula.data.db.BrujulaDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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

    @Test fun noConflictWhenFree() = runTest {
        val vm = newVm(); vm.setDateChoice(DateChoice.TOMORROW); vm.shiftStart(495 + 60) // 20:00, justo al terminar
        vm.state.first { it.start == LocalTime.of(20, 0) }
        yield()
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
        // Con el repo ya cargado, no choca contra sí misma.
        edit.setDuration(45)
        edit.state.first { it.durationMin == 45 }
        yield()
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
}
