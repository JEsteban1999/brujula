package app.brujula.reminders

import android.app.AlarmManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat

data class PermissionStatus(val notifications: Boolean, val exactAlarms: Boolean) {
    val ok get() = notifications && exactAlarms
}

fun readPermissionStatus(context: Context): PermissionStatus {
    val alarms = context.getSystemService(AlarmManager::class.java)
    return PermissionStatus(
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        exactAlarms = alarms?.canScheduleExactAlarms() == true,
    )
}
