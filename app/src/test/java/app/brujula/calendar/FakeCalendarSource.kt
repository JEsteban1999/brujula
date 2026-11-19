package app.brujula.calendar

import java.time.LocalDateTime

class FakeCalendarSource(
    var permission: Boolean = true,
    var calendarList: List<DeviceCalendar> = emptyList(),
) : CalendarSource {
    override fun hasReadPermission() = permission
    override suspend fun calendars() = calendarList
    override suspend fun events(calendarIds: Set<Long>, from: LocalDateTime, to: LocalDateTime) = emptyList<ExternalEvent>()
}
