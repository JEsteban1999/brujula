package app.brujula.habits

import app.brujula.routine.Routine
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitTimesTest {
    private val d = LocalDate.of(2026, 11, 18)
    private val water = Habit(1, "Agua", HabitIcon.WATER, HabitSchedule.Every(90), 8)

    @Test fun waterEvery90WithinAwakeWindow() {
        val t = water.timesFor(d, Routine())
        assertEquals(d.atTime(9, 45), t.first())
        assertEquals(d.atTime(23, 15), t.last())
        assertEquals(10, t.size)
        assertTrue(t.none { it.toLocalTime() in LocalTime.of(0, 31)..LocalTime.of(8, 14) })
    }

    @Test fun earlyBedtimeCutsReminders() {
        assertEquals(d.atTime(21, 45), water.timesFor(d, Routine(bed = LocalTime.of(22, 0))).last())
    }

    @Test fun balmFixedTimes() {
        val balm = Habit(2, "Bálsamo labial", HabitIcon.LIP_BALM, HabitSchedule.AtTimes(listOf(9, 13, 17, 21).map { LocalTime.of(it, 0) }), 4)
        assertEquals(listOf(9, 13, 17, 21), balm.timesFor(d, Routine()).map { it.hour })
        assertEquals(listOf(10, 13, 17, 21), balm.timesFor(d, Routine(wake = LocalTime.of(10, 0))).map { it.hour })
    }

    @Test fun atTimeAfterMidnightBeforeBedFallsOnNextCalendarDay() {
        val r = Routine(bed = LocalTime.of(0, 30))
        val h = Habit(3, "Tarde", HabitIcon.GENERIC, HabitSchedule.AtTimes(listOf(LocalTime.of(0, 15), LocalTime.of(9, 0), LocalTime.of(2, 0))), 3)
        // 00:15 cae dentro de la vigilia (bed 00:30) del dia siguiente natural; 02:00 esta dormido
        assertEquals(listOf(d.atTime(8, 15), d.atTime(9, 0), d.plusDays(1).atTime(0, 15)), h.timesFor(d, r))
    }

    @Test fun respectsDaysAndActive() {
        val mondayOnly = water.copy(days = setOf(DayOfWeek.MONDAY))
        assertEquals(DayOfWeek.WEDNESDAY, d.dayOfWeek)
        assertTrue(mondayOnly.timesFor(d, Routine()).isEmpty())
        assertTrue(mondayOnly.timesFor(d.minusDays(2), Routine()).isNotEmpty())
        assertTrue(water.copy(active = false).timesFor(d, Routine()).isEmpty())
    }

    private val sat = LocalDate.of(2026, 11, 21)
    private fun balm(vararg h: Int) = Habit(2, "Bálsamo", HabitIcon.LIP_BALM, HabitSchedule.AtTimes(h.map { LocalTime.of(it, 0) }), 4)

    @Test fun restDayBalmShiftsTo1000() {
        assertEquals(listOf(10, 13, 17, 21), balm(9, 13, 17, 21).timesFor(sat, Routine()).map { it.hour })
        val t = balm(9, 10).timesFor(sat, Routine())
        assertEquals(listOf(sat.atTime(10, 0)), t)
    }

    @Test fun restDayWater() {
        val t = water.copy(schedule = HabitSchedule.Every(90)).timesFor(sat, Routine())
        assertEquals(sat.atTime(11, 30), t.first())
        assertEquals(sat.atTime(23, 30), t.last())
        assertEquals(9, t.size)
    }

    private fun work(schedule: HabitSchedule) =
        Habit(9, "Pausa", HabitIcon.EYE, schedule, 4, group = HabitGroup.WELLNESS, window = HabitWindow.WORK)

    @Test fun workEvery30PerBlock() {
        val t = work(HabitSchedule.Every(30)).timesFor(d, Routine())
        val expected = (listOf(8 to 30, 9 to 0, 9 to 30, 10 to 0, 10 to 30, 11 to 0, 11 to 30) +
            listOf(14 to 30, 15 to 0, 15 to 30, 16 to 0, 16 to 30, 17 to 0, 17 to 30)).map { d.atTime(it.first, it.second) }
        assertEquals(14, t.size)
        assertEquals(expected, t)
        assertTrue(work(HabitSchedule.Every(30)).timesFor(sat, Routine()).isEmpty())
    }

    @Test fun workEvery60() {
        assertEquals(listOf(9, 10, 11, 15, 16, 17), work(HabitSchedule.Every(60)).timesFor(d, Routine()).map { it.hour })
    }

    @Test fun workAtTimesKeepsOnlyInsideBlocks() {
        val h = work(HabitSchedule.AtTimes(listOf(LocalTime.of(10, 15), LocalTime.of(16, 15))))
        assertEquals(listOf(d.atTime(10, 15), d.atTime(16, 15)), h.timesFor(d, Routine()))
        assertTrue(h.timesFor(sat, Routine()).isEmpty())
        val out = work(HabitSchedule.AtTimes(listOf(LocalTime.of(10, 15), LocalTime.of(13, 0), LocalTime.of(20, 0))))
        assertEquals(listOf(d.atTime(10, 15)), out.timesFor(d, Routine()))
    }

    @Test fun beforeBed() {
        val h = Habit(10, "Pantallas", HabitIcon.SCREEN_OFF, HabitSchedule.BeforeBed(45), 1, group = HabitGroup.WELLNESS)
        assertEquals(listOf(d.atTime(23, 45)), h.timesFor(d, Routine()))
        assertEquals(listOf(sat.plusDays(1).atTime(0, 15)), h.timesFor(sat, Routine()))
    }
}
