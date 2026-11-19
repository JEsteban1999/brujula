package app.brujula.activities

import app.brujula.core.time.TimeSpan
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class FreeSlotsTest {
    private val d = LocalDate.of(2026, 11, 21)
    private fun at(h: Int, m: Int = 0): LocalDateTime = d.atTime(h, m)
    private val window = TimeSpan(at(10), d.plusDays(1).atTime(1, 0))

    @Test fun emptyDayIsOneSlot() = assertEquals(listOf(window), freeSlots(window, emptyList()))

    @Test fun slotsAroundBusyBlock() {
        val r = freeSlots(window, listOf(TimeSpan(at(12), at(13))))
        assertEquals(listOf(TimeSpan(at(10), at(12)), TimeSpan(at(13), window.end)), r)
    }

    @Test fun gapsShorterThanMinimumAreDropped() {
        val r = freeSlots(window, listOf(TimeSpan(at(10, 20), at(12)), TimeSpan(at(12, 20), at(23))))
        assertEquals(listOf(TimeSpan(at(23), window.end)), r)
    }

    @Test fun overlappingAndUnsortedBusyAreMerged() {
        val r = freeSlots(window, listOf(TimeSpan(at(14), at(16)), TimeSpan(at(10), at(15))))
        assertEquals(listOf(TimeSpan(at(16), window.end)), r)
    }

    @Test fun busyBeforeWindowAndPastEndAreClipped() {
        val r = freeSlots(window, listOf(TimeSpan(at(8), at(11)), TimeSpan(at(23), d.plusDays(1).atTime(3, 0))))
        assertEquals(listOf(TimeSpan(at(11), at(23))), r)
    }

    @Test fun customMinimum() {
        val busy = listOf(TimeSpan(at(10, 20), at(23, 45)))
        assertEquals(listOf(TimeSpan(at(23, 45), window.end)), freeSlots(window, busy, 30))
        assertEquals(emptyList<TimeSpan>(), freeSlots(window, busy, 80))
        assertEquals(listOf(TimeSpan(at(10), at(10, 20)), TimeSpan(at(23, 45), window.end)), freeSlots(window, busy, 15))
    }
}
