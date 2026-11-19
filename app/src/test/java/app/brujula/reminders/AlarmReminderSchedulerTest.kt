package app.brujula.reminders

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class AlarmReminderSchedulerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val alarms = ctx.getSystemService(AlarmManager::class.java)
    private val zone = ZoneId.systemDefault()
    private var nowMs = LocalDateTime.of(2026, 11, 18, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private lateinit var scheduler: AlarmReminderScheduler

    @Before fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler = AlarmReminderScheduler(ctx) { nowMs }
    }

    private fun at(min: Long) = Instant.ofEpochMilli(nowMs).plusSeconds(min * 60).atZone(zone).toLocalDateTime()
    private fun r(key: String, min: Long) =
        Reminder(key, at(min), ReminderKind.HABIT, "t", "b", habitId = 1, day = LocalDate.of(2026, 11, 18))

    private fun isWake(a: org.robolectric.shadows.ShadowAlarmManager.ScheduledAlarm) =
        shadowOf(a.operation!!).savedIntent.data?.host == "wake"

    /** Horas de los avisos reales (sin la alarma silenciosa de reprogramacion). */
    private fun triggers() = shadowOf(alarms).scheduledAlarms.filterNot(::isWake).map { it.triggerAtMs }.sorted()
    private fun ms(min: Long) = nowMs + min * 60_000

    @Test fun replaceAllAlsoArmsOneSilentWakeUpIn12HoursThatIsNeverCancelled() {
        scheduler.replaceAll(emptyList())
        val wake = shadowOf(alarms).scheduledAlarms.filter(::isWake)
        assertEquals(listOf(ms(12 * 60)), wake.map { it.triggerAtMs })
        assertTrue(wake.single().allowWhileIdle)
        // Un replaceAll posterior (otros avisos, mas tarde) la reemplaza sin duplicarla ni cancelarla.
        nowMs += 60 * 60_000
        scheduler.replaceAll(listOf(r("habit:1:a", 30)))
        val again = shadowOf(alarms).scheduledAlarms.filter(::isWake)
        assertEquals(listOf(ms(0) + 12 * 3_600_000L), again.map { it.triggerAtMs })
        assertEquals(listOf(ms(30)), triggers())
    }

    @Test fun replaceAllSchedulesAlarmsWhenAllowed() {
        scheduler.replaceAll(listOf(r("habit:1:a", 30), r("habit:1:b", 60)))
        assertEquals(listOf(ms(30), ms(60)), triggers())
        assertTrue(shadowOf(alarms).scheduledAlarms.all { it.type == AlarmManager.RTC_WAKEUP })
    }

    @Test fun staleFutureKeysAreCancelled() {
        scheduler.replaceAll(listOf(r("habit:1:a", 30), r("habit:1:b", 60)))
        scheduler.replaceAll(listOf(r("habit:1:b", 60)))
        assertEquals(listOf(ms(60)), triggers())
    }

    @Test fun overdueKeysAreNotCancelled() {
        scheduler.replaceAll(listOf(r("habit:1:a", 30), r("habit:1:b", 60)))
        val aAt = ms(30)
        nowMs += 40 * 60_000 // "a" venció pero Doze aun no lo entrego
        scheduler.replaceAll(listOf(r("habit:1:b", 20)))
        assertTrue(aAt in triggers())
    }

    @Test fun overdueEntriesArePrunedAfterSixHours() {
        scheduler.replaceAll(listOf(r("habit:1:a", 30)))
        nowMs += 7 * 3_600_000L
        scheduler.replaceAll(emptyList())
        val tracked = ctx.getSharedPreferences("scheduled_reminders", Context.MODE_PRIVATE)
            .getStringSet("alarms", emptySet()).orEmpty()
        assertTrue(tracked.isEmpty())
    }

    @Test fun snoozeSurvivesReplaceAllAndIsRearmed() {
        val snooze = r("habit:1:a:s", 15)
        scheduler.scheduleOne(snooze)
        scheduler.replaceAll(listOf(r("habit:1:b", 60)))
        assertEquals(listOf(ms(15), ms(60)), triggers())
        // Simula reinicio: las alarmas del sistema se pierden; replaceAll re-arma la espera.
        shadowOf(alarms).scheduledAlarms.toList().forEach { alarms.cancel(it.operation!!) }
        assertTrue(triggers().isEmpty())
        scheduler.replaceAll(listOf(r("habit:1:b", 60)))
        assertEquals(listOf(ms(15), ms(60)), triggers())
    }

    @Test fun cancelledSnoozeIsNotRearmedByReplaceAllNorAfterReboot() {
        scheduler.scheduleOne(r("act:5:20261118:s", 15))
        scheduler.scheduleOne(r("habit:1:a:s", 20))
        scheduler.cancelSnooze("act:5:20261118:s")
        assertEquals(listOf(ms(20)), triggers())
        scheduler.replaceAll(emptyList())
        assertEquals(listOf(ms(20)), triggers())
        // Simula reinicio: solo la espera que sigue vigente se vuelve a armar.
        shadowOf(alarms).scheduledAlarms.toList().forEach { alarms.cancel(it.operation!!) }
        scheduler.replaceAll(emptyList())
        assertEquals(listOf(ms(20)), triggers())
        // Cancelar una que no existe no hace nada.
        scheduler.cancelSnooze("act:9:20261118:s")
        assertEquals(listOf(ms(20)), triggers())
    }

    @Test fun expiredSnoozeIsPrunedAndNotRearmed() {
        scheduler.scheduleOne(r("habit:1:a:s", 15))
        nowMs += 7 * 3_600_000L
        scheduler.replaceAll(emptyList())
        shadowOf(alarms).scheduledAlarms.toList().forEach { alarms.cancel(it.operation!!) }
        scheduler.replaceAll(emptyList())
        assertTrue(triggers().isEmpty())
    }

    @Test fun overdueSnoozeIsNotRearmedButStillNotCancelled() {
        scheduler.scheduleOne(r("habit:1:a:s", 15))
        nowMs += 30 * 60_000
        scheduler.replaceAll(emptyList())
        assertEquals(1, triggers().size) // no cancelado
    }

    @Test fun inexactFallbackWhenExactNotAllowed() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        scheduler.replaceAll(listOf(r("habit:1:a", 30)))
        assertEquals(listOf(ms(30)), triggers())
        assertFalse(readPermissionStatus(ctx).exactAlarms)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(readPermissionStatus(ctx).exactAlarms)
    }
}
