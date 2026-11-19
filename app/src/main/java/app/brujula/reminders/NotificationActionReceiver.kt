package app.brujula.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.brujula.activities.Activity
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.activities.freeSlots
import app.brujula.activities.occurrencesOn
import app.brujula.container
import app.brujula.core.time.TimeSpan
import app.brujula.routine.awakeWindow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Acciones de la notificacion; funcionan sin abrir la app. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val reminder = ReminderExtras.read(intent)
        // Quitar la notificacion de inmediato (tambien cubre un segundo toque).
        Notifications.cancel(context, key)

        val app = context.applicationContext
        launchAsync("NotificationAction") {
            safely("NotificationAction") { handle(app, action, key, reminder) }
            app.container.reminderSync.resync()
        }
    }

    private suspend fun handle(context: Context, action: String, key: String, reminder: Reminder?) {
        val c = context.container
        val originalKey = key.removeSuffix(SNOOZE_SUFFIX)
        val now = c.clock.now()
        when (action) {
            ACTION_DONE -> when (reminder?.kind) {
                ReminderKind.HABIT ->
                    if (reminder.habitIds.size > 1) {
                        val minute = originalKey.removePrefix("wellness:")
                        reminder.habitIds.forEach { c.habitRepository.logDone(it, now, "habit:$it:$minute") }
                    } else {
                        reminder.habitId?.let { c.habitRepository.logDone(it, now, originalKey) }
                    }
                ReminderKind.ACTIVITY -> reminder.activityId?.let { c.activityRepository.setDone(it, reminder.day, true) }
                ReminderKind.EXERCISE_PROMPT, null -> Unit
            }
            ACTION_SKIP -> reminder?.habitId?.let { c.habitRepository.logSkip(it, now, originalKey) }
            ACTION_EXERCISE_YES -> reminder?.let { exerciseYes(context, it.day, now) }
            ACTION_EXERCISE_NO -> reminder?.let { c.routineRepository.markExercisePromptAnswered(it.day) }
            ACTION_SNOOZE_15 -> if (reminder != null) {
                val snoozeKey = if (key.endsWith(SNOOZE_SUFFIX)) key else key + SNOOZE_SUFFIX
                c.reminderScheduler.scheduleOne(reminder.copy(key = snoozeKey, at = now.plusMinutes(15)))
            }
        }
    }

    /** Agenda 30 min en el primer hueco libre de la vigilia de [day]; idempotente ante doble toque. */
    private suspend fun exerciseYes(context: Context, day: LocalDate, now: LocalDateTime) = exerciseLock.withLock {
        val c = context.container
        if (day in c.routineRepository.exercisePromptAnswered.first()) return@withLock
        val activities = c.activityRepository.observeAll().first()
        val doneKeys = c.activityRepository.observeDoneKeys().first()
        val overrides = c.activityRepository.observeOverrides().first()
        if (occurrencesOn(day, activities, doneKeys, overrides).any { it.activity.type == ActivityType.EXERCISE }) {
            c.routineRepository.markExercisePromptAnswered(day)
            return@withLock
        }
        val window = c.routineRepository.routine.first().awakeWindow(day)
        val from = maxOf(ceilToQuarter(now), window.start)
        val busy = (-1L..1L).flatMap { occurrencesOn(day.plusDays(it), activities, doneKeys, overrides) }.map { it.span } +
            externalBusy(c, window)
        val slot = freeSlots(TimeSpan(from, window.end), busy, EXERCISE_MIN.toLong()).firstOrNull()
        if (slot == null) {
            Notifications.showInfo(context, "exnoslot:$day", "No encontré un hueco libre hoy para ejercicio.")
        } else {
            c.activityRepository.upsert(
                Activity(
                    title = "Ejercicio en casa", type = ActivityType.EXERCISE, date = slot.start.toLocalDate(),
                    start = slot.start.toLocalTime(), durationMin = EXERCISE_MIN, repeat = Repeat.NONE, remindBeforeMin = 15,
                ),
            )
        }
        c.routineRepository.markExercisePromptAnswered(day)
    }

    /** Reuniones de los calendarios elegidos (no de todo el dia, ni copias de Brujula); vacio si algo falla. */
    private suspend fun externalBusy(c: app.brujula.AppContainer, window: TimeSpan): List<TimeSpan> = try {
        val ids = c.routineRepository.calendarPrefs.selectedCalendarIds.first()
        if (ids.isNullOrEmpty() || !c.calendarSource.hasReadPermission()) {
            emptyList()
        } else {
            val linked = c.activityRepository.observeGoogleEventIds().first()
            c.calendarSource.events(ids, window.start.minusDays(1), window.end.plusDays(1))
                .filter { !it.allDay && it.eventId !in linked }
                .map { it.span }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("NotificationAction", "No se pudieron leer los calendarios", e)
        emptyList()
    }

    private fun ceilToQuarter(t: LocalDateTime): LocalDateTime {
        val base = t.truncatedTo(ChronoUnit.MINUTES)
        val exact = base == t && base.minute % 15 == 0
        return if (exact) base else base.plusMinutes((15 - base.minute % 15).toLong())
    }

    companion object {
        private val exerciseLock = Mutex()
        private const val EXERCISE_MIN = 30
        const val ACTION_EXERCISE_YES = "app.brujula.action.EXERCISE_YES"
        const val ACTION_EXERCISE_NO = "app.brujula.action.EXERCISE_NO"
        const val ACTION_DONE = "app.brujula.action.DONE"
        const val ACTION_SNOOZE_15 = "app.brujula.action.SNOOZE_15"
        const val ACTION_SKIP = "app.brujula.action.SKIP"
        const val EXTRA_KEY = "key"
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_HABIT_IDS = "habitIds"
        const val EXTRA_WELLNESS = "wellness"
        const val EXTRA_ACTIVITY_ID = "activityId"
        const val EXTRA_DAY = "day"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val SNOOZE_SUFFIX = ":s"
        private val ACTIONS = setOf(ACTION_DONE, ACTION_SNOOZE_15, ACTION_SKIP, ACTION_EXERCISE_YES, ACTION_EXERCISE_NO)
    }
}
