package app.brujula.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.brujula.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        val pending: PendingResult? = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                handle(app, action, key, reminder)
            } finally {
                try {
                    app.container.reminderSync.resync()
                } finally {
                    pending?.finish()
                }
            }
        }
    }

    private suspend fun handle(context: Context, action: String, key: String, reminder: Reminder?) {
        val c = context.container
        val originalKey = key.removeSuffix(SNOOZE_SUFFIX)
        val now = c.clock.now()
        when (action) {
            ACTION_DONE -> when (reminder?.kind) {
                ReminderKind.HABIT -> reminder.habitId?.let { c.habitRepository.logDone(it, now, originalKey) }
                ReminderKind.ACTIVITY -> reminder.activityId?.let { c.activityRepository.setDone(it, reminder.day, true) }
                null -> Unit
            }
            ACTION_SKIP -> reminder?.habitId?.let { c.habitRepository.logSkip(it, now, originalKey) }
            ACTION_SNOOZE_15 -> if (reminder != null) {
                val snoozeKey = if (key.endsWith(SNOOZE_SUFFIX)) key else key + SNOOZE_SUFFIX
                c.reminderScheduler.scheduleOne(reminder.copy(key = snoozeKey, at = now.plusMinutes(15)))
            }
        }
    }

    companion object {
        const val ACTION_DONE = "app.brujula.action.DONE"
        const val ACTION_SNOOZE_15 = "app.brujula.action.SNOOZE_15"
        const val ACTION_SKIP = "app.brujula.action.SKIP"
        const val EXTRA_KEY = "key"
        const val EXTRA_HABIT_ID = "habitId"
        const val EXTRA_ACTIVITY_ID = "activityId"
        const val EXTRA_DAY = "day"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val SNOOZE_SUFFIX = ":s"
        private val ACTIONS = setOf(ACTION_DONE, ACTION_SNOOZE_15, ACTION_SKIP)
    }
}
