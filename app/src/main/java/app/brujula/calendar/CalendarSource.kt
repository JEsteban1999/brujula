package app.brujula.calendar

import java.time.LocalDateTime

/** Calendarios del telefono, solo lectura. Las pruebas lo sustituyen por uno falso. */
interface CalendarSource {
    fun hasReadPermission(): Boolean
    suspend fun calendars(): List<DeviceCalendar>
    suspend fun events(calendarIds: Set<Long>, from: LocalDateTime, to: LocalDateTime): List<ExternalEvent>
}

/** Sin calendarios: valor por defecto cuando no hay origen de eventos. */
object NoCalendarSource : CalendarSource {
    override fun hasReadPermission() = false
    override suspend fun calendars() = emptyList<DeviceCalendar>()
    override suspend fun events(calendarIds: Set<Long>, from: LocalDateTime, to: LocalDateTime) = emptyList<ExternalEvent>()
}
