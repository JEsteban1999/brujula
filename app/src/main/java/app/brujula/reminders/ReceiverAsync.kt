package app.brujula.reminders

import android.content.BroadcastReceiver
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Un fallo (DataStore, SQLite, AlarmManager) se registra pero nunca tumba el proceso. */
internal suspend fun safely(tag: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(tag, "Fallo en el receptor", e)
    }
}

/** goAsync() + corrutina; el resultado pendiente siempre se cierra. */
internal fun BroadcastReceiver.launchAsync(tag: String, block: suspend () -> Unit) {
    val pending: BroadcastReceiver.PendingResult? = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
        try {
            safely(tag, block)
        } finally {
            pending?.finish()
        }
    }
}
