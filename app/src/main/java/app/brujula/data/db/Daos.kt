package app.brujula.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activities ORDER BY date, start, id")
    fun observeAll(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun get(id: Long): ActivityEntity?

    @Query("SELECT COUNT(*) FROM activities")
    suspend fun count(): Int

    @Upsert suspend fun upsert(e: ActivityEntity): Long

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
}
