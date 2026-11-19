package app.brujula.claude

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.Repeat
import app.brujula.calendar.GoogleCopySync
import app.brujula.core.time.formatMinutes
import app.brujula.core.time.formatTime
import app.brujula.habits.Habit
import app.brujula.habits.HabitRepository
import app.brujula.habits.HabitSchedule
import app.brujula.reminders.ReminderResync
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

data class CreatedItem(val label: String, val activityId: Long?, val habitId: Long?)

/** Lo que creó un dictado, para deshacerlo exactamente. Opaco para la UI. */
data class UndoToken(val activityIds: List<Long>, val habitIds: List<Long>)

data class ApplyResult(val created: List<CreatedItem>, val messages: List<String>, val undo: UndoToken?)

/**
 * Aplica las acciones ya validadas: crea todo en una sola transacción (si algo falla no queda nada), copia a Google
 * las actividades que lo piden y resincroniza los recordatorios una vez. [undo] borra exactamente lo creado.
 */
class ActionExecutor(
    private val database: RoomDatabase,
    private val activityRepo: ActivityRepository,
    private val habitRepo: HabitRepository,
    private val googleCopySync: GoogleCopySync,
    private val reminderSync: ReminderResync,
) {
    /** Solo para pruebas: se invoca tras cada fila insertada, dentro de la transacción. */
    internal var afterInsert: (suspend () -> Unit)? = null

    suspend fun apply(items: List<Validated>): ApplyResult {
        val messages = items.mapNotNull {
            when (it) {
                is Validated.Question -> it.text
                is Validated.Unsupported -> it.text
                is Validated.Invalid -> it.reason
                else -> null
            }
        }.toMutableList()
        val toCreate = items.filter { it is Validated.Activity || it is Validated.Habit }
        if (toCreate.isEmpty()) return ApplyResult(emptyList(), messages, null)

        val created = mutableListOf<CreatedItem>()
        val savedActivities = mutableListOf<Activity>()
        try {
            database.withTransaction {
                for (item in toCreate) {
                    when (item) {
                        is Validated.Activity -> {
                            val id = activityRepo.upsert(item.activity)
                            savedActivities += item.activity.copy(id = id)
                            created += CreatedItem(activityLabel(item.activity), id, null)
                        }
                        is Validated.Habit -> {
                            val id = habitRepo.upsert(item.habit)
                            created += CreatedItem(habitLabel(item.habit), null, id)
                        }
                        else -> Unit
                    }
                    afterInsert?.invoke()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            messages += "No pude guardar lo que pediste. No se agendó nada."
            return ApplyResult(emptyList(), messages, null)
        }
        savedActivities.filter { it.copyToGoogle }.forEach { googleCopySync.afterSave(it) }
        reminderSync.resync()
        val token = UndoToken(created.mapNotNull { it.activityId }, created.mapNotNull { it.habitId })
        return ApplyResult(created.toList(), messages, token)
    }

    /** Idempotente: lo que ya no existe se omite. La copia de Google se barre aunque terminara después de [apply]. */
    suspend fun undo(token: UndoToken) {
        for (id in token.activityIds) {
            val stored = activityRepo.get(id) ?: continue
            activityRepo.delete(id)
            googleCopySync.afterDelete(stored)
        }
        token.habitIds.forEach { habitRepo.delete(it) }
        reminderSync.resync()
    }

    private val es = Locale.forLanguageTag("es")

    private fun shortDay(d: LocalDate): String {
        val wd = d.dayOfWeek.getDisplayName(TextStyle.SHORT, es).trimEnd('.')
        val month = d.month.getDisplayName(TextStyle.SHORT, es).trimEnd('.')
        return "$wd ${d.dayOfMonth} $month"
    }

    private fun activityLabel(a: Activity): String {
        val end = a.start.plusMinutes(a.durationMin.toLong())
        val repeat = when (a.repeat) {
            Repeat.NONE -> ""
            Repeat.DAILY -> " · cada día"
            Repeat.WEEKDAYS -> " · lunes a viernes"
            Repeat.WORKDAYS -> " · días laborales"
            Repeat.WEEKLY -> " · cada semana"
        }
        return "${a.title} · ${shortDay(a.date)} · ${"%02d:%02d".format(a.start.hour, a.start.minute)}–" +
            "${"%02d:%02d".format(end.hour, end.minute)}$repeat"
    }

    private fun habitLabel(h: Habit): String {
        val schedule = when (val s = h.schedule) {
            is HabitSchedule.Every -> "cada ${formatMinutes(s.intervalMin)}"
            is HabitSchedule.AtTimes -> "a las " + s.times.joinToString(", ") { formatTime(it) }
            is HabitSchedule.BeforeBed -> "${s.minutes} min antes de dormir"
        }
        return "Hábito · ${h.name} · $schedule"
    }
}
