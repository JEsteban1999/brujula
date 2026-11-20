package app.brujula.calendar

import java.time.LocalDateTime

open class FakeCalendarSource(
    var permission: Boolean = true,
    var calendarList: List<DeviceCalendar> = emptyList(),
    var eventList: List<ExternalEvent> = emptyList(),
) : CalendarSource {
    @Volatile var eventQueries = 0
    @Volatile var lastQueryThread: Thread? = null
    @Volatile var lastFrom: LocalDateTime? = null
    @Volatile var lastTo: LocalDateTime? = null
    override fun hasReadPermission() = permission
    override suspend fun calendars() = calendarList
    override suspend fun events(calendarIds: Set<Long>, from: LocalDateTime, to: LocalDateTime): List<ExternalEvent> {
        eventQueries++
        lastQueryThread = Thread.currentThread()
        lastFrom = from
        lastTo = to
        return eventList.filter { it.calendarId in calendarIds && it.span.start < to && it.span.end > from }
    }
}
