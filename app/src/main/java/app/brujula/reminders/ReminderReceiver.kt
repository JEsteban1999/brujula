package app.brujula.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.brujula.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminder = ReminderExtras.read(intent)
        // Mostrar primero y de forma sincrona: lo critico es que suene a su hora.
        if (reminder != null) Notifications.show(context, reminder)
        val app = context.applicationContext
        val pending: PendingResult? = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                app.container.reminderSync.resync()
            } finally {
                pending?.finish()
            }
        }
    }
}
