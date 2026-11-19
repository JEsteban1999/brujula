package app.brujula.reminders

import java.time.LocalDate
import java.time.LocalDateTime

/** [PLAN_NIGHT], [PLAN_MORNING] y [BEDTIME] son los avisos de planificación (canal `planificacion`, sin Hecho ni En 15 min). */
enum class ReminderKind { HABIT, ACTIVITY, EXERCISE_PROMPT, PLAN_NIGHT, PLAN_MORNING, BEDTIME }

data class Reminder(
    val key: String,
    val at: LocalDateTime,
    val kind: ReminderKind,
    val title: String,
    val body: String,
    val habitId: Long? = null,
    /** Solo en avisos agrupados de bienestar; [habitId] es el primero. */
    val habitIds: List<Long> = emptyList(),
    /** Aviso de un habito de bienestar (canal Pausas y bienestar). */
    val wellness: Boolean = false,
    val activityId: Long? = null,
    val day: LocalDate,
)
