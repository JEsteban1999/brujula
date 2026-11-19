package app.brujula.calendar

import android.database.Cursor
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import app.brujula.core.time.TimeSpan
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

val INSTANCE_PROJECTION = arrayOf(
    Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.TITLE, Instances.ALL_DAY,
    Instances.CALENDAR_ID, Instances.CALENDAR_DISPLAY_NAME, Instances.ORGANIZER, Instances.STATUS,
    Instances.SELF_ATTENDEE_STATUS, Events.CUSTOM_APP_PACKAGE,
)

val CALENDAR_PROJECTION = arrayOf(
    Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
    Calendars.CALENDAR_COLOR, Calendars.IS_PRIMARY, Calendars.OWNER_ACCOUNT, Calendars.VISIBLE,
)

private fun Cursor.idx(col: String) = getColumnIndex(col)
private fun Cursor.str(col: String): String? = idx(col).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }
private fun Cursor.lng(col: String): Long? = idx(col).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) }
private fun Cursor.int(col: String): Int? = idx(col).takeIf { it >= 0 && !isNull(it) }?.let { getInt(it) }

/** Calendarios del cursor de `Calendars`; las filas sin id se ignoran. */
fun mapCalendars(cursor: Cursor): List<DeviceCalendar> {
    val out = mutableListOf<DeviceCalendar>()
    while (cursor.moveToNext()) {
        val id = cursor.lng(Calendars._ID) ?: continue
        val account = cursor.str(Calendars.ACCOUNT_NAME).orEmpty()
        val type = cursor.str(Calendars.ACCOUNT_TYPE).orEmpty()
        val primary = cursor.int(Calendars.IS_PRIMARY)?.let { it == 1 }
            ?: (cursor.str(Calendars.OWNER_ACCOUNT) == account)
        out += DeviceCalendar(
            id = id,
            name = cursor.str(Calendars.CALENDAR_DISPLAY_NAME) ?: account,
            accountName = account,
            accountType = type,
            color = cursor.int(Calendars.CALENDAR_COLOR) ?: 0,
            isPrimaryGoogle = type == "com.google" && primary,
        )
    }
    return out
}

/**
 * Instancias del cursor de `Instances` como eventos locales. Excluye las copias de Brujula, los cancelados y los
 * rechazados. Los de todo el dia vienen en medianoche UTC: se toma la fecha, no el instante.
 */
fun mapInstances(
    cursor: Cursor,
    zone: ZoneId,
    sourceOf: (calendarId: Long) -> String = { "" },
): List<ExternalEvent> {
    val out = mutableListOf<ExternalEvent>()
    while (cursor.moveToNext()) {
        val eventId = cursor.lng(Instances.EVENT_ID) ?: continue
        val begin = cursor.lng(Instances.BEGIN) ?: continue
        val end = cursor.lng(Instances.END) ?: begin
        if (cursor.str(Events.CUSTOM_APP_PACKAGE) == BRUJULA_PACKAGE) continue
        if (cursor.int(Instances.STATUS) == Events.STATUS_CANCELED) continue
        if (cursor.int(Instances.SELF_ATTENDEE_STATUS) == Attendees.ATTENDEE_STATUS_DECLINED) continue
        val allDay = cursor.int(Instances.ALL_DAY) == 1
        val span = if (allDay) {
            val startDate = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
            val endDate = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
                .let { if (it <= startDate) startDate.plusDays(1) else it }
            TimeSpan(startDate.atStartOfDay(), endDate.atStartOfDay())
        } else {
            TimeSpan(localAt(begin, zone), localAt(maxOf(end, begin), zone))
        }
        val calId = cursor.lng(Instances.CALENDAR_ID) ?: 0L
        out += ExternalEvent(
            eventId = eventId,
            instanceBegin = begin,
            calendarId = calId,
            calendarName = cursor.str(Instances.CALENDAR_DISPLAY_NAME).orEmpty(),
            source = sourceOf(calId),
            title = cursor.str(Instances.TITLE)?.takeIf { it.isNotBlank() } ?: "(Sin título)",
            span = span,
            allDay = allDay,
            organizer = cursor.str(Instances.ORGANIZER),
        )
    }
    return out
}

private fun localAt(millis: Long, zone: ZoneId): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
