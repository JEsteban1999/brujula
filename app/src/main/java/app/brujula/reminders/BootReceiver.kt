package app.brujula.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.brujula.container
import app.brujula.widget.WidgetUpdater

/** Reprograma todo tras reinicio, cambio de hora o zona, o actualizacion de la app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val app = context.applicationContext
        launchAsync("BootReceiver") {
            safely("BootReceiver") { app.container.reminderSync.resync() }
            // También repinta el widget y vuelve a armar su alarma (se pierde al reiniciar o cambiar la hora).
            WidgetUpdater.updateAll(app)
        }
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
