package app.brujula.reminders

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.brujula.container
import app.brujula.core.time.FixedClock
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitSchedule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private class FakeScheduler : ReminderScheduler {
    val one = CopyOnWriteArrayList<Reminder>()
    override fun replaceAll(reminders: List<Reminder>) = Unit
    override fun scheduleOne(reminder: Reminder) { one.add(reminder) }
}

private class FakeSync : ReminderResync {
    val calls = AtomicInteger()
    override suspend fun resync() { calls.incrementAndGet() }
}

@RunWith(RobolectricTestRunner::class)
class ReceiversTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val now = LocalDateTime.of(2026, 11, 18, 12, 45)
    private val scheduler = FakeScheduler()
    private val sync = FakeSync()

    @Before fun setUp() {
        ctx.container.apply {
            clock = FixedClock(now)
            reminderScheduler = scheduler
            reminderSync = sync
        }
    }

    private fun awaitCalls(n: Int) {
        val end = System.currentTimeMillis() + 5000
        while (sync.calls.get() < n && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("expected $n resync calls, got ${sync.calls.get()}", sync.calls.get() >= n)
    }

    private fun actionIntent(action: String, key: String, habitId: Long) =
        Intent(ctx, NotificationActionReceiver::class.java).setAction(action)
            .putExtra(NotificationActionReceiver.EXTRA_KEY, key)
            .putExtra(NotificationActionReceiver.EXTRA_HABIT_ID, habitId)
            .putExtra(NotificationActionReceiver.EXTRA_DAY, "2026-11-18")
            .putExtra(NotificationActionReceiver.EXTRA_TITLE, "Hora de tomar agua")
            .putExtra(NotificationActionReceiver.EXTRA_BODY, "Vas 0 de 8 vasos.")
            .putExtra(NotificationActionReceiver.EXTRA_KIND, ReminderKind.HABIT.name)

    private fun newHabit(): Long = runBlocking {
        ctx.container.habitRepository.upsert(Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8))
    }

    private fun postFor(key: String, habitId: Long) {
        Notifications.show(ctx, Reminder(key, now, ReminderKind.HABIT, "Hora de tomar agua", "Vas 0 de 8 vasos.", habitId = habitId, day = now.toLocalDate()))
    }

    private val nm get() = ctx.getSystemService(NotificationManager::class.java)

    private fun countFor(id: Long): Int? = runBlocking {
        ctx.container.habitRepository.observeCountsFor(LocalDate.of(2026, 11, 18)).first()[id]
    }

    @Test fun doneActionTwiceLogsOnce() {
        val id = newHabit()
        val key = "habit:$id:202611181245"
        postFor(key, id)
        assertEquals(1, shadowOf(nm).allNotifications.size)
        val receiver = NotificationActionReceiver()
        receiver.onReceive(ctx, actionIntent(NotificationActionReceiver.ACTION_DONE, key, id))
        receiver.onReceive(ctx, actionIntent(NotificationActionReceiver.ACTION_DONE, key, id))
        awaitCalls(2)
        assertEquals(1, countFor(id))
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun snoozeSchedulesOneIn15Minutes() {
        val id = newHabit()
        val key = "habit:$id:202611181245"
        postFor(key, id)
        NotificationActionReceiver().onReceive(ctx, actionIntent(NotificationActionReceiver.ACTION_SNOOZE_15, key, id))
        awaitCalls(1)
        assertEquals(1, scheduler.one.size)
        assertEquals(now.plusMinutes(15), scheduler.one[0].at)
        assertEquals("$key:s", scheduler.one[0].key)
        assertEquals(id, scheduler.one[0].habitId)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun doneOnSnoozedNotificationCountsOnceWithOriginalKey() {
        val id = newHabit()
        val key = "habit:$id:202611181245"
        val receiver = NotificationActionReceiver()
        receiver.onReceive(ctx, actionIntent(NotificationActionReceiver.ACTION_DONE, key, id))
        receiver.onReceive(ctx, actionIntent(NotificationActionReceiver.ACTION_DONE, "$key:s", id))
        awaitCalls(2)
        assertEquals(1, countFor(id))
    }

    @Test fun bootAndTimeChangesTriggerResync() {
        val actions = listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)
        actions.forEachIndexed { i, a ->
            BootReceiver().onReceive(ctx, Intent(a))
            awaitCalls(i + 1)
        }
        assertEquals(4, sync.calls.get())
    }

    @Test fun reminderReceiverShowsNotificationWithActionsAndResyncs() {
        val intent = Intent(ctx, ReminderReceiver::class.java)
            .putExtra(NotificationActionReceiver.EXTRA_KEY, "habit:1:202611181245")
            .putExtra(NotificationActionReceiver.EXTRA_HABIT_ID, 1L)
            .putExtra(NotificationActionReceiver.EXTRA_DAY, "2026-11-18")
            .putExtra(NotificationActionReceiver.EXTRA_TITLE, "Hora de tomar agua")
            .putExtra(NotificationActionReceiver.EXTRA_BODY, "Vas 0 de 8 vasos.")
            .putExtra(NotificationActionReceiver.EXTRA_KIND, ReminderKind.HABIT.name)
        ReminderReceiver().onReceive(ctx, intent)
        awaitCalls(1)
        val n = shadowOf(nm).allNotifications.single()
        assertEquals(listOf("Hecho", "En 15 min", "Saltar"), n.actions.map { it.title.toString() })
    }
}
