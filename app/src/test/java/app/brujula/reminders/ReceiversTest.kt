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

    @Test fun activityDoneMarksThatDayCancelsNotificationAndResyncs() {
        val id = runBlocking {
            ctx.container.activityRepository.upsert(
                app.brujula.activities.Activity(
                    title = "Ejercicio", type = app.brujula.activities.ActivityType.EXERCISE, date = LocalDate.of(2026, 11, 18),
                    start = java.time.LocalTime.of(13, 0), durationMin = 30, repeat = app.brujula.activities.Repeat.DAILY,
                ),
            )
        }
        val key = "act:$id:20261118"
        Notifications.show(ctx, Reminder(key, now, ReminderKind.ACTIVITY, "Ejercicio a las 13:00", "Empieza en 15 min.", activityId = id, day = LocalDate.of(2026, 11, 18)))
        assertEquals(1, shadowOf(nm).allNotifications.size)
        val intent = Intent(ctx, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.ACTION_DONE)
            .putExtra(NotificationActionReceiver.EXTRA_KEY, key)
            .putExtra(NotificationActionReceiver.EXTRA_ACTIVITY_ID, id)
            .putExtra(NotificationActionReceiver.EXTRA_DAY, "2026-11-18")
            .putExtra(NotificationActionReceiver.EXTRA_TITLE, "Ejercicio a las 13:00")
            .putExtra(NotificationActionReceiver.EXTRA_BODY, "Empieza en 15 min.")
            .putExtra(NotificationActionReceiver.EXTRA_KIND, ReminderKind.ACTIVITY.name)
        NotificationActionReceiver().onReceive(ctx, intent)
        awaitCalls(1)
        val keys = runBlocking { ctx.container.activityRepository.observeDoneKeys().first() }
        assertEquals(setOf(id to LocalDate.of(2026, 11, 18)), keys)
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

    private fun groupIntent(action: String, key: String, ids: List<Long>) =
        Intent(ctx, NotificationActionReceiver::class.java).setAction(action)
            .putExtra(NotificationActionReceiver.EXTRA_KEY, key)
            .putExtra(NotificationActionReceiver.EXTRA_HABIT_ID, ids.first())
            .putExtra(NotificationActionReceiver.EXTRA_HABIT_IDS, ids.toLongArray())
            .putExtra(NotificationActionReceiver.EXTRA_DAY, "2026-11-18")
            .putExtra(NotificationActionReceiver.EXTRA_TITLE, "Pausa")
            .putExtra(NotificationActionReceiver.EXTRA_BODY, "a · b")
            .putExtra(NotificationActionReceiver.EXTRA_KIND, ReminderKind.HABIT.name)

    @Test fun groupedDoneLogsEachHabitOnceAndRepeatDoesNothing() {
        val a = newHabit(); val b = newHabit()
        val key = "wellness:202611181245"
        val g = Reminder(key, now, ReminderKind.HABIT, "Pausa", "a · b", habitId = a, habitIds = listOf(a, b), day = now.toLocalDate())
        Notifications.show(ctx, g)
        val n = shadowOf(nm).allNotifications.single()
        assertEquals(listOf("Hecho", "En 15 min"), n.actions.map { it.title.toString() })
        assertEquals(Notifications.CHANNEL_WELLNESS, n.channelId)
        val receiver = NotificationActionReceiver()
        receiver.onReceive(ctx, groupIntent(NotificationActionReceiver.ACTION_DONE, key, listOf(a, b)))
        receiver.onReceive(ctx, groupIntent(NotificationActionReceiver.ACTION_DONE, "$key:s", listOf(a, b)))
        awaitCalls(2)
        assertEquals(1, countFor(a))
        assertEquals(1, countFor(b))
        // un aviso individual posterior con la misma clave por habito tampoco duplica
        runBlocking { assertEquals(false, ctx.container.habitRepository.logDone(a, now, "habit:$a:202611181245")) }
    }

    @Test fun groupedSnoozeKeepsAllHabitIds() {
        val a = newHabit(); val b = newHabit()
        NotificationActionReceiver().onReceive(ctx, groupIntent(NotificationActionReceiver.ACTION_SNOOZE_15, "wellness:202611181245", listOf(a, b)))
        awaitCalls(1)
        val s = scheduler.one.single()
        assertEquals("wellness:202611181245:s", s.key)
        assertEquals(listOf(a, b), s.habitIds)
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

    @Test fun wakeUpIntentShowsNothingAndOnlyResyncs() {
        ReminderReceiver().onReceive(ctx, Intent(ctx, ReminderReceiver::class.java).setData(android.net.Uri.parse("brujula://wake")))
        awaitCalls(1)
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    private class ThrowingSync : ReminderResync {
        val calls = AtomicInteger()
        override suspend fun resync() { calls.incrementAndGet(); throw IllegalStateException("boom") }
    }

    private fun assertNoCrash(block: () -> Unit, calls: AtomicInteger, expected: Int) {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        try {
            block()
            val end = System.currentTimeMillis() + 5000
            while (calls.get() < expected && System.currentTimeMillis() < end) Thread.sleep(10)
            Thread.sleep(300)
            assertEquals(expected, calls.get())
            assertTrue("uncaught: $uncaught", uncaught.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test fun failingResyncDoesNotCrashBootReceiver() {
        val bad = ThrowingSync()
        ctx.container.reminderSync = bad
        assertNoCrash({ BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED)) }, bad.calls, 1)
    }

    @Test fun failingResyncDoesNotCrashActionOrReminderReceivers() {
        val bad = ThrowingSync()
        ctx.container.reminderSync = bad
        assertNoCrash({
            NotificationActionReceiver().onReceive(ctx, actionIntent(NotificationActionReceiver.ACTION_SKIP, "habit:1:202611181245", 1))
            ReminderReceiver().onReceive(ctx, Intent(ctx, ReminderReceiver::class.java))
        }, bad.calls, 2)
    }

    @Test fun failingHandlerStillResyncsAndDoesNotCrash() {
        ctx.container.reminderScheduler = object : ReminderScheduler {
            override fun replaceAll(reminders: List<Reminder>) = Unit
            override fun scheduleOne(reminder: Reminder) = throw SecurityException("no exact alarms")
        }
        assertNoCrash({
            NotificationActionReceiver().onReceive(ctx, actionIntent(NotificationActionReceiver.ACTION_SNOOZE_15, "habit:1:202611181245", 1))
        }, sync.calls, 1)
    }

    // --- Aviso de ejercicio en descanso ---
    // Room y DataStore persisten entre pruebas Robolectric: cada prueba usa un sabado distinto.
    private var sat = LocalDate.of(2026, 11, 21)
    private fun saturday(n: Long) { sat = LocalDate.of(2026, 11, 21).plusWeeks(n) }

    private fun promptIntent(action: String, day: LocalDate = sat) =
        Intent(ctx, NotificationActionReceiver::class.java).setAction(action)
            .putExtra(NotificationActionReceiver.EXTRA_KEY, "exprompt:${day.toString().replace("-", "")}")
            .putExtra(NotificationActionReceiver.EXTRA_DAY, day.toString())
            .putExtra(NotificationActionReceiver.EXTRA_TITLE, "¿Te animas a hacer ejercicio hoy?")
            .putExtra(NotificationActionReceiver.EXTRA_BODY, "Si te sientes con ganas, te busco un hueco de 30 min.")
            .putExtra(NotificationActionReceiver.EXTRA_KIND, ReminderKind.EXERCISE_PROMPT.name)

    private fun setNow(t: LocalDateTime) { ctx.container.clock = FixedClock(t) }

    private fun busy(day: LocalDate, h: Int, m: Int, minutes: Int, title: String = "Cita") = runBlocking {
        ctx.container.activityRepository.upsert(
            app.brujula.activities.Activity(
                title = title, type = app.brujula.activities.ActivityType.PERSONAL, date = day,
                start = java.time.LocalTime.of(h, m), durationMin = minutes, repeat = app.brujula.activities.Repeat.NONE,
            ),
        )
    }

    private fun exercises() = runBlocking {
        ctx.container.activityRepository.observeAll().first().filter { it.type == app.brujula.activities.ActivityType.EXERCISE && it.date in setOf(sat, sat.plusDays(1)) }
    }

    private fun answered() = runBlocking { ctx.container.routineRepository.exercisePromptAnswered.first() }

    @Test fun exerciseYesSchedulesFirstFreeSlotAfterBusyBlock() {
        saturday(1)
        setNow(sat.atTime(12, 0))
        busy(sat, 12, 0, 60)
        Notifications.show(ctx, Reminder("exprompt:${sat.toString().replace("-", "")}", sat.atTime(12, 0), ReminderKind.EXERCISE_PROMPT, "t", "b", day = sat))
        val n = shadowOf(nm).allNotifications.single()
        assertEquals(listOf("Sí, agéndalo", "Hoy no"), n.actions.map { it.title.toString() })
        NotificationActionReceiver().onReceive(ctx, promptIntent(NotificationActionReceiver.ACTION_EXERCISE_YES))
        awaitCalls(1)
        val a = exercises().single()
        assertEquals("Ejercicio en casa", a.title)
        assertEquals(sat, a.date)
        assertEquals(java.time.LocalTime.of(13, 0), a.start)
        assertEquals(30, a.durationMin)
        assertEquals(app.brujula.activities.Repeat.NONE, a.repeat)
        assertEquals(15, a.remindBeforeMin)
        assertTrue(sat in answered())
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun exerciseYesTwiceCreatesOnlyOneActivity() {
        saturday(2)
        setNow(sat.atTime(12, 0))
        val receiver = NotificationActionReceiver()
        receiver.onReceive(ctx, promptIntent(NotificationActionReceiver.ACTION_EXERCISE_YES))
        receiver.onReceive(ctx, promptIntent(NotificationActionReceiver.ACTION_EXERCISE_YES))
        awaitCalls(2)
        assertEquals(1, exercises().size)
    }

    @Test fun exerciseYesRoundsUpToNextQuarterHour() {
        saturday(3)
        setNow(sat.atTime(12, 7))
        NotificationActionReceiver().onReceive(ctx, promptIntent(NotificationActionReceiver.ACTION_EXERCISE_YES))
        awaitCalls(1)
        assertEquals(java.time.LocalTime.of(12, 15), exercises().single().start)
    }

    @Test fun exerciseYesAfterMidnightStillUsesTheRestDayOfThePrompt() {
        saturday(4)
        setNow(sat.plusDays(1).atTime(0, 20))
        NotificationActionReceiver().onReceive(ctx, promptIntent(NotificationActionReceiver.ACTION_EXERCISE_YES))
        awaitCalls(1)
        // Sigue siendo el sabado logico: vigilia hasta la 01:00, cabe 00:30-01:00 (no la vigilia del domingo).
        val a = exercises().single()
        assertEquals(sat.plusDays(1), a.date)
        assertEquals(java.time.LocalTime.of(0, 30), a.start)
        assertTrue(sat in answered())
    }

    @Test fun exerciseNoOnlyMarksAnswered() {
        saturday(5)
        setNow(sat.atTime(12, 0))
        NotificationActionReceiver().onReceive(ctx, promptIntent(NotificationActionReceiver.ACTION_EXERCISE_NO))
        awaitCalls(1)
        assertTrue(exercises().isEmpty())
        assertTrue(sat in answered())
    }

    @Test fun exerciseYesWithoutFreeSlotMarksAnsweredAndNotifies() {
        saturday(6)
        setNow(sat.atTime(12, 0))
        busy(sat, 11, 0, 14 * 60)
        NotificationActionReceiver().onReceive(ctx, promptIntent(NotificationActionReceiver.ACTION_EXERCISE_YES))
        awaitCalls(1)
        assertTrue(exercises().isEmpty())
        assertTrue(sat in answered())
        val n = shadowOf(nm).allNotifications.single()
        assertEquals("No encontré un hueco libre hoy para ejercicio.", n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
    }

    @Test fun groupedReminderNotificationCarriesFullBodyAsBigText() {
        val body = "Mira algo lejano 20 segundos. · Párate y camina 2 minutos. · Estira cuello, espalda y muñecas. · Endereza la espalda y relaja los hombros."
        val g = Reminder("wellness:202611181300", now, ReminderKind.HABIT, "Pausa", body, habitId = 1, habitIds = listOf(1, 2, 3, 4), day = now.toLocalDate())
        Notifications.show(ctx, g)
        val n = shadowOf(nm).allNotifications.single()
        assertEquals(body, n.extras.getCharSequence(androidx.core.app.NotificationCompat.EXTRA_BIG_TEXT).toString())
        assertEquals("android.app.Notification\$BigTextStyle", n.extras.getString(androidx.core.app.NotificationCompat.EXTRA_TEMPLATE))
    }

    @Test fun onlyWellnessNotificationsTimeOutAfter25Minutes() {
        val w = Reminder("wellness:202611181301", now, ReminderKind.HABIT, "Pausa", "a · b", habitId = 1, habitIds = listOf(1, 2), day = now.toLocalDate())
        val single = Reminder("habit:9:202611181302", now, ReminderKind.HABIT, "Ojos", "Mira algo lejano 20 segundos.", habitId = 9, day = now.toLocalDate(), wellness = true)
        val habit = Reminder("habit:8:202611181303", now, ReminderKind.HABIT, "Hora de tomar agua", "Vas 0 de 8 vasos.", habitId = 8, day = now.toLocalDate())
        val act = Reminder("act:5:20261118", now, ReminderKind.ACTIVITY, "Ejercicio", "Empieza en 15 min.", activityId = 5, day = now.toLocalDate())
        listOf(w, single, habit, act).forEach { Notifications.show(ctx, it) }
        val byId = shadowOf(nm).allNotifications.associateBy { it.channelId + it.extras.getString(android.app.Notification.EXTRA_TITLE) }
        val timeouts = shadowOf(nm).allNotifications.map { it.channelId to it.timeoutAfter }.groupBy({ it.first }, { it.second })
        assertEquals(listOf(25 * 60_000L, 25 * 60_000L), timeouts[Notifications.CHANNEL_WELLNESS])
        assertEquals(listOf(0L), timeouts[Notifications.CHANNEL_HABITS])
        assertEquals(listOf(0L), timeouts[Notifications.CHANNEL_ACTIVITIES])
        assertEquals(4, byId.size)
    }
}
