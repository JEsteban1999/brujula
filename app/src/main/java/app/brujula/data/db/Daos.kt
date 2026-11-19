package app.brujula.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activities ORDER BY date, start, id")
    fun observeAll(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun get(id: Long): ActivityEntity?

    @Query("SELECT COUNT(*) FROM activities")
    suspend fun count(): Int

    @Upsert suspend fun upsert(e: ActivityEntity): Long

    /**
     * Lectura del vinculo guardado + escritura en una sola transaccion: Room serializa las transacciones de
     * escritura, asi un [setGoogleEventId] concurrente no se pierde entre la lectura y el upsert.
     */
    @Transaction
    suspend fun upsertKeepingGoogleLink(e: ActivityEntity): Long {
        val old = if (e.id != 0L) get(e.id) else null
        val keptLink = if (e.googleEventId == null) old?.googleEventId else e.googleEventId
        val keptOrigin = if (e.origin == "UNKNOWN" && old != null) old.origin else e.origin
        return upsert(e.copy(googleEventId = keptLink, origin = keptOrigin))
    }

    @Query("UPDATE activities SET googleEventId = :eventId WHERE id = :id")
    suspend fun setGoogleEventId(id: Long, eventId: Long?)

    @Query("SELECT googleEventId FROM activities WHERE googleEventId IS NOT NULL")
    fun observeGoogleEventIds(): Flow<List<Long>>

    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM activity_completions")
    fun observeCompletions(): Flow<List<ActivityCompletionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCompletion(e: ActivityCompletionEntity)

    @Query("DELETE FROM activity_completions WHERE activityId = :id AND day = :day")
    suspend fun deleteCompletion(id: Long, day: LocalDate)

    @Query("SELECT EXISTS(SELECT 1 FROM activity_completions WHERE activityId = :id AND day = :day)")
    suspend fun isDone(id: Long, day: LocalDate): Boolean

    @Query("SELECT * FROM activity_overrides")
    fun observeOverrides(): Flow<List<ActivityOverrideEntity>>

    @Query("SELECT * FROM activity_overrides WHERE activityId = :id AND day = :day")
    suspend fun getOverride(id: Long, day: LocalDate): ActivityOverrideEntity?

    @Upsert suspend fun upsertOverride(e: ActivityOverrideEntity)

    @Query("DELETE FROM activity_overrides WHERE activityId = :id AND day = :day")
    suspend fun deleteOverride(id: Long, day: LocalDate)
}

@Dao
interface ProposalDao {
    @Insert suspend fun insert(e: ProposalEntity): Long

    @Query("SELECT * FROM proposals WHERE id = :id")
    suspend fun get(id: Long): ProposalEntity?

    @Query("SELECT * FROM proposals WHERE day = :day AND status = :status ORDER BY id DESC LIMIT 1")
    suspend fun latestByStatus(day: LocalDate, status: String): ProposalEntity?

    @Query("UPDATE proposals SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("UPDATE proposals SET status = 'ACCEPTED', undo = :undo, acceptedAt = :at WHERE id = :id")
    suspend fun markAccepted(id: Long, undo: String?, at: LocalDateTime)

    @Query("SELECT DISTINCT day FROM proposals WHERE status = 'ACCEPTED'")
    fun observeAcceptedDays(): Flow<List<LocalDate>>

    @Query("SELECT id FROM proposals WHERE status = 'ACCEPTED'")
    fun observeAcceptedIds(): Flow<List<Long>>

    @Query("UPDATE proposals SET status = 'SUPERSEDED' WHERE day = :day AND status = 'PENDING'")
    suspend fun supersedePending(day: LocalDate)

    @Query("SELECT DISTINCT day FROM proposals WHERE status = 'ACCEPTED'")
    suspend fun acceptedDays(): List<LocalDate>

    /** Deshacer: la propuesta vuelve a estar pendiente, sin datos para deshacer. */
    @Query("UPDATE proposals SET status = 'PENDING', undo = NULL, acceptedAt = NULL WHERE id = :id")
    suspend fun reopen(id: Long)

    /** Una sola pendiente por día: las anteriores del mismo día pasan a SUPERSEDED en la misma transacción. */
    @Transaction
    suspend fun insertPending(e: ProposalEntity): Long {
        supersedePending(e.day)
        return insert(e.copy(id = 0, status = "PENDING", undo = null, acceptedAt = null))
    }

    /** Vuelve a dejar [id] como la pendiente de su día (las demás pendientes de ese día pasan a SUPERSEDED). */
    @Transaction
    suspend fun reopenAsOnlyPending(id: Long, day: LocalDate) {
        supersedePending(day)
        reopen(id)
    }
}

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY id")
    fun observeHabits(): Flow<List<HabitEntity>>

    @Query("SELECT COUNT(*) FROM habits")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM habits WHERE groupName = 'WELLNESS'")
    suspend fun countWellness(): Int

    @Upsert suspend fun upsert(e: HabitEntity): Long

    @Query("UPDATE habits SET active = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    @Query("DELETE FROM habits WHERE id = :id")
    suspend fun delete(id: Long)

    /** Devuelve -1 si la reminderKey ya existia. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLog(e: HabitLogEntity): Long

    @Query("SELECT habitId, COUNT(*) AS count FROM habit_logs WHERE logicalDay = :day AND skipped = 0 GROUP BY habitId")
    fun observeCounts(day: LocalDate): Flow<List<HabitCountRow>>

    @Query(
        "DELETE FROM habit_logs WHERE id = (SELECT id FROM habit_logs " +
            "WHERE habitId = :habitId AND logicalDay = :day AND skipped = 0 ORDER BY at DESC, id DESC LIMIT 1)",
    )
    suspend fun deleteLastDone(habitId: Long, day: LocalDate)

    @Query("SELECT reminderKey FROM habit_logs WHERE reminderKey IS NOT NULL")
    fun observeHandledKeys(): Flow<List<String>>
}

@Dao
interface WorkClassificationDao {
    @Query("SELECT * FROM work_classifications")
    fun observeAll(): Flow<List<WorkClassificationEntity>>

    @Upsert suspend fun upsert(e: WorkClassificationEntity)

    /** Escritura automatica atomica en una sola sentencia: nunca pisa una fila `USER` (REPLACE solo corre si no hay una). */
    @Query(
        "INSERT OR REPLACE INTO work_classifications(`key`, isWork, source) " +
            "SELECT :key, :isWork, 'AUTO' " +
            "WHERE NOT EXISTS (SELECT 1 FROM work_classifications WHERE `key` = :key AND source = 'USER')",
    )
    suspend fun upsertAuto(key: String, isWork: Boolean)

    @Query("SELECT source FROM work_classifications WHERE `key` = :key")
    suspend fun sourceOf(key: String): String?
}
