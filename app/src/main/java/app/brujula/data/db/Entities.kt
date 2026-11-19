package app.brujula.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.brujula.activities.Activity
import app.brujula.activities.ActivityOrigin
import app.brujula.activities.ActivityType
import app.brujula.activities.OccurrenceOverride
import app.brujula.activities.Repeat
import app.brujula.habits.Habit
import app.brujula.habits.HabitGroup
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitWindow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Entity(tableName = "activities")
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val type: String,
    val date: LocalDate,
    val start: LocalTime,
    val durationMin: Int,
    val repeat: String,
    val remindBeforeMin: Int?,
    val copyToGoogle: Boolean,
    @ColumnInfo(defaultValue = "NULL") val googleEventId: Long? = null,
    @ColumnInfo(defaultValue = "UNKNOWN") val origin: String = "UNKNOWN",
)

@Entity(
    tableName = "activity_completions",
    primaryKeys = ["activityId", "day"],
    foreignKeys = [ForeignKey(ActivityEntity::class, ["id"], ["activityId"], onDelete = ForeignKey.CASCADE)],
)
data class ActivityCompletionEntity(
    val activityId: Long,
    val day: LocalDate,
)

@Entity(
    tableName = "activity_overrides",
    primaryKeys = ["activityId", "day"],
    foreignKeys = [ForeignKey(ActivityEntity::class, ["id"], ["activityId"], onDelete = ForeignKey.CASCADE)],
)
data class ActivityOverrideEntity(
    val activityId: Long,
    val day: LocalDate,
    val start: LocalDateTime?,
    val durationMin: Int?,
    val skipped: Boolean,
)

@Entity(tableName = "proposals", indices = [Index("day"), Index("status")])
data class ProposalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: LocalDate,
    val createdAt: LocalDateTime,
    val status: String,
    val request: String?,
    val summary: String,
    val payload: String,
    val undo: String? = null,
    val acceptedAt: LocalDateTime? = null,
)

@Entity(tableName = "habits")
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String,
    val schedule: String,
    val dailyTarget: Int,
    val days: String,
    val active: Boolean,
    @ColumnInfo(defaultValue = "ESSENTIAL") val groupName: String = "ESSENTIAL",
    @ColumnInfo(defaultValue = "AWAKE") val window: String = "AWAKE",
)

@Entity(
    tableName = "habit_logs",
    foreignKeys = [ForeignKey(HabitEntity::class, ["id"], ["habitId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("habitId"), Index("logicalDay"), Index(value = ["reminderKey"], unique = true)],
)
data class HabitLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    val at: LocalDateTime,
    val logicalDay: LocalDate,
    val skipped: Boolean,
    val reminderKey: String?,
)

@Entity(tableName = "work_classifications")
data class WorkClassificationEntity(
    @PrimaryKey val key: String,
    val isWork: Boolean,
    /** `USER` (marca manual, siempre gana) o `AUTO` (clasificada por Claude). */
    @ColumnInfo(defaultValue = "USER") val source: String = SOURCE_USER,
) {
    companion object {
        const val SOURCE_USER = "USER"
        const val SOURCE_AUTO = "AUTO"
    }
}

data class HabitCountRow(val habitId: Long, val count: Int)

fun Activity.toEntity() = ActivityEntity(id, title, type.name, date, start, durationMin, repeat.name, remindBeforeMin, copyToGoogle, googleEventId, origin.name)

fun ActivityEntity.toModel() = Activity(
    id, title, ActivityType.valueOf(type), date, start, durationMin, Repeat.valueOf(repeat), remindBeforeMin, copyToGoogle, googleEventId,
    runCatching { ActivityOrigin.valueOf(origin) }.getOrDefault(ActivityOrigin.UNKNOWN),
)

fun OccurrenceOverride.toEntity() = ActivityOverrideEntity(activityId, day, start, durationMin, skipped)

fun ActivityOverrideEntity.toModel() = OccurrenceOverride(activityId, day, start, durationMin, skipped)

fun Habit.toEntity() = HabitEntity(id, name, icon.name, schedule.encode(), dailyTarget, days.encodeDays(), active, group.name, window.name)

fun HabitEntity.toModel() = Habit(
    id, name, habitIconOf(icon), decodeSchedule(schedule), dailyTarget, decodeDays(days), active,
    runCatching { HabitGroup.valueOf(groupName) }.getOrDefault(HabitGroup.ESSENTIAL),
    runCatching { HabitWindow.valueOf(window) }.getOrDefault(HabitWindow.AWAKE),
)

private fun habitIconOf(name: String): HabitIcon = runCatching { HabitIcon.valueOf(name) }.getOrDefault(HabitIcon.GENERIC)
