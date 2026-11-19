package app.brujula.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId

/** Lee los calendarios del telefono (Google, Outlook...) con CalendarContract. Nunca escribe. */
class DeviceCalendarSource(
    private val context: Context,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : CalendarSource {
    override fun hasReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override suspend fun calendars(): List<DeviceCalendar> {
        if (!hasReadPermission()) return emptyList()
        return withContext(Dispatchers.IO) { queryCalendars() }
    }

    private fun queryCalendars(): List<DeviceCalendar> = try {
        context.contentResolver.query(Calendars.CONTENT_URI, CALENDAR_PROJECTION, null, null, Calendars._ID)
            ?.use { mapCalendars(it) } ?: emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }

    override suspend fun events(calendarIds: Set<Long>, from: LocalDateTime, to: LocalDateTime): List<ExternalEvent> {
        if (calendarIds.isEmpty() || !hasReadPermission()) return emptyList()
        val z = zone()
        return withContext(Dispatchers.IO) {
            try {
                // Un calendario que ya no existe simplemente no devuelve filas: se ignora.
                val sources = queryCalendars().associate { it.id to it.sourceLabel }
                val uri = Instances.CONTENT_URI.buildUpon().also {
                    ContentUris.appendId(it, from.atZone(z).toInstant().toEpochMilli())
                    ContentUris.appendId(it, to.atZone(z).toInstant().toEpochMilli())
                }.build()
                val selection = "${Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",")})"
                context.contentResolver.query(uri, INSTANCE_PROJECTION, selection, null, Instances.BEGIN)
                    ?.use { c -> mapInstances(c, z) { sources[it].orEmpty() } } ?: emptyList()
            } catch (_: SecurityException) {
                emptyList()
            }
        }
    }
}
