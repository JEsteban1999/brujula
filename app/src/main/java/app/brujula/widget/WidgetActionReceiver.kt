package app.brujula.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.brujula.container
import app.brujula.reminders.launchAsync
import app.brujula.reminders.safely
import kotlinx.coroutines.flow.first

/** "+" del widget: registra el hábito directo en Room (sin red, aunque el proceso estuviera muerto) y repinta. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADD_HABIT) return
        val id = intent.getLongExtra(EXTRA_HABIT_ID, NO_ID)
        if (id == NO_ID) return
        val app = context.applicationContext
        launchAsync("WidgetAction") {
            safely("WidgetAction") { add(app, id) }
            safely("WidgetAction") { WidgetUpdater.updateAll(app) }
            // Como "Hecho" en la notificación: el próximo aviso de ese hábito se reprograma con el conteo nuevo.
            safely("WidgetAction") { app.container.reminderSync.resync() }
        }
    }

    /** Mismo camino que el "+" de Hoy (`logDone` con la hora actual); un hábito desconocido o inactivo se ignora. */
    private suspend fun add(context: Context, id: Long) {
        val c = context.container
        val habit = c.habitRepository.observeHabits().first().firstOrNull { it.id == id && it.active } ?: return
        c.habitRepository.logDone(habit.id, c.clock.now())
    }

    companion object {
        const val ACTION_ADD_HABIT = "app.brujula.widget.ADD_HABIT"
        const val EXTRA_HABIT_ID = "habitId"
        private const val NO_ID = -1L
    }
}
