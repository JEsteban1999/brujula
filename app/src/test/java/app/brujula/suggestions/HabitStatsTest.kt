package app.brujula.suggestions

import app.brujula.suggestions.SuggestionFixtures.balm
import app.brujula.suggestions.SuggestionFixtures.done
import app.brujula.suggestions.SuggestionFixtures.skip
import app.brujula.suggestions.SuggestionFixtures.today
import app.brujula.suggestions.SuggestionFixtures.water
import app.brujula.routine.Routine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HabitStatsTest {
    private fun day(d: Int): LocalDate = LocalDate.of(2026, 11, d)

    private val logs = listOf(
        done(3, day(4).atTime(10, 0)), // fuera: hace 15 días
        done(3, day(5).atTime(10, 0)), // el más viejo de los 14
        done(3, day(18).atTime(9, 10)),
        done(3, day(18).atTime(15, 20)),
        done(3, day(18).atTime(15, 50)),
        done(3, day(19).atTime(0, 20)), // día lógico 18
        done(3, day(19).atTime(9, 0)), // hoy: no cuenta
        skip(3, day(17).atTime(15, 0)),
    )

    @Test fun dailyAndHourlyCounts() {
        val s = habitStats(today, listOf(water), logs, Routine()).single()
        assertEquals("H3", s.ref)
        assertEquals(water, s.habit)
        assertEquals(14, s.daily.size)
        assertEquals(listOf(1) + List(12) { 0 } + 4, s.daily)
        assertEquals(mapOf(0 to 1, 9 to 1, 10 to 1, 15 to 2), s.doneByHour)
    }

    @Test fun skippedSeparated() {
        val s = habitStats(today, listOf(water), logs, Routine()).single()
        assertEquals(mapOf(15 to 1), s.skippedByHour)
        assertEquals("El saltado no cuenta como hecho", 0, s.daily[12])
        assertEquals("Los hechos de las 15 h no incluyen el saltado", 2, s.doneByHour[15])
    }

    @Test fun remindersByHourFromSchedule() {
        val s = habitStats(today, listOf(balm), emptyList(), Routine()).single()
        // 5 al 18 de noviembre: 5 días de descanso (sábados 7 y 14, domingos 8 y 15, festivo del lunes 16): las 9:00 pasan a las 10:00.
        assertEquals(mapOf(9 to 9, 10 to 5, 13 to 14, 17 to 14, 21 to 14), s.remindersByHour)
        assertEquals(List(14) { 0 }, s.daily)
        assertTrue(s.doneByHour.isEmpty() && s.skippedByHour.isEmpty())
    }

    @Test fun inactiveHabitsLeftOut() {
        val stats = habitStats(today, listOf(water.copy(active = false), balm), logs, Routine())
        assertEquals(listOf("H4"), stats.map { it.ref })
    }
}
