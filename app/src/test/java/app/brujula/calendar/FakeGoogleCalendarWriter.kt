package app.brujula.calendar

class FakeGoogleCalendarWriter(
    var permission: Boolean = true,
    var calendarId: Long? = 7L,
) : GoogleCalendarWriter {
    val events = mutableMapOf<Long, GoogleEventDraft>()
    var nextId = 100L
    var failInsert = false
    var insertDelayMs = 0L
    var inserts = 0
    var updates = 0
    var deletes = 0

    override fun hasWritePermission() = permission
    override suspend fun primaryGoogleCalendarId() = calendarId
    override suspend fun insert(calendarId: Long, e: GoogleEventDraft): Long? {
        if (failInsert) throw IllegalStateException("boom")
        if (insertDelayMs > 0) kotlinx.coroutines.delay(insertDelayMs)
        inserts++
        val id = nextId++
        events[id] = e
        return id
    }
    override suspend fun update(eventId: Long, e: GoogleEventDraft): Boolean {
        updates++
        if (eventId !in events) return false
        events[eventId] = e
        return true
    }
    override suspend fun delete(eventId: Long): Boolean {
        deletes++
        return events.remove(eventId) != null
    }
    override suspend fun findCopies(activityId: Long): List<Long> =
        events.filterValues { it.activityId == activityId }.keys.toList()
}
