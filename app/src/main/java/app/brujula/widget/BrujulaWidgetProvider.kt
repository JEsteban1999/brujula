package app.brujula.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import app.brujula.reminders.launchAsync

/** El widget 4x2. Solo repinta: al ponerlo, con la alarma de la próxima frontera y cuando la app avisa de cambios. */
class BrujulaWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WidgetUpdater.ACTION_REFRESH) {
            refresh(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = refresh(context)

    override fun onDisabled(context: Context) = WidgetUpdater.cancelRefresh(context)

    private fun refresh(context: Context) {
        val app = context.applicationContext
        launchAsync("BrujulaWidget") { WidgetUpdater.updateAll(app) }
    }
}
