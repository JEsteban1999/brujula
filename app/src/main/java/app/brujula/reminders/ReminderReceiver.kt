package app.brujula.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.brujula.container
import app.brujula.planning.MORNING_FALLBACK_BODY
import app.brujula.planning.morningBody
import kotlinx.coroutines.CancellationException

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Sin extras (alarma silenciosa "wake" de reprogramacion): no muestra nada y solo resincroniza.
        val reminder = ReminderExtras.read(intent)
        val app = context.applicationContext
        if (reminder?.kind == ReminderKind.PLAN_MORNING) {
            // El resumen se arma al entregarlo con el dia real (D14); si falla, sale con el texto de respaldo.
            launchAsync("ReminderReceiver") {
                val body = try {
                    morningBody(app.container.daySnapshots.snapshot(reminder.day))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("ReminderReceiver", "No se pudo armar el resumen; va el texto de respaldo", e)
                    MORNING_FALLBACK_BODY
                }
                show(app, reminder.copy(body = body))
                app.container.reminderSync.resync()
            }
            return
        }
        // Mostrar primero y de forma sincrona: lo critico es que suene a su hora.
        if (reminder != null) show(app, reminder)
        launchAsync("ReminderReceiver") { app.container.reminderSync.resync() }
    }

    private fun show(context: Context, reminder: Reminder) {
        try {
            Notifications.show(context, reminder)
        } catch (e: Exception) {
            android.util.Log.w("ReminderReceiver", "No se pudo mostrar el aviso", e)
        }
    }
}
