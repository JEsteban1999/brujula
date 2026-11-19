package app.brujula.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import app.brujula.R
import java.time.LocalDate
import java.time.LocalDateTime

/** Ida y vuelta de un [Reminder] por extras de Intent (alarma y botones de la notificacion). */
object ReminderExtras {
    fun put(intent: Intent, r: Reminder): Intent = intent
        .putExtra(NotificationActionReceiver.EXTRA_KEY, r.key)
        .putExtra(NotificationActionReceiver.EXTRA_KIND, r.kind.name)
        .putExtra(NotificationActionReceiver.EXTRA_TITLE, r.title)
        .putExtra(NotificationActionReceiver.EXTRA_BODY, r.body)
        .putExtra(NotificationActionReceiver.EXTRA_DAY, r.day.toString())
        .putExtra(NotificationActionReceiver.EXTRA_HABIT_ID, r.habitId ?: -1L)
        .putExtra(NotificationActionReceiver.EXTRA_HABIT_IDS, r.habitIds.toLongArray())
        .putExtra(NotificationActionReceiver.EXTRA_WELLNESS, r.wellness)
        .putExtra(NotificationActionReceiver.EXTRA_ACTIVITY_ID, r.activityId ?: -1L)

    fun read(intent: Intent): Reminder? {
        val key = intent.getStringExtra(NotificationActionReceiver.EXTRA_KEY) ?: return null
        val kind = intent.getStringExtra(NotificationActionReceiver.EXTRA_KIND)
            ?.let { runCatching { ReminderKind.valueOf(it) }.getOrNull() } ?: return null
        val day = intent.getStringExtra(NotificationActionReceiver.EXTRA_DAY)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val habit = intent.getLongExtra(NotificationActionReceiver.EXTRA_HABIT_ID, -1L).takeIf { it >= 0 }
        val act = intent.getLongExtra(NotificationActionReceiver.EXTRA_ACTIVITY_ID, -1L).takeIf { it >= 0 }
        return Reminder(
            key = key, at = LocalDateTime.now(), kind = kind,
            title = intent.getStringExtra(NotificationActionReceiver.EXTRA_TITLE).orEmpty(),
            body = intent.getStringExtra(NotificationActionReceiver.EXTRA_BODY).orEmpty(),
            habitId = habit, habitIds = intent.getLongArrayExtra(NotificationActionReceiver.EXTRA_HABIT_IDS)?.toList().orEmpty(),
            wellness = intent.getBooleanExtra(NotificationActionReceiver.EXTRA_WELLNESS, false),
            activityId = act, day = day,
        )
    }
}

object Notifications {
    const val CHANNEL_HABITS = "habitos"
    const val CHANNEL_ACTIVITIES = "actividades"
    const val CHANNEL_WELLNESS = "pausas"
    private const val WELLNESS_TIMEOUT_MS = 25 * 60_000L

    fun notificationId(key: String): Int = key.hashCode()

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HABITS, "Hábitos", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Avisos de agua, bálsamo y otros hábitos" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WELLNESS, "Pausas y bienestar", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Pausas, postura, comida y otros h\u00e1bitos de bienestar" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ACTIVITIES, "Actividades", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Avisos antes de tus actividades" },
        )
    }

    fun show(context: Context, r: Reminder) {
        ensureChannels(context)
        val grouped = r.habitIds.size > 1
        val channel = when {
            r.kind != ReminderKind.HABIT -> CHANNEL_ACTIVITIES
            grouped || r.wellness -> CHANNEL_WELLNESS
            else -> CHANNEL_HABITS
        }
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(r.title)
            .setContentText(r.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (channel == CHANNEL_WELLNESS) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        if (channel == CHANNEL_WELLNESS) b.setTimeoutAfter(WELLNESS_TIMEOUT_MS)
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            b.setContentIntent(
                PendingIntent.getActivity(context, notificationId(r.key), launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        if (r.kind == ReminderKind.EXERCISE_PROMPT) {
            b.addAction(0, "Sí, agéndalo", actionIntent(context, r, NotificationActionReceiver.ACTION_EXERCISE_YES))
            b.addAction(0, "Hoy no", actionIntent(context, r, NotificationActionReceiver.ACTION_EXERCISE_NO))
        } else {
            b.addAction(0, "Hecho", actionIntent(context, r, NotificationActionReceiver.ACTION_DONE))
            b.addAction(0, "En 15 min", actionIntent(context, r, NotificationActionReceiver.ACTION_SNOOZE_15))
            if (r.kind == ReminderKind.HABIT && !grouped) {
                b.addAction(0, "Saltar", actionIntent(context, r, NotificationActionReceiver.ACTION_SKIP))
            }
        }
        post(context, notificationId(r.key), b)
    }

    /** Aviso informativo sin botones. */
    fun showInfo(context: Context, key: String, title: String) {
        ensureChannels(context)
        val b = NotificationCompat.Builder(context, CHANNEL_ACTIVITIES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        post(context, notificationId(key), b)
    }

    /** Aviso informativo con texto largo (BigTextStyle) que abre la app al tocarlo; un [id] distinto por aviso. */
    /** [contentIntent]: a dónde lleva el toque (p. ej. `DeepLinks.intent`); sin él, abre la app. */
    fun showText(context: Context, id: Int, title: String, text: String, contentIntent: Intent? = null) {
        ensureChannels(context)
        val b = NotificationCompat.Builder(context, CHANNEL_ACTIVITIES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        (contentIntent ?: context.packageManager.getLaunchIntentForPackage(context.packageName))?.let { launch ->
            b.setContentIntent(PendingIntent.getActivity(context, id, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        post(context, id, b)
    }

    private fun post(context: Context, id: Int, b: NotificationCompat.Builder) {
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, b.build())
        } catch (_: SecurityException) {
            // Sin permiso de notificaciones; Hoy mostrara el aviso para arreglarlo.
        }
    }

    fun cancel(context: Context, key: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(key))
    }

    /** URI unico por accion+clave, asi dos botones o dos avisos nunca comparten PendingIntent. */
    private fun actionIntent(context: Context, r: Reminder, action: String): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("brujula://action/${Uri.encode(action)}/${Uri.encode(r.key)}"))
        ReminderExtras.put(intent, r)
        return PendingIntent.getBroadcast(
            context, (action + r.key).hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
