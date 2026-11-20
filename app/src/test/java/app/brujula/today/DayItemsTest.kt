package app.brujula.today

import app.brujula.calendar.CalendarSource
import app.brujula.calendar.DeviceCalendar
import app.brujula.calendar.ExternalEvent
import app.brujula.calendar.FakeCalendarSource
import app.brujula.core.time.TimeSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime

class DayItemsTest {
    private val from = LocalDateTime.of(2026, 11, 15, 4, 0)
    private val to = LocalDateTime.of(2026, 11, 23, 4, 0)
    private val event = ExternalEvent(
        1, 0, 7, "Cal", "Google", "Cita",
        TimeSpan(LocalDateTime.of(2026, 11, 18, 9, 0), LocalDateTime.of(2026, 11, 18, 10, 0)), false, null,
    )

    @Test fun returnsEventsInRange() = runTest {
        val source = FakeCalendarSource(eventList = listOf(event))
        assertEquals(listOf(event), source.selectedEventsBetweenOrEmpty(flowOf(setOf(7L)), from, to))
    }

    @Test fun permissionDeniedIsEmpty() = runTest {
        val source = FakeCalendarSource(permission = false, eventList = listOf(event))
        assertTrue(source.selectedEventsBetweenOrEmpty(flowOf(setOf(7L)), from, to).isEmpty())
        assertEquals(0, source.eventQueries)
    }

    @Test fun emptyOrNullIdsIsEmpty() = runTest {
        val source = FakeCalendarSource(eventList = listOf(event))
        assertTrue(source.selectedEventsBetweenOrEmpty(flowOf(emptySet()), from, to).isEmpty())
        assertTrue(source.selectedEventsBetweenOrEmpty(flowOf(null), from, to).isEmpty())
        assertEquals(0, source.eventQueries)
    }

    @Test fun throwingSourceIsEmpty() = runTest {
        val source = object : CalendarSource {
            override fun hasReadPermission() = true
            override suspend fun calendars(): List<DeviceCalendar> = emptyList()
            override suspend fun events(calendarIds: Set<Long>, from: LocalDateTime, to: LocalDateTime): List<ExternalEvent> =
                throw IllegalStateException("proveedor caido")
        }
        assertTrue(source.selectedEventsBetweenOrEmpty(flowOf(setOf(7L)), from, to).isEmpty())
    }

    @Test fun cancellationIsRethrown() = runTest {
        val source = object : CalendarSource {
            override fun hasReadPermission() = true
            override suspend fun calendars(): List<DeviceCalendar> = emptyList()
            override suspend fun events(calendarIds: Set<Long>, from: LocalDateTime, to: LocalDateTime): List<ExternalEvent> =
                throw CancellationException("cancelado")
        }
        try {
            source.selectedEventsBetweenOrEmpty(flowOf(setOf(7L)), from, to)
            fail("debia relanzar la cancelacion")
        } catch (e: CancellationException) {
            assertEquals("cancelado", e.message)
        }
    }
}
