package app.brujula.calendar

import android.util.Log
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import kotlinx.coroutines.CancellationException

enum class GoogleCopyStatus { NOT_REQUESTED, COPIED, NO_PERMISSION, NO_GOOGLE_ACCOUNT, FAILED }

/** Mantiene la copia en Google de las actividades con "Copiar a Google". Nunca lanza: devuelve un estado. */
class GoogleCopySync(
    private val writer: GoogleCalendarWriter,
    private val activityRepository: ActivityRepository,
) {
    /** [a] ya esta guardada en Brujula; el id de evento vigente se lee del repositorio. */
    suspend fun afterSave(a: Activity): GoogleCopyStatus = try {
        val stored = activityRepository.get(a.id) ?: a
        val eventId = stored.googleEventId
        when {
            !a.copyToGoogle -> removeCopy(a.id, eventId)
            !writer.hasWritePermission() -> GoogleCopyStatus.NO_PERMISSION
            else -> {
                val calendarId = writer.primaryGoogleCalendarId()
                if (calendarId == null) GoogleCopyStatus.NO_GOOGLE_ACCOUNT else copy(stored.copy(copyToGoogle = true), calendarId, eventId)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("GoogleCopySync", "No se pudo copiar a Google", e)
        GoogleCopyStatus.FAILED
    }

    private suspend fun copy(a: Activity, calendarId: Long, eventId: Long?): GoogleCopyStatus {
        val draft = draftFor(a)
        // Si el evento ya no existe en Google (lo borraron alli), se crea uno nuevo.
        if (eventId != null && writer.update(eventId, draft)) return GoogleCopyStatus.COPIED
        val newId = writer.insert(calendarId, draft) ?: return GoogleCopyStatus.FAILED
        activityRepository.setGoogleEventId(a.id, newId)
        return GoogleCopyStatus.COPIED
    }

    private suspend fun removeCopy(activityId: Long, eventId: Long?): GoogleCopyStatus {
        if (eventId == null) return GoogleCopyStatus.NOT_REQUESTED
        if (!writer.hasWritePermission()) return GoogleCopyStatus.NO_PERMISSION
        writer.delete(eventId)
        activityRepository.setGoogleEventId(activityId, null)
        return GoogleCopyStatus.NOT_REQUESTED
    }

    /** [a] es la actividad tal como estaba antes de borrarla (con su googleEventId). */
    suspend fun afterDelete(a: Activity) {
        val eventId = a.googleEventId ?: return
        try {
            if (writer.hasWritePermission()) writer.delete(eventId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("GoogleCopySync", "No se pudo borrar la copia de Google", e)
        }
    }
}
