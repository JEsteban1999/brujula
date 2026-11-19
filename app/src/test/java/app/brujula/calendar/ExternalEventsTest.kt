package app.brujula.calendar

import app.brujula.core.time.TimeSpan
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ExternalEventsTest {
    private val wed = LocalDate.of(2026, 11, 18)
    private val thu = wed.plusDays(1)

    private fun ev(id: Long, s: LocalDateTime, e: LocalDateTime, allDay: Boolean = false) =
        ExternalEvent(id, 0, 1, "Cal", "Google", "e$id", TimeSpan(s, e), allDay, null)

    private val crossing = ev(1, wed.atTime(23, 0), thu.atTime(1, 0))
    private val early = ev(2, thu.atTime(0, 30), thu.atTime(1, 30))
    private val allDayThu = ev(3, thu.atStartOfDay(), thu.plusDays(1).atStartOfDay(), allDay = true)
    private val all = listOf(crossing, early, allDayThu)

    @Test fun crossingMidnightBelongsToStartDay() {
        assertEquals(listOf(1L, 2L), eventsOnLogicalDay(wed, all, null).map { it.eventId })
        assertEquals(listOf(3L), eventsOnLogicalDay(thu, all, null).map { it.eventId })
    }

    @Test fun allDayOnlyOnItsDate() {
        assertEquals(emptyList<Long>(), eventsOnLogicalDay(wed, listOf(allDayThu), null).map { it.eventId })
        assertEquals(listOf(3L), eventsOnLogicalDay(thu, listOf(allDayThu), null).map { it.eventId })
    }

    @Test fun spillFromPreviousDayCountsWhenItOverlapsAwakeWindow() {
        val lateNight = ev(4, wed.atTime(23, 0), thu.atTime(10, 30)) // empieza ayer, cruza la vigilia del jueves
        val awake = TimeSpan(thu.atTime(8, 15), thu.plusDays(1).atTime(0, 30))
        assertEquals(listOf(4L), eventsOnLogicalDay(thu, listOf(lateNight), awake).map { it.eventId })
        assertEquals(emptyList<Long>(), eventsOnLogicalDay(thu, listOf(crossing), awake).map { it.eventId })
    }

    @Test fun duplicatedInstancesCollapse() {
        assertEquals(1, eventsOnLogicalDay(wed, listOf(crossing, crossing.copy()), null).size)
    }

    @Test fun classificationKeyCollapsesUnicodeSpaces() {
        val a = crossing.copy(title = "Reunión  de equipo")
        val b = crossing.copy(title = "reunion de equipo")
        assertEquals(classificationKey(b), classificationKey(a))
    }

    @Test fun threeDayAllDayEventShowsOnEachDayExceptTheExclusiveEnd() {
        val trip = ev(5, wed.atStartOfDay(), wed.plusDays(3).atStartOfDay(), allDay = true)
        for (d in 0L..2L) assertEquals(listOf(5L), eventsOnLogicalDay(wed.plusDays(d), listOf(trip), null).map { it.eventId })
        assertEquals(emptyList<Long>(), eventsOnLogicalDay(wed.plusDays(3), listOf(trip), null).map { it.eventId })
        assertEquals(emptyList<Long>(), eventsOnLogicalDay(wed.minusDays(1), listOf(trip), null).map { it.eventId })
    }

    @Test fun multiDayTimedEventShowsOnEveryDayItCrossesTheAwakeWindow() {
        val mon = wed.minusDays(2)
        val tue = wed.minusDays(1)
        val trip = ev(6, mon.atTime(9, 0), wed.atTime(18, 0))
        fun awake(d: LocalDate) = TimeSpan(d.atTime(8, 0), d.atTime(23, 0))
        assertEquals(listOf(6L), eventsOnLogicalDay(mon, listOf(trip), awake(mon)).map { it.eventId })
        assertEquals(listOf(6L), eventsOnLogicalDay(tue, listOf(trip), awake(tue)).map { it.eventId })
        assertEquals(listOf(6L), eventsOnLogicalDay(wed, listOf(trip), awake(wed)).map { it.eventId })
        assertEquals(emptyList<Long>(), eventsOnLogicalDay(wed.plusDays(1), listOf(trip), awake(wed.plusDays(1))).map { it.eventId })
    }
}
