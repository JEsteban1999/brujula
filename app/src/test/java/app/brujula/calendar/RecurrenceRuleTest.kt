package app.brujula.calendar

import android.provider.CalendarContract.Events
import app.brujula.activities.Activity
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(application = app.brujula.TestBrujulaApp::class)
class RecurrenceRuleTest {
    private val wednesday = LocalDate.of(2026, 11, 18)

    @Test fun rulesForAWednesday() {
        assertNull(rruleFor(Repeat.NONE, wednesday))
        assertEquals("FREQ=DAILY", rruleFor(Repeat.DAILY, wednesday))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", rruleFor(Repeat.WEEKDAYS, wednesday))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", rruleFor(Repeat.WORKDAYS, wednesday))
        assertEquals("FREQ=WEEKLY;BYDAY=WE", rruleFor(Repeat.WEEKLY, wednesday))
    }

    @Test fun draftForActivity() {
        val a = Activity(
            id = 5, title = "Dentista", type = ActivityType.APPOINTMENT, date = wednesday,
            start = LocalTime.of(23, 30), durationMin = 60, repeat = Repeat.WEEKLY,
        )
        val d = draftFor(a)
        assertEquals("Dentista", d.title)
        assertEquals(LocalDateTime.of(2026, 11, 18, 23, 30), d.start)
        assertEquals(LocalDateTime.of(2026, 11, 19, 0, 30), d.end)
        assertEquals("FREQ=WEEKLY;BYDAY=WE", d.rrule)
        assertEquals(5L, d.activityId)
    }

    @Test fun valuesForSingleEvent() {
        val zone = ZoneId.of("America/Bogota")
        val d = GoogleEventDraft("Cita", LocalDateTime.of(2026, 11, 18, 10, 0), LocalDateTime.of(2026, 11, 18, 11, 0), null, 9)
        val v = eventValues(7L, d, zone)
        assertEquals(7L, v.getAsLong(Events.CALENDAR_ID))
        assertEquals("Cita", v.getAsString(Events.TITLE))
        assertEquals(LocalDateTime.of(2026, 11, 18, 10, 0).atZone(zone).toInstant().toEpochMilli(), v.getAsLong(Events.DTSTART))
        assertEquals(LocalDateTime.of(2026, 11, 18, 11, 0).atZone(zone).toInstant().toEpochMilli(), v.getAsLong(Events.DTEND))
        assertNull(v.get(Events.RRULE))
        assertNull(v.get(Events.DURATION))
        assertEquals("America/Bogota", v.getAsString(Events.EVENT_TIMEZONE))
        assertEquals(0, v.getAsInteger(Events.HAS_ALARM))
        assertEquals("app.brujula", v.getAsString(Events.CUSTOM_APP_PACKAGE))
        assertEquals("brujula://activity/9", v.getAsString(Events.CUSTOM_APP_URI))
    }

    @Test fun valuesForRecurringEventUseDurationNotEnd() {
        val zone = ZoneId.of("America/Bogota")
        val d = GoogleEventDraft("Gym", LocalDateTime.of(2026, 11, 18, 6, 0), LocalDateTime.of(2026, 11, 18, 7, 0), "FREQ=DAILY", 3)
        val v = eventValues(7L, d, zone)
        assertEquals("FREQ=DAILY", v.getAsString(Events.RRULE))
        assertEquals("P3600S", v.getAsString(Events.DURATION))
        assertNull(v.get(Events.DTEND))
    }
}
