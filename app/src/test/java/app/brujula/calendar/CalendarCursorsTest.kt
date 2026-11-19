package app.brujula.calendar

import android.database.MatrixCursor
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import app.brujula.core.time.TimeSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(application = app.brujula.TestBrujulaApp::class)
class CalendarCursorsTest {
    private val bogota = ZoneId.of("America/Bogota")

    private fun cursor(vararg rows: Array<Any?>) =
        MatrixCursor(INSTANCE_PROJECTION).also { m -> rows.forEach { m.addRow(it) } }

    private fun millis(d: LocalDateTime, z: ZoneId) = d.atZone(z).toInstant().toEpochMilli()

    private fun row(
        id: Long, begin: Long, end: Long, title: String, allDay: Int = 0, status: Int? = null,
        self: Int? = null, pkg: String? = null,
    ): Array<Any?> = arrayOf(id, begin, end, title, allDay, 7L, "Trabajo", "ana@x.com", status, self, pkg, 0xFF336699.toInt())

    @Test fun mapsTimedAndAllDayInstances() {
        val timed = row(1, millis(LocalDateTime.of(2026, 11, 18, 14, 0), bogota), millis(LocalDateTime.of(2026, 11, 18, 15, 30), bogota), "Reunión")
        val d = LocalDate.of(2026, 11, 19)
        val allDay = row(2, d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), d.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), "Festivo", allDay = 1)
        val r = mapInstances(cursor(timed, allDay), bogota) { "Google" }
        assertEquals(2, r.size)
        assertEquals(TimeSpan(LocalDateTime.of(2026, 11, 18, 14, 0), LocalDateTime.of(2026, 11, 18, 15, 30)), r[0].span)
        assertFalse(r[0].allDay)
        assertEquals("Google", r[0].source)
        assertEquals("ana@x.com", r[0].organizer)
        assertEquals(0xFF336699.toInt(), r[0].calendarColor)
        assertTrue(r[1].allDay)
        assertEquals(TimeSpan(d.atStartOfDay(), d.plusDays(1).atStartOfDay()), r[1].span)
        assertEquals("2:${r[1].instanceBegin}", r[1].key)
    }

    @Test fun excludesBrujulaCopiesCancelledAndDeclined() {
        val b = millis(LocalDateTime.of(2026, 11, 18, 9, 0), bogota)
        val rows = arrayOf(
            row(1, b, b + 3_600_000, "copia", pkg = "app.brujula"),
            row(2, b, b + 3_600_000, "cancelado", status = Events.STATUS_CANCELED),
            row(3, b, b + 3_600_000, "rechazado", self = Attendees.ATTENDEE_STATUS_DECLINED),
            row(4, b, b + 3_600_000, "bueno", status = Events.STATUS_CONFIRMED, self = Attendees.ATTENDEE_STATUS_ACCEPTED),
        )
        assertEquals(listOf("bueno"), mapInstances(cursor(*rows), bogota).map { it.title })
    }

    @Test fun mapsCalendarsWithPrimaryGoogleAndOutlook() {
        val m = MatrixCursor(CALENDAR_PROJECTION)
        m.addRow(arrayOf<Any?>(1L, "Personal", "yo@gmail.com", "com.google", 0xFF0000, 1, "yo@gmail.com", 1))
        m.addRow(arrayOf<Any?>(2L, "Calendario", "yo@empresa.com", "com.microsoft.office.outlook.USER_ACCOUNT", 0x00FF00, 0, "yo@empresa.com", 1))
        m.addRow(arrayOf<Any?>(3L, "Festivos", "yo@gmail.com", "com.google", 0, 0, "otro@group.v.calendar.google.com", 1))
        val r = mapCalendars(m)
        assertTrue(r[0].isPrimaryGoogle)
        assertEquals("Google", r[0].sourceLabel)
        assertFalse(r[1].isPrimaryGoogle)
        assertEquals("Outlook", r[1].sourceLabel)
        assertFalse(r[2].isPrimaryGoogle)
    }

    @Test fun otherAccountUsesAccountNameAsLabel() {
        assertEquals("yo@x.org", DeviceCalendar(1, "c", "yo@x.org", "com.other", 0, false).sourceLabel)
    }
}
