package app.brujula.habits

import app.brujula.data.db.HabitDao
import app.brujula.data.db.HabitLogEntity
import app.brujula.data.db.toEntity
import app.brujula.data.db.toModel
import app.brujula.routine.logicalDay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime

class HabitRepository(private val dao: HabitDao) {
    fun observeHabits(): Flow<List<Habit>> = dao.observeHabits().map { list -> list.map { it.toModel() } }

    suspend fun count(): Int = dao.count()

    suspend fun upsert(h: Habit): Long {
        val rowId = dao.upsert(h.toEntity())
        return if (h.id != 0L) h.id else rowId
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /** Cuenta por dia logico (04:00). */
    fun observeCountsFor(day: LocalDate): Flow<Map<Long, Int>> =
        dao.observeCounts(day).map { rows -> rows.associate { it.habitId to it.count } }

    /** false si esa reminderKey ya estaba registrada (hecha o saltada). */
    suspend fun logDone(habitId: Long, at: LocalDateTime, reminderKey: String? = null): Boolean =
        dao.insertLog(
            HabitLogEntity(habitId = habitId, at = at, logicalDay = logicalDay(at), skipped = false, reminderKey = reminderKey),
        ) != -1L

    suspend fun logSkip(habitId: Long, at: LocalDateTime, reminderKey: String) {
        dao.insertLog(
            HabitLogEntity(habitId = habitId, at = at, logicalDay = logicalDay(at), skipped = true, reminderKey = reminderKey),
        )
    }

    suspend fun undoLast(habitId: Long, day: LocalDate) = dao.deleteLastDone(habitId, day)

    fun observeHandledKeys(): Flow<Set<String>> = dao.observeHandledKeys().map { it.toSet() }
}
