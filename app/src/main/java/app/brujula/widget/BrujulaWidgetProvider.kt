package app.brujula.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import app.brujula.reminders.launchAsync
import app.brujula.reminders.safely

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

    /** Un broadcast ajeno con esta acción no debe cancelar la alarma mientras haya widgets puestos. */
    override fun onDisabled(context: Context) {
        if (!WidgetUpdater.hasWidgets(context)) WidgetUpdater.cancelRefresh(context)
    }

    private fun refresh(context: Context) {
        val app = context.applicationContext
        launchAsync("BrujulaWidget") { safely("BrujulaWidget") { WidgetUpdater.updateAll(app) } }
    }
}
