package app.brujula.calendar

import app.brujula.data.db.WorkClassificationDao
import app.brujula.data.db.WorkClassificationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class WorkClassificationRepository(private val dao: WorkClassificationDao) {
    fun observe(): Flow<Map<String, Boolean>> =
        dao.observeAll().map { list -> list.associate { it.key to it.isWork } }

    /** Accion del usuario: siempre escribe y marca la fila como `USER`. */
    suspend fun set(key: String, isWork: Boolean) = dao.upsert(WorkClassificationEntity(key, isWork, WorkClassificationEntity.SOURCE_USER))

    /** Clasificacion automatica: solo escribe si no hay una marca del usuario (atomico). Las claves vacias nunca se clasifican. */
    suspend fun setAuto(key: String, isWork: Boolean) {
        if (key.isEmpty()) return
        dao.upsertAuto(key, isWork)
    }

    /** Sin marca guardada, o sin titulo (clave vacia), un evento no cuenta como trabajo. */
    fun isWork(e: ExternalEvent, map: Map<String, Boolean>): Boolean {
        val key = classificationKey(e)
        return key.isNotEmpty() && (map[key] ?: false)
    }
}
