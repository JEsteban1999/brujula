package app.brujula.activities

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ActivityTest {
    private val wed = LocalDate.of(2026, 11, 18)
    private fun act(repeat: Repeat) = Activity(1, "X", ActivityType.WORK, wed, LocalTime.of(9, 0), 60, repeat)
    @Test fun repeatRules() {
        assertTrue(act(Repeat.NONE).occursOn(wed)); assertFalse(act(Repeat.NONE).occursOn(wed.plusDays(1)))
        assertTrue(act(Repeat.DAILY).occursOn(wed.plusDays(4)))
        assertFalse(act(Repeat.WEEKDAYS).occursOn(LocalDate.of(2026, 11, 21))) // sábado
        assertTrue(act(Repeat.WEEKLY).occursOn(wed.plusDays(7))); assertFalse(act(Repeat.WEEKLY).occursOn(wed.plusDays(6)))
        assertFalse(act(Repeat.DAILY).occursOn(wed.minusDays(1))) // no antes de su fecha
    }
    @Test fun occurrenceCrossingMidnightStaysOnItsDay() {
        val late = Activity(2, "Película", ActivityType.PERSONAL, wed, LocalTime.of(23, 30), 60)
        val occ = occurrencesOn(wed, listOf(late), emptySet()).single()
        assertEquals(wed.plusDays(1).atTime(0, 30), occ.span.end)
    }
    @Test fun doneIsPerDay() {
        val occ = occurrencesOn(wed.plusDays(1), listOf(act(Repeat.DAILY)), setOf(1L to wed)).single()
        assertFalse(occ.done)
    }
    @Test fun appointmentCopiesToGoogleByDefault() {
        assertTrue(Activity(title = "Dentista", type = ActivityType.APPOINTMENT, date = wed, start = LocalTime.NOON, durationMin = 30).copyToGoogle)
        assertFalse(Activity(title = "Leer", type = ActivityType.PERSONAL, date = wed, start = LocalTime.NOON, durationMin = 30).copyToGoogle)
    }
    @Test fun occurrencesAreSortedByStartAndMarkedDone() {
        val a = Activity(1, "B", ActivityType.PERSONAL, wed, LocalTime.of(15, 0), 30)
        val b = Activity(2, "A", ActivityType.PERSONAL, wed, LocalTime.of(8, 0), 30)
        val occs = occurrencesOn(wed, listOf(a, b), setOf(2L to wed))
        assertEquals(listOf(2L, 1L), occs.map { it.activity.id })
        assertTrue(occs[0].done); assertFalse(occs[1].done)
    }
    @Test fun labels() {
        assertEquals(listOf("Trabajo", "Ejercicio", "Cita", "Personal"), ActivityType.values().map { it.labelEs })
        assertEquals(listOf("No se repite", "Todos los días", "Lunes a viernes", "Cada semana"), Repeat.values().map { it.labelEs })
    }
}
