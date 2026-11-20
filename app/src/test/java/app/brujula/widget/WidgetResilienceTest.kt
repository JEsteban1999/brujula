package app.brujula.widget

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.brujula.R
import app.brujula.activities.Activity
import app.brujula.activities.ActivityType
import app.brujula.container
import app.brujula.core.time.FixedClock
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitSchedule
import app.brujula.planning.DaySnapshot
import app.brujula.planning.DaySnapshotProvider
import app.brujula.reminders.NotificationActionReceiver
import app.brujula.reminders.ReminderKind
import app.brujula.reminders.ReminderResync
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** El widget no depende de los calendarios del sistema ni de que todo salga bien: siempre queda una alarma armada. */
@RunWith(RobolectricTestRunner::class)
class WidgetResilienceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val now = LocalDateTime.of(2026, 11, 18, 12, 45)
    private val day = LocalDate.of(2026, 11, 18)
    private val resyncs = AtomicInteger()
    private val manager: AppWidgetManager get() = AppWidgetManager.getInstance(ctx)
    private val alarms get() = shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms

    @Before fun setUp() {
        WidgetUpdater.snapshotTimeoutMs = 200
        ctx.container.apply {
            clock = FixedClock(now)
            reminderSync = ReminderResync { resyncs.incrementAndGet() }
        }
    }

    @After fun tearDown() { WidgetUpdater.snapshotTimeoutMs = 3_500 }

    private fun widget(): Int = shadowOf(manager).createWidget(BrujulaWidgetProvider::class.java, R.layout.widget_brujula)

    private fun provider(block: suspend () -> DaySnapshot): DaySnapshotProvider = with(ctx.container) {
        object : DaySnapshotProvider(routineRepository, activityRepository, habitRepository, calendarSource, workClassificationRepository) {
            override suspend fun snapshot(day: LocalDate): DaySnapshot = block()
        }
    }

    private fun work() = runBlocking {
        ctx.container.activityRepository.upsert(Activity(title = "Informe", type = ActivityType.WORK, date = day, start = LocalTime.of(12, 0), durationMin = 120))
    }

    private fun title(id: Int): String? = shadowOf(manager).getViewFor(id)?.findViewById<TextView>(R.id.widget_now_title)?.text?.toString()

    private fun awaitResync(n: Int) {
        val end = System.currentTimeMillis() + 5000
        while (resyncs.get() < n && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("expected $n resyncs, got ${resyncs.get()}", resyncs.get() >= n)
    }

    @Test fun hangingCalendarStillRendersOwnBlocksAndArmsTheAlarm() {
        val id = widget()
        work()
        ctx.container.daySnapshots = provider { awaitCancellation() }
        runBlocking { WidgetUpdater.updateAll(ctx) }
        assertEquals("Informe", title(id))
        assertEquals(1, alarms.size)
    }

    @Test fun updateThrowingStillArmsTheSixtyMinuteFallbackAlarm() {
        widget()
        ctx.container.daySnapshots = provider { throw IllegalStateException("boom") }
        try {
            runBlocking { WidgetUpdater.updateAll(ctx) }
        } catch (e: IllegalStateException) {
            // esperado: el que llama lo atrapa con safely
        }
        assertEquals(1, alarms.size)
        assertEquals(now.plusMinutes(60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), alarms[0].triggerAtMs)
    }

    @Test fun plusOneWithHangingCalendarStillResyncsReminders() {
        widget()
        val habit = runBlocking {
            ctx.container.habitRepository.upsert(Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8))
        }
        ctx.container.daySnapshots = provider { awaitCancellation() }
        val intent = Intent(ctx, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.ACTION_ADD_HABIT)
            .setData(Uri.parse("brujula://widget/habit/$habit")).putExtra(WidgetActionReceiver.EXTRA_HABIT_ID, habit)
        WidgetActionReceiver().onReceive(ctx, intent)
        awaitResync(1)
    }

    @Test fun doneFromNotificationRepaintsTheWidgetCount() {
        val id = widget()
        val habit = runBlocking {
            ctx.container.habitRepository.upsert(Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8))
        }
        val key = "habit:$habit:202611181245"
        val intent = Intent(ctx, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_DONE)
            .putExtra(NotificationActionReceiver.EXTRA_KEY, key)
            .putExtra(NotificationActionReceiver.EXTRA_HABIT_ID, habit)
            .putExtra(NotificationActionReceiver.EXTRA_DAY, "2026-11-18")
            .putExtra(NotificationActionReceiver.EXTRA_TITLE, "Hora de tomar agua")
            .putExtra(NotificationActionReceiver.EXTRA_BODY, "Vas 0 de 8 vasos.")
            .putExtra(NotificationActionReceiver.EXTRA_KIND, ReminderKind.HABIT.name)
        NotificationActionReceiver().onReceive(ctx, intent)
        val end = System.currentTimeMillis() + 8000
        fun count() = shadowOf(manager).getViewFor(id)?.findViewById<TextView>(R.id.widget_habit1_count)?.text?.toString()
        while (count() != "1 / 8" && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals("1 / 8", count())
    }

    @Test fun disabledKeepsTheAlarmWhileWidgetsRemain() {
        widget()
        WidgetUpdater.scheduleNext(ctx, now.plusMinutes(30))
        BrujulaWidgetProvider().onDisabled(ctx)
        assertEquals(1, alarms.size)
    }

    @Test fun disabledCancelsTheAlarmWhenNoWidgets() {
        WidgetUpdater.scheduleNext(ctx, now.plusMinutes(30))
        BrujulaWidgetProvider().onDisabled(ctx)
        assertEquals(0, alarms.size)
    }
}
