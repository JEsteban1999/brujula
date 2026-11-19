package app.brujula.planning

import app.brujula.activities.Activity
import app.brujula.activities.ActivityType
import app.brujula.activities.Occurrence
import app.brujula.calendar.ExternalEvent
import app.brujula.core.time.TimeSpan
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class MorningSummaryTest {
    private val thu = LocalDate.of(2026, 11, 19)
    private val sat = LocalDate.of(2026, 11, 21)

    private fun own(title: String, type: ActivityType, day: LocalDate, h: Int, m: Int = 0, minutes: Int = 60) = Occurrence(
        Activity(title = title, type = type, date = day, start = LocalTime.of(h, m), durationMin = minutes),
        day, TimeSpan(day.atTime(h, m), day.atTime(h, m).plusMinutes(minutes.toLong())), done = false,
    )

    private fun ext(title: String, s: LocalDateTime, work: Boolean = false) =
        FixedEvent(ExternalEvent(1, 0, 1, "Trabajo", "Outlook", title, TimeSpan(s, s.plusHours(1)), false, null), countsAsWork = work)

    private fun snap(day: LocalDate, minutes: Int, own: List<Occurrence>, external: List<FixedEvent>, goal: IntRange?) = DaySnapshot(
        day, own.sortedBy { it.span.start }, external, DaySummary(minutes, goal, 3, 8, ExerciseState.NONE),
    )

    @Test fun workdayBody() {
        val s = snap(
            thu, 390,
            own = listOf(
                own("Informe", ActivityType.WORK, thu, 9, minutes = 390),
                own("Ejercicio en casa", ActivityType.EXERCISE, thu, 19, minutes = 30),
                own("Mercado", ActivityType.PERSONAL, thu, 20),
            ),
            external = listOf(ext("Llamada con cliente", thu.atTime(15, 0)), ext("Daily", thu.atTime(9, 0), work = true)),
            goal = 360..420,
        )
        // Solo los dos primeros bloques que no son trabajo, por hora; el externo de trabajo no cuenta como bloque.
        assertEquals("6 h 30 de trabajo \u00b7 Llamada con cliente a las 15:00 \u00b7 Ejercicio en casa a las 19:00", morningBody(s))
    }

    @Test fun restDayWithoutWorkOmitsWork() {
        val s = snap(sat, 0, own = listOf(own("Caminata", ActivityType.EXERCISE, sat, 11)), external = emptyList(), goal = null)
        assertEquals("Caminata a las 11:00", morningBody(s))
        // Un dia de descanso con trabajo si lo menciona.
        assertEquals("1 h de trabajo \u00b7 Caminata a las 11:00", morningBody(s.copy(summary = s.summary.copy(workMinutes = 60))))
    }

    @Test fun otherEdgeCases() {
        // Lo de ayer que cruza la medianoche no es de este dia; un dia sin nada tiene texto propio.
        val spill = own("Cine", ActivityType.PERSONAL, sat.minusDays(1), 23).let { it.copy(span = TimeSpan(sat.atTime(0, 30), sat.atTime(1, 30))) }
        assertEquals("Hoy no tienes nada agendado.", morningBody(snap(sat, 0, listOf(spill), emptyList(), goal = null)))
        assertEquals("0 min de trabajo", morningBody(snap(thu, 0, emptyList(), emptyList(), goal = 360..420)))
    }
}
