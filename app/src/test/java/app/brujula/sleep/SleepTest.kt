package app.brujula.sleep

import app.brujula.routine.Routine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SleepTest {
    private val routine = Routine()
    private val thu = LocalDate.of(2026, 11, 19)
    private val fri = LocalDate.of(2026, 11, 20)
    private val sat = LocalDate.of(2026, 11, 21)

    private fun at(d: LocalDate, h: Int, m: Int) = d.atTime(h, m)

    @Test fun plannedSleepFromRoutine() {
        val span = sleepSpan(thu, routine, null)
        assertEquals(at(fri, 0, 30), span.start)
        assertEquals(at(fri, 8, 15), span.end)
        assertEquals(465, sleepMinutes(thu, routine, null))
    }

    @Test fun fridayNightUsesRestWake() {
        val span = sleepSpan(fri, routine, null)
        assertEquals(at(sat, 0, 30), span.start)
        assertEquals(at(sat, 10, 0), span.end)
        assertEquals(570, sleepMinutes(fri, routine, null))
    }

    @Test fun confirmedBedtimeShortensSleep() {
        assertEquals(405, sleepMinutes(thu, routine, SleepRecord(thu, at(fri, 1, 30), null)))
    }

    @Test fun recordedWakeUsed() {
        assertEquals(420, sleepMinutes(thu, routine, SleepRecord(thu, null, at(fri, 7, 30))))
    }

    @Test fun implausibleValuesFallBack() {
        assertEquals(465, sleepMinutes(thu, routine, SleepRecord(thu, null, at(fri, 0, 10))))
        // el despertar vale solo después de acostarse: con bedAt 01:00, un wakeAt 00:50 cae al despertar planeado
        assertEquals(435, sleepMinutes(thu, routine, SleepRecord(thu, at(fri, 1, 0), at(fri, 0, 50))))
        assertEquals(465, sleepMinutes(thu, routine, SleepRecord(thu, at(thu.minusDays(1), 0, 30), null)))
    }

    @Test fun wakeNightForMorningOpen() {
        assertEquals(thu, wakeNightFor(at(fri, 8, 40), routine))
        assertNull(wakeNightFor(at(fri, 10, 30), routine))
        assertNull(wakeNightFor(at(fri, 2, 0), routine))
        assertEquals(fri, wakeNightFor(at(sat, 11, 0), routine))
    }

    @Test fun wakeWindowIsTwoHoursEachSideOfPlannedWake() {
        assertNull(wakeNightFor(at(fri, 4, 30), routine))
        assertEquals(thu, wakeNightFor(at(fri, 6, 15), routine))
        assertEquals(thu, wakeNightFor(at(fri, 10, 15), routine))
        assertNull(wakeNightFor(at(fri, 10, 16), routine))
        assertEquals(fri, wakeNightFor(at(sat, 8, 0), routine))
        assertNull(wakeNightFor(at(sat, 7, 59), routine))
    }

    @Test fun bedtimeAfterMidnightBelongsToLogicalDay() {
        assertEquals(thu, bedtimeNightFor(at(fri, 0, 40), thu))
        assertNull(bedtimeNightFor(at(fri, 5, 0), thu))
    }
}
