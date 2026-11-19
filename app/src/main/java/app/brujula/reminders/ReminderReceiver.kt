package app.brujula.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.brujula.container

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Sin extras (alarma silenciosa "wake" de reprogramacion): no muestra nada y solo resincroniza.
        val reminder = ReminderExtras.read(intent)
        // Mostrar primero y de forma sincrona: lo critico es que suene a su hora.
        if (reminder != null) {
            try {
                Notifications.show(context, reminder)
            } catch (e: Exception) {
                android.util.Log.w("ReminderReceiver", "No se pudo mostrar el aviso", e)
            }
        }
        val app = context.applicationContext
        launchAsync("ReminderReceiver") { app.container.reminderSync.resync() }
    }
}
