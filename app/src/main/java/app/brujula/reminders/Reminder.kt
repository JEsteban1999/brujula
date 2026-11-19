package app.brujula.reminders

import java.time.LocalDate
import java.time.LocalDateTime

enum class ReminderKind { HABIT, ACTIVITY, EXERCISE_PROMPT }

data class Reminder(
    val key: String,
    val at: LocalDateTime,
    val kind: ReminderKind,
    val title: String,
    val body: String,
    val habitId: Long? = null,
    val activityId: Long? = null,
    val day: LocalDate,
)
