package app.brujula.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class AlarmReminderScheduler(private val context: Context) : ReminderScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("scheduled_reminders", Context.MODE_PRIVATE)

    /**
     * Los avisos "En 15 min" (clave con sufijo :s) no los conoce el planificador: se guardan completos
     * y se reprograman en cada replaceAll (asi sobreviven a un reinicio) hasta que suenan.
     */
    @Synchronized
    override fun replaceAll(reminders: List<Reminder>) {
        val nowMillis = System.currentTimeMillis()
        val snoozes = loadSnoozes().filter { millis(it) > nowMillis }
        val wanted = reminders.map { it.key }.toSet()
        val keep = snoozes.map { it.key }.toSet()
        for (stale in prefs.getStringSet(KEYS, emptySet()).orEmpty() - wanted - keep) cancel(stale)
        reminders.forEach { set(it) }
        snoozes.forEach { set(it) }
        prefs.edit()
            .putStringSet(KEYS, wanted + keep)
            .putStringSet(SNOOZES, snoozes.map { encode(it) }.toSet())
            .apply()
    }

    @Synchronized
    override fun scheduleOne(reminder: Reminder) {
        set(reminder)
        val editor = prefs.edit()
            .putStringSet(KEYS, prefs.getStringSet(KEYS, emptySet()).orEmpty() + reminder.key)
        if (reminder.key.endsWith(NotificationActionReceiver.SNOOZE_SUFFIX)) {
            val all = loadSnoozes().filter { it.key != reminder.key } + reminder
            editor.putStringSet(SNOOZES, all.map { encode(it) }.toSet())
        }
        editor.apply()
    }

    private fun millis(r: Reminder) = r.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun encode(r: Reminder): String = JSONObject()
        .put("key", r.key).put("at", r.at.toString()).put("kind", r.kind.name)
        .put("title", r.title).put("body", r.body).put("day", r.day.toString())
        .put("habit", r.habitId ?: -1L).put("act", r.activityId ?: -1L).toString()

    private fun loadSnoozes(): List<Reminder> =
        prefs.getStringSet(SNOOZES, emptySet()).orEmpty().mapNotNull {
            runCatching {
                val j = JSONObject(it)
                Reminder(
                    key = j.getString("key"), at = LocalDateTime.parse(j.getString("at")),
                    kind = ReminderKind.valueOf(j.getString("kind")),
                    title = j.getString("title"), body = j.getString("body"),
                    habitId = j.getLong("habit").takeIf { id -> id >= 0 },
                    activityId = j.getLong("act").takeIf { id -> id >= 0 },
                    day = LocalDate.parse(j.getString("day")),
                )
            }.getOrNull()
        }

    private fun set(r: Reminder) {
        val triggerAt = millis(r)
        val pi = pendingIntent(r.key, create = true, extrasFrom = r) ?: return
        val exact = alarms.canScheduleExactAlarms()
        try {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancel(key: String) {
        pendingIntent(key, create = false, extrasFrom = null)?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }

    /** La identidad del PendingIntent la da el URI (unico por clave); el request code solo es un refuerzo. */
    private fun pendingIntent(key: String, create: Boolean, extrasFrom: Reminder?): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).setData(Uri.parse("brujula://reminder/${Uri.encode(key)}"))
        if (extrasFrom != null) ReminderExtras.put(intent, extrasFrom)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, key.hashCode(), intent, flags)
    }

    private companion object {
        const val KEYS = "keys"
        const val SNOOZES = "snoozes"
    }
}
