package app.brujula.calendar

import android.util.Log
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class GoogleCopyStatus { NOT_REQUESTED, COPIED, NO_PERMISSION, NO_GOOGLE_ACCOUNT, FAILED }

/**
 * Mantiene la copia en Google de las actividades con "Copiar a Google". Nunca lanza: devuelve un estado.
 * Debe usarse como singleton: el [Mutex] serializa las sincronizaciones (basta uno para toda la app personal)
 * y evita insertar dos veces la misma actividad. Las escrituras que cambian el vinculo no se cancelan a medias.
 */
class GoogleCopySync(
    private val writer: GoogleCalendarWriter,
    private val activityRepository: ActivityRepository,
) {
    private val mutex = Mutex()

    /** [a] ya esta guardada en Brujula; el id de evento vigente se lee del repositorio. */
    suspend fun afterSave(a: Activity): GoogleCopyStatus = mutex.withLock {
        try {
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
    }

    private suspend fun copy(a: Activity, calendarId: Long, eventId: Long?): GoogleCopyStatus = withContext(NonCancellable) {
        val draft = draftFor(a)
        if (eventId != null && writer.update(eventId, draft)) return@withContext GoogleCopyStatus.COPIED
        // Sin vinculo valido: si ya hay una copia (el proceso murio entre insert y guardar el id), se adopta.
        val existing = writer.findCopies(a.id).filter { it != eventId }
        val adopted = existing.firstOrNull { writer.update(it, draft) }
        if (adopted != null) {
            existing.filter { it != adopted }.forEach { writer.delete(it) }
            activityRepository.setGoogleEventId(a.id, adopted)
            return@withContext GoogleCopyStatus.COPIED
        }
        val newId = writer.insert(calendarId, draft) ?: return@withContext GoogleCopyStatus.FAILED
        activityRepository.setGoogleEventId(a.id, newId)
        GoogleCopyStatus.COPIED
    }

    private suspend fun removeCopy(activityId: Long, eventId: Long?): GoogleCopyStatus = withContext(NonCancellable) {
        if (eventId == null) return@withContext GoogleCopyStatus.NOT_REQUESTED
        if (!writer.hasWritePermission()) return@withContext GoogleCopyStatus.NO_PERMISSION
        writer.delete(eventId)
        activityRepository.setGoogleEventId(activityId, null)
        GoogleCopyStatus.NOT_REQUESTED
    }

    /** [a] es la actividad tal como estaba antes de borrarla (con su googleEventId). */
    suspend fun afterDelete(a: Activity) = mutex.withLock {
        try {
            if (!writer.hasWritePermission()) return@withLock
            withContext(NonCancellable) {
                val ids = (listOfNotNull(a.googleEventId) + writer.findCopies(a.id)).toSet()
                ids.forEach { writer.delete(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("GoogleCopySync", "No se pudo borrar la copia de Google", e)
        }
    }
}
