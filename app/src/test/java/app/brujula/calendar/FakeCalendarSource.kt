package app.brujula.calendar

import java.time.LocalDateTime

class FakeCalendarSource(
    var permission: Boolean = true,
    var calendarList: List<DeviceCalendar> = emptyList(),
    var eventList: List<ExternalEvent> = emptyList(),
) : CalendarSource {
    @Volatile var eventQueries = 0
    @Volatile var lastQueryThread: Thread? = null
    override fun hasReadPermission() = permission
    override suspend fun calendars() = calendarList
    override suspend fun events(calendarIds: Set<Long>, from: LocalDateTime, to: LocalDateTime): List<ExternalEvent> {
        eventQueries++
        lastQueryThread = Thread.currentThread()
        return eventList.filter { it.calendarId in calendarIds && it.span.start < to && it.span.end > from }
    }
}
