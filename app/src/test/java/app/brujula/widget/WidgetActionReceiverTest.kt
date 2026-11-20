package app.brujula.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.brujula.container
import app.brujula.core.time.FixedClock
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitSchedule
import app.brujula.reminders.ReminderResync
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class WidgetActionReceiverTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val now = LocalDateTime.of(2026, 11, 18, 12, 45)
    private val resyncs = AtomicInteger()

    @Before fun setUp() {
        ctx.container.apply {
            clock = FixedClock(now)
            reminderSync = ReminderResync { resyncs.incrementAndGet() }
        }
    }

    private fun habit(active: Boolean = true): Long = runBlocking {
        ctx.container.habitRepository.upsert(
            Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8, active = active),
        )
    }

    private fun count(id: Long): Int = runBlocking {
        ctx.container.habitRepository.observeCountsFor(LocalDate.of(2026, 11, 18)).first()[id] ?: 0
    }

    private fun send(id: Long) {
        val intent = Intent(ctx, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.ACTION_ADD_HABIT)
            .setData(Uri.parse("brujula://widget/habit/$id")).putExtra(WidgetActionReceiver.EXTRA_HABIT_ID, id)
        WidgetActionReceiver().onReceive(ctx, intent)
    }

    private fun awaitResync(n: Int) {
        val end = System.currentTimeMillis() + 5000
        while (resyncs.get() < n && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("expected $n resyncs, got ${resyncs.get()}", resyncs.get() >= n)
    }

    @Test fun addHabitLogsOffline() {
        val id = habit()
        send(id)
        awaitResync(1)
        assertEquals(1, count(id))
        send(id)
        awaitResync(2)
        assertEquals(2, count(id))
    }

    @Test fun unknownOrInactiveHabitIgnored() {
        val inactive = habit(active = false)
        send(inactive)
        send(9999)
        awaitResync(2)
        assertEquals(0, count(inactive))
        assertEquals(0, count(9999))
    }
}
