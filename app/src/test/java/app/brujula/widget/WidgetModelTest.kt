package app.brujula.widget

import app.brujula.activities.Activity
import app.brujula.activities.ActivityType
import app.brujula.activities.Occurrence
import app.brujula.calendar.ExternalEvent
import app.brujula.core.theme.ThemeMode
import app.brujula.core.time.TimeSpan
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitSchedule
import app.brujula.planning.DaySnapshot
import app.brujula.planning.DaySummary
import app.brujula.planning.ExerciseState
import app.brujula.planning.FixedEvent
import app.brujula.routine.Routine
import app.brujula.today.HabitChip
import app.brujula.today.todayHabitChips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WidgetModelTest {
    private val day = LocalDate.of(2026, 11, 18)
    private fun at(h: Int, m: Int = 0) = day.atTime(h, m)
    private val light = Routine(themeMode = ThemeMode.LIGHT)

    private fun own(title: String, start: LocalDateTime, minutes: Long, id: Long = 1) = Occurrence(
        Activity(id = id, title = title, type = ActivityType.PERSONAL, date = day, start = start.toLocalTime(), durationMin = minutes.toInt()),
        day, TimeSpan(start, start.plusMinutes(minutes)), done = false,
    )

    private fun ext(title: String, start: LocalDateTime, minutes: Long) = FixedEvent(
        ExternalEvent(1, 0, 1, "Cal", "google", title, TimeSpan(start, start.plusMinutes(minutes)), false, null), countsAsWork = false,
    )

    private fun snap(own: List<Occurrence> = emptyList(), external: List<FixedEvent> = emptyList()) =
        DaySnapshot(day, own, external, DaySummary(0, null, null, null, ExerciseState.NONE))

    private fun model(now: LocalDateTime, s: DaySnapshot, routine: Routine = light, chips: List<HabitChip> = emptyList()) =
        buildWidgetModel(now, s, chips, routine)

    @Test fun currentAndNextFromTimeline() {
        val s = snap(listOf(own("Trabajo", at(8), 150), own("Ejercicio", at(10, 30), 30, id = 2)))
        val m = model(at(10, 24), s)
        assertEquals("Ahora · hasta 10:30", m.nowLabel)
        assertEquals("Trabajo", m.nowTitle)
        assertEquals("Ejercicio 10:30", m.nextText)
    }

    @Test fun freeUntilNext() {
        val m = model(at(12), snap(listOf(own("Ejercicio", at(15), 30))))
        assertEquals("Ahora · hasta 15:00", m.nowLabel)
        assertEquals("Libre", m.nowTitle)
        assertEquals("Ejercicio 15:00", m.nextText)
    }

    @Test fun nothingLeft() {
        val m = model(at(20), snap(listOf(own("Trabajo", at(8), 60))))
        assertEquals("Ahora", m.nowLabel)
        assertEquals("Libre", m.nowTitle)
        assertEquals("Nada más por hoy", m.nextText)
    }

    @Test fun externalEventsCount() {
        val m = model(at(10, 24), snap(external = listOf(ext("Reunión", at(10), 60), ext("Cierre", at(16), 30))))
        assertEquals("Reunión", m.nowTitle)
        assertEquals("Ahora · hasta 11:00", m.nowLabel)
        assertEquals("Cierre 16:00", m.nextText)
    }

    @Test fun overlappingPicksLatestStart() {
        val s = snap(listOf(own("Trabajo", at(8), 240), own("Cita", at(10), 60, id = 2)))
        assertEquals("Cita", model(at(10, 24), s).nowTitle)
        // Empate de inicio: gana el propio.
        val tie = snap(listOf(own("Propio", at(10), 60)), listOf(ext("Externo", at(10), 60)))
        assertEquals("Propio", model(at(10, 24), tie).nowTitle)
    }

    @Test fun nextRefreshIsEarliestBoundary() {
        // Fin del bloque actual.
        val s = snap(listOf(own("Trabajo", at(8), 150)))
        assertEquals(at(10, 30), model(at(10, 24), s).nextRefresh)
        // Inicio del siguiente.
        assertEquals(at(11, 15), model(at(11, 0), snap(listOf(own("Cita", at(11, 15), 30)))).nextRefresh)
        // Cambio de tema a las 21:00 en AUTO.
        val auto = Routine(themeMode = ThemeMode.AUTO)
        assertEquals(at(21), model(at(20, 30), snap(), auto).nextRefresh)
        // Con tema fijo no hay cambio de tema: tope de 60 min.
        assertEquals(at(13), model(at(12), snap(), light).nextRefresh)
        // A las 03:50 el día lógico sigue siendo el anterior: refresca a las 04:00.
        val late = day.plusDays(1).atTime(3, 50)
        assertEquals(day.plusDays(1).atTime(4, 0), model(late, snap(), light).nextRefresh)
        assertEquals(day.plusDays(1).atTime(4, 0), model(late, snap(), auto).nextRefresh)
        // El instante exacto no cuenta: solo > now.
        assertTrue(model(at(10, 30), s).nextRefresh > at(10, 30))
    }

    @Test fun darkFollowsAppThemeNotSystem() {
        assertTrue(model(at(21, 30), snap(), Routine(themeMode = ThemeMode.AUTO)).dark)
        assertFalse(model(at(23), snap(), Routine(themeMode = ThemeMode.LIGHT)).dark)
        assertTrue(model(at(12), snap(), Routine(themeMode = ThemeMode.DARK)).dark)
    }

    @Test fun chipsMatchToday() {
        val habits = listOf(
            Habit(id = 1, name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8),
            Habit(id = 2, name = "Bálsamo", icon = HabitIcon.LIP_BALM, schedule = HabitSchedule.Every(180), dailyTarget = 4),
            Habit(id = 3, name = "Tercero", icon = HabitIcon.GENERIC, schedule = HabitSchedule.Every(60), dailyTarget = 3),
            Habit(id = 4, name = "Apagado", icon = HabitIcon.GENERIC, schedule = HabitSchedule.Every(60), dailyTarget = 3, active = false),
        )
        val chips = todayHabitChips(habits, mapOf(1L to 3), day)
        assertEquals(listOf(1L, 2L), chips.map { it.habitId })
        assertEquals(3, chips[0].count)
        assertEquals(chips, model(at(12), snap(), light, chips).habits)
    }
}
