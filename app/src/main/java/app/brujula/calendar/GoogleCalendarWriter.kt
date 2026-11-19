package app.brujula.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import app.brujula.activities.Activity
import app.brujula.activities.Repeat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Escribe en el calendario Google principal. Solo toca eventos creados por Brujula. Las pruebas lo sustituyen. */
interface GoogleCalendarWriter {
    fun hasWritePermission(): Boolean
    suspend fun primaryGoogleCalendarId(): Long?
    suspend fun insert(calendarId: Long, e: GoogleEventDraft): Long?
    suspend fun update(eventId: Long, e: GoogleEventDraft): Boolean
    suspend fun delete(eventId: Long): Boolean

    /** Copias de Brujula (no borradas) de esa actividad, para no duplicar si se perdio el vinculo. */
    suspend fun findCopies(activityId: Long): List<Long>
}

data class GoogleEventDraft(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val rrule: String?,
    val activityId: Long,
)

/** Sin escritura: valor por defecto (nunca copia nada). */
object NoGoogleCalendarWriter : GoogleCalendarWriter {
    override fun hasWritePermission() = false
    override suspend fun primaryGoogleCalendarId(): Long? = null
    override suspend fun insert(calendarId: Long, e: GoogleEventDraft): Long? = null
    override suspend fun update(eventId: Long, e: GoogleEventDraft) = false
    override suspend fun delete(eventId: Long) = false
    override suspend fun findCopies(activityId: Long): List<Long> = emptyList()
}

/**
 * Regla de repeticion de Google para una actividad.
 * Limitacion conocida: WORKDAYS se copia como lunes a viernes, porque Google no conoce los festivos
 * colombianos; en festivos la copia aparece en Google aunque en Brujula el dia sea de descanso.
 */
fun rruleFor(repeat: Repeat, date: LocalDate): String? = when (repeat) {
    Repeat.NONE -> null
    Repeat.DAILY -> "FREQ=DAILY"
    Repeat.WEEKDAYS, Repeat.WORKDAYS -> "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
    Repeat.WEEKLY -> "FREQ=WEEKLY;BYDAY=" + when (date.dayOfWeek.value) {
        1 -> "MO"; 2 -> "TU"; 3 -> "WE"; 4 -> "TH"; 5 -> "FR"; 6 -> "SA"; else -> "SU"
    }
}

fun draftFor(a: Activity): GoogleEventDraft {
    val start = a.date.atTime(a.start)
    return GoogleEventDraft(a.title, start, start.plusMinutes(a.durationMin.toLong()), rruleFor(a.repeat, a.date), a.id)
}

/** Valores del evento; con repeticion usa RRULE + DURATION y sin DTEND, como exige el proveedor. */
fun eventValues(calendarId: Long, e: GoogleEventDraft, zone: ZoneId): ContentValues = ContentValues().apply {
    put(Events.CALENDAR_ID, calendarId)
    put(Events.TITLE, e.title)
    put(Events.DTSTART, e.start.atZone(zone).toInstant().toEpochMilli())
    put(Events.EVENT_TIMEZONE, zone.id)
    if (e.rrule != null) {
        put(Events.RRULE, e.rrule)
        put(Events.DURATION, "P${Duration.between(e.start, e.end).seconds}S")
        putNull(Events.DTEND)
    } else {
        put(Events.DTEND, e.end.atZone(zone).toInstant().toEpochMilli())
        putNull(Events.RRULE)
        putNull(Events.DURATION)
    }
    put(Events.HAS_ALARM, 0)
    put(Events.CUSTOM_APP_PACKAGE, BRUJULA_PACKAGE)
    put(Events.CUSTOM_APP_URI, activityUri(e.activityId))
}

fun activityUri(activityId: Long) = "brujula://activity/$activityId"

/** Destino de update/delete: el proveedor rechaza selecciones sobre `events/{id}`, asi que se usa la coleccion. */
class EventTarget(val uri: android.net.Uri, val selection: String, val args: Array<String>)

fun ownEventTarget(eventId: Long) = EventTarget(
    Events.CONTENT_URI,
    "${Events._ID} = ? AND ${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.DELETED} = 0",
    arrayOf(eventId.toString(), BRUJULA_PACKAGE),
)

class DeviceGoogleCalendarWriter(
    private val context: Context,
    private val source: CalendarSource,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : GoogleCalendarWriter {
    override fun hasWritePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override suspend fun primaryGoogleCalendarId(): Long? =
        source.calendars().firstOrNull { it.isPrimaryGoogle }?.id

    override suspend fun insert(calendarId: Long, e: GoogleEventDraft): Long? = withContext(Dispatchers.IO) {
        context.contentResolver.insert(Events.CONTENT_URI, eventValues(calendarId, e, zone()))?.let { ContentUris.parseId(it) }
    }

    /** false si el evento ya no existe, esta borrado o no es de Brujula (nunca se modifica un evento ajeno). */
    override suspend fun update(eventId: Long, e: GoogleEventDraft): Boolean = withContext(Dispatchers.IO) {
        val values = eventValues(0L, e, zone()).apply { remove(Events.CALENDAR_ID) }
        val t = ownEventTarget(eventId)
        context.contentResolver.update(t.uri, values, t.selection, t.args) > 0
    }

    override suspend fun delete(eventId: Long): Boolean = withContext(Dispatchers.IO) {
        val t = ownEventTarget(eventId)
        context.contentResolver.delete(t.uri, t.selection, t.args) > 0
    }

    override suspend fun findCopies(activityId: Long): List<Long> = withContext(Dispatchers.IO) {
        context.contentResolver.query(
            Events.CONTENT_URI, arrayOf(Events._ID),
            "${Events.CUSTOM_APP_URI} = ? AND ${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.DELETED} = 0",
            arrayOf(activityUri(activityId), BRUJULA_PACKAGE), Events._ID,
        )?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } } ?: emptyList()
    }

}
