package app.brujula.calendar

import android.util.Log
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import app.brujula.activities.Repeat
import kotlinx.coroutines.flow.first

enum class GoogleCopyStatus { NOT_REQUESTED, COPIED, NO_PERMISSION, NO_GOOGLE_ACCOUNT, FAILED }

/**
 * Mantiene la copia en Google de las actividades con "Copiar a Google". Nunca lanza: devuelve un estado.
 * Debe usarse como singleton: el [Mutex] serializa las sincronizaciones (basta uno para toda la app personal)
 * y evita insertar dos veces la misma actividad. Las escrituras que cambian el vinculo no se cancelan a medias.
 */
class GoogleCopySync(
    private val writer: GoogleCalendarWriter,
    private val activityRepository: ActivityRepository,
    /** Interruptor global "Copiar citas a Google Calendar"; activo por defecto. */
    private val copyEnabled: suspend () -> Boolean = { true },
) {
    private val mutex = Mutex()

    /** [a] ya esta guardada en Brujula; el id de evento vigente se lee del repositorio. */
    suspend fun afterSave(a: Activity): GoogleCopyStatus = mutex.withLock { syncLocked(a, activityRepository.safeGet(a.id)) }

    /**
     * Para [reconcile]: con el cerrojo tomado relee la fila y solo copia si sigue pendiente (existe, con copia activa y
     * sin vinculo); asi un borrado o un "copia apagada" posteriores a la instantanea no producen una copia huerfana.
     */
    internal suspend fun copyIfStillPending(id: Long): GoogleCopyStatus = mutex.withLock {
        val stored = activityRepository.safeGet(id) ?: return@withLock GoogleCopyStatus.NOT_REQUESTED
        if (!stored.copyToGoogle || stored.googleEventId != null) return@withLock GoogleCopyStatus.NOT_REQUESTED
        syncLocked(stored, stored)
    }

    /**
     * Para el diario: relee la fila con el cerrojo tomado y la copia según cómo está ahora; si ya no existe no hay nada que
     * copiar. Un error al leer cuenta como FAILED (la entrada se queda para reintentar).
     */
    internal suspend fun afterSaveIfExists(id: Long): GoogleCopyStatus = mutex.withLock {
        val stored = try {
            activityRepository.get(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("GoogleCopySync", "No se pudo leer la actividad", e)
            return@withLock GoogleCopyStatus.FAILED
        } ?: return@withLock GoogleCopyStatus.NOT_REQUESTED
        syncLocked(stored, stored)
    }

    /**
     * Para el diario: con el cerrojo tomado, borra las copias de [id] solo si la fila sigue sin existir (deshacer pudo
     * volver a crearla con el mismo id y una copia nueva). `true` = terminado; `false` = reintentar (sin permiso o error).
     */
    internal suspend fun afterDeleteIfGone(id: Long, googleEventId: Long?): Boolean = mutex.withLock {
        val row = try {
            activityRepository.get(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("GoogleCopySync", "No se pudo leer la actividad", e)
            return@withLock false
        }
        if (row != null) return@withLock true
        deleteLocked(id, googleEventId)
    }

    /** Hay permiso de escritura (un error al preguntar cuenta como que no). */
    internal fun canWrite(): Boolean = try {
        writer.hasWritePermission()
    } catch (e: Exception) {
        false
    }

    private suspend fun ActivityRepository.safeGet(id: Long): Activity? = try {
        get(id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("GoogleCopySync", "No se pudo leer la actividad", e)
        null
    }

    private suspend fun syncLocked(a: Activity, stored: Activity?): GoogleCopyStatus {
        // Sin fila guardada (borrada): nunca se escribe en Google; de la copia se encarga afterDelete.
        if (stored == null) return GoogleCopyStatus.NOT_REQUESTED
        return try {
            val eventId = stored.googleEventId
            when {
                !a.copyToGoogle -> removeCopy(a.id, eventId)
                !copyEnabled() -> GoogleCopyStatus.NOT_REQUESTED
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
        if (!writer.hasWritePermission()) {
            return@withContext if (eventId != null) GoogleCopyStatus.NO_PERMISSION else GoogleCopyStatus.NOT_REQUESTED
        }
        // Si se perdio el vinculo, tambien se borran las copias que aun existan en Google.
        val ids = (listOfNotNull(eventId) + writer.findCopies(activityId)).toSet()
        ids.forEach { writer.delete(it) }
        if (eventId != null) activityRepository.setGoogleEventId(activityId, null)
        GoogleCopyStatus.NOT_REQUESTED
    }

    /**
     * Crea las copias que faltan: actividades con "Copiar a Google" y sin vinculo, recurrentes o de hoy en adelante.
     * Idempotente (reutiliza [afterSave]); no hace nada sin permiso de escritura, sin cuenta Google o con el interruptor apagado.
     */
    suspend fun reconcile(today: LocalDate) {
        try {
            if (!copyEnabled() || !writer.hasWritePermission() || writer.primaryGoogleCalendarId() == null) return
            activityRepository.observeAll().first()
                .filter { it.copyToGoogle && it.googleEventId == null && (it.repeat != Repeat.NONE || it.date >= today) }
                .forEach { copyIfStillPending(it.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("GoogleCopySync", "No se pudo completar la sincronizacion pendiente", e)
        }
    }

    /**
     * [a] es la actividad tal como estaba antes de borrarla (con su googleEventId). Devuelve `false` si no se pudo
     * intentar (sin permiso o con error), para que quien lo pidió pueda reintentar después.
     */
    suspend fun afterDelete(a: Activity): Boolean = mutex.withLock { deleteLocked(a.id, a.googleEventId) }

    private suspend fun deleteLocked(activityId: Long, googleEventId: Long?): Boolean {
        return try {
            if (!writer.hasWritePermission()) {
                Log.w("GoogleCopySync", "Sin permiso de escritura: la copia de Google de la actividad $activityId no se pudo borrar")
                return false
            }
            withContext(NonCancellable) {
                val ids = (listOfNotNull(googleEventId) + writer.findCopies(activityId)).toSet()
                ids.forEach { writer.delete(it) }
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("GoogleCopySync", "No se pudo borrar la copia de Google", e)
            false
        }
    }
}
