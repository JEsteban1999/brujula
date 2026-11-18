package app.brujula.activities

import app.brujula.data.db.ActivityCompletionEntity
import app.brujula.data.db.ActivityDao
import app.brujula.data.db.toEntity
import app.brujula.data.db.toModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class ActivityRepository(private val dao: ActivityDao) {
    fun observeAll(): Flow<List<Activity>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeDoneKeys(): Flow<Set<Pair<Long, LocalDate>>> =
        dao.observeCompletions().map { list -> list.map { it.activityId to it.day }.toSet() }

    suspend fun get(id: Long): Activity? = dao.get(id)?.toModel()

    suspend fun count(): Int = dao.count()

    suspend fun upsert(a: Activity): Long {
        val rowId = dao.upsert(a.toEntity())
        return if (a.id != 0L) a.id else rowId
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun setDone(id: Long, day: LocalDate, done: Boolean) {
        if (done) dao.insertCompletion(ActivityCompletionEntity(id, day)) else dao.deleteCompletion(id, day)
    }
}
