package app.brujula.planning

import app.brujula.planning.PlanFixtures.FRI
import app.brujula.planning.PlanFixtures.SAT
import app.brujula.planning.PlanFixtures.THU
import app.brujula.routine.Routine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

/** D12: la más temprana entre la hora de acostarse y (levantarse mañana − 8 h), redondeada hacia abajo a 15 min. */
class BedtimeTest {
    private val routine = Routine() // 8:15–00:30 en días laborales, 10:00–01:00 en descanso

    @Test fun thursdayToFridayWithDefaultRoutine() {
        assertEquals(
            BedtimeSuggestion(bedtime = FRI.atTime(0, 15), sleepMinutes = 480, remindAt = THU.atTime(23, 45)),
            suggestBedtime(THU, routine, nextDayFirstStart = null),
        )
    }

    @Test fun fridayToSaturdayKeepsTheUsualBedtime() {
        assertEquals(
            BedtimeSuggestion(bedtime = SAT.atTime(0, 30), sleepMinutes = 570, remindAt = SAT.atTime(0, 0)),
            suggestBedtime(FRI, routine, nextDayFirstStart = null),
        )
    }

    @Test fun fridayWithAnEventAtSevenMovesTheWakeEarlier() {
        assertEquals(
            BedtimeSuggestion(bedtime = FRI.atTime(22, 30), sleepMinutes = 480, remindAt = FRI.atTime(22, 0)),
            suggestBedtime(FRI, routine, nextDayFirstStart = SAT.atTime(7, 0)),
        )
    }

    @Test fun notBeforeRoundsUpTo15() {
        assertEquals(
            BedtimeSuggestion(bedtime = FRI.atTime(1, 15), sleepMinutes = 420, remindAt = FRI.atTime(0, 45)),
            suggestBedtime(THU, routine, nextDayFirstStart = null, notBefore = FRI.atTime(1, 10)),
        )
        // Justo en un cuarto de hora no se corre; un instante antes de la sugerida no cambia nada.
        assertEquals(FRI.atTime(1, 15), suggestBedtime(THU, routine, null, notBefore = FRI.atTime(1, 15)).bedtime)
        assertEquals(FRI.atTime(1, 30), suggestBedtime(THU, routine, null, notBefore = FRI.atTime(1, 15, 30)).bedtime)
        assertEquals(FRI.atTime(0, 15), suggestBedtime(THU, routine, null, notBefore = THU.atTime(23, 0)).bedtime)
    }

    @Test fun eventAtWakePlus30DoesNotMoveTheWake() {
        // Viernes a las 8:45 = levantarse (8:15) + 30 min: no es "antes de", la hora queda igual.
        assertEquals(FRI.atTime(0, 15), suggestBedtime(THU, routine, nextDayFirstStart = FRI.atTime(8, 45)).bedtime)
        assertEquals(FRI.atTime(0, 0), suggestBedtime(THU, routine, nextDayFirstStart = FRI.atTime(8, 30)).bedtime)
    }

    @Test fun earlyWakeIsNeverBeforeFive() {
        val s = suggestBedtime(THU, routine, nextDayFirstStart = FRI.atTime(4, 30))
        assertEquals(THU.atTime(21, 0), s.bedtime)
        assertEquals(480, s.sleepMinutes)
        assertEquals(THU.atTime(20, 30), s.remindAt)
    }

    @Test fun usualBedtimeIsFlooredTo15() {
        val s = suggestBedtime(THU, routine.copy(bed = LocalTime.of(23, 50)), nextDayFirstStart = null)
        assertEquals(THU.atTime(23, 45), s.bedtime)
        assertEquals(510, s.sleepMinutes)
        assertEquals(THU.atTime(23, 15), s.remindAt)
    }

    @Test fun bedtimeBeforeTheNightNoticeStaysOnTheSameCalendarDay() {
        // Se acuesta a las 22:00: dormir y avisar quedan el mismo jueves, antes de las 22:45.
        val s = suggestBedtime(THU, routine.copy(bed = LocalTime.of(22, 0)), nextDayFirstStart = null)
        assertEquals(THU.atTime(22, 0), s.bedtime)
        assertEquals(615, s.sleepMinutes)
        assertEquals(THU.atTime(21, 30), s.remindAt)
    }
}
