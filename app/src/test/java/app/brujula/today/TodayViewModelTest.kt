package app.brujula.today

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.calendar.ExternalEvent
import app.brujula.calendar.FakeCalendarSource
import app.brujula.calendar.WorkClassificationRepository
import app.brujula.calendar.classificationKey
import app.brujula.core.time.FixedClock
import app.brujula.core.time.TimeSpan
import app.brujula.data.db.BrujulaDatabase
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitRepository
import app.brujula.habits.HabitSchedule
import app.brujula.reminders.PermissionStatus
import app.brujula.routine.RoutineRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private data class OwnView(val occurrence: app.brujula.activities.Occurrence, val isNow: Boolean)

@RunWith(RobolectricTestRunner::class)
class TodayViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: BrujulaDatabase
    private lateinit var activities: ActivityRepository
    private lateinit var habits: HabitRepository
    private val day = LocalDate.of(2026, 11, 18)
    private val clock = FixedClock(LocalDateTime.of(2026, 11, 18, 10, 40))
    private var perms = PermissionStatus(notifications = true, exactAlarms = true)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        activities = ActivityRepository(db.activityDao())
        habits = HabitRepository(db.habitDao())
        classifications = WorkClassificationRepository(db.workClassificationDao())
    }

    @After fun tearDown() { db.close() }

    private val calendar = FakeCalendarSource()
    private lateinit var classifications: WorkClassificationRepository
    private val TodayUiState.own get() = timeline.filterIsInstance<TimelineBlock.Own>().map { OwnView(it.occurrence, it.isNow) }

    private fun TestScope.routineRepo(file: String = "r.preferences_pb") = RoutineRepository(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, file) },
    )

    private fun TestScope.vm(routine: RoutineRepository = routineRepo()) = TodayViewModel(
        clock = clock,
        routineRepository = routine,
        activityRepository = activities,
        habitRepository = habits,
        readPermissions = { perms },
        calendarSource = calendar,
        classifications = classifications,
        scopeOverride = backgroundScope,
        tickMs = 0,
    )

    private fun act(title: String, type: ActivityType, start: String, min: Int, date: LocalDate = day, repeat: Repeat = Repeat.NONE) =
        Activity(title = title, type = type, date = date, start = LocalTime.parse(start), durationMin = min, repeat = repeat)

    private suspend fun seedDay(): Long {
        val w1 = activities.upsert(act("Trabajo", ActivityType.WORK, "08:00", 150))
        activities.upsert(act("Ejercicio", ActivityType.EXERCISE, "10:30", 30))
        activities.upsert(act("Trabajo", ActivityType.WORK, "11:00", 90))
        activities.setDone(w1, day, true)
        return w1
    }

    @Test fun nowEntryAndWorkMinutes() = runTest {
        seedDay()
        val s = vm().state.first { it.own.isNotEmpty() }
        assertTrue(s.own.single { it.isNow }.occurrence.activity.type == ActivityType.EXERCISE)
        assertEquals(240, s.workMinutes) // 150 + 90
        assertEquals("Miércoles 18 de noviembre", s.dayLabel)
        assertTrue(s.own.first().occurrence.done)
    }

    @Test fun addHabitIncrementsCount() = runTest {
        val water = habits.upsert(
            Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8),
        )
        repeat(3) { habits.logDone(water, clock.now().minusMinutes(60L * (it + 1))) }
        val vm = vm()
        assertEquals(3, vm.state.first { it.habits.isNotEmpty() }.habits.single().count)
        vm.addHabit(water)
        val s = vm.state.first { it.habits.single().count == 4 }
        assertEquals(4, s.habits.single().count)
        assertEquals(8, s.habits.single().target)
    }

    @Test fun toggleDoneMarksOnlyToday() = runTest {
        val id = activities.upsert(act("Ejercicio", ActivityType.EXERCISE, "18:30", 30, repeat = Repeat.DAILY))
        val vm = vm()
        vm.state.first { it.own.isNotEmpty() }
        vm.toggleDone(id, day)
        vm.state.first { it.own.single().occurrence.done }
        val keys = activities.observeDoneKeys().first()
        assertTrue((id to day) in keys)
        assertFalse((id to day.plusDays(1)) in keys)
        vm.toggleDone(id, day)
        vm.state.first { !it.own.single().occurrence.done }
        assertTrue(activities.observeDoneKeys().first().isEmpty())
    }

    @Test fun freeSlotsInsideAwakeWindow() = runTest {
        seedDay()
        activities.upsert(act("Ejercicio", ActivityType.EXERCISE, "18:30", 30))
        val s = vm().state.first { it.own.size == 4 }
        val at = { h: Int, m: Int -> day.atTime(h, m) }
        assertTrue(s.freeSlots.contains(TimeSpan(at(12, 30), at(18, 30))))
        assertTrue(s.freeSlots.none { it.start < at(8, 15) })
        assertTrue(s.freeSlots.none { it.end > day.plusDays(1).atTime(0, 30) })
        assertTrue(s.freeSlots.all { it.minutes >= 30 })
        assertTrue(s.freeSlots.contains(TimeSpan(at(19, 0), day.plusDays(1).atTime(0, 30))))
    }

    @Test fun overnightEndingBeforeWakeIsNotShown() = runTest {
        // ayer 23:30 + 90 min termina 01:00 de hoy: antes de levantarse (08:15), no entra en la vigilia de hoy
        activities.upsert(act("Película", ActivityType.PERSONAL, "23:30", 90, date = day.minusDays(1)))
        activities.upsert(act("Trabajo", ActivityType.WORK, "09:00", 60))
        val s = vm().state.first { it.own.isNotEmpty() }
        assertEquals(listOf("Trabajo"), s.own.map { it.occurrence.activity.title })
    }

    @Test fun overnightIntersectingAwakeWindowAppearsOnceAtTop() = runTest {
        // una vez, el día anterior, 23:30 -> 09:00 de hoy: cruza la vigilia (08:15)
        activities.upsert(act("Guardia", ActivityType.PERSONAL, "23:30", 570, date = day.minusDays(1)))
        activities.upsert(act("Trabajo", ActivityType.WORK, "09:30", 60))
        val s = vm().state.first { it.own.isNotEmpty() }
        assertEquals(listOf("Guardia", "Trabajo"), s.own.map { it.occurrence.activity.title })
        assertEquals(day.minusDays(1), s.own.first().occurrence.day)
        assertEquals(1, s.own.count { it.occurrence.activity.title == "Guardia" })
        // el hueco libre empieza cuando acaba (09:00), no a las 08:15
        assertTrue(s.freeSlots.none { it.start < day.atTime(9, 0) })
        assertEquals(day.atTime(9, 0), s.freeSlots.first().start)
        // no cuenta como trabajo de hoy
        assertEquals(60, s.workMinutes)
    }

    private fun TestScope.vmFor(file: String, tick: Long = 0) = TodayViewModel(
        clock = clock,
        routineRepository = RoutineRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, file) },
        ),
        activityRepository = activities, habitRepository = habits,
        readPermissions = { perms }, calendarSource = calendar, classifications = classifications,
        scopeOverride = backgroundScope, tickMs = tick,
    )

    @Test fun activityBeforeWakeIsShownInOrder() = runTest {
        activities.upsert(act("Trabajo", ActivityType.WORK, "09:00", 60))
        activities.upsert(act("Dentista", ActivityType.APPOINTMENT, "07:00", 45))
        val s = vm().state.first { it.own.size == 2 }
        assertEquals(listOf("Dentista", "Trabajo"), s.own.map { it.occurrence.activity.title })
        assertEquals(60, s.workMinutes)
    }

    @Test fun afterMidnightItemSavedForTodayAppearsTodayAndNotTomorrow() = runTest {
        clock.current = day.atTime(23, 50)
        // "Hoy 00:15" se guarda con la fecha natural siguiente
        activities.upsert(act("Llamada", ActivityType.PERSONAL, "00:15", 30, date = day.plusDays(1)))
        activities.upsert(act("Trabajo", ActivityType.WORK, "09:00", 60))
        val s = vm().state.first { it.own.size == 2 }
        assertEquals(listOf("Trabajo", "Llamada"), s.own.map { it.occurrence.activity.title })
        assertEquals(day.plusDays(1), s.own.last().occurrence.day)
        // el dia logico siguiente (a las 10:00) no la repite
        clock.current = day.plusDays(1).atTime(10, 0)
        val n = vmFor("r2.preferences_pb").state.first { it.day == day.plusDays(1) }
        assertTrue(n.own.none { it.occurrence.activity.title == "Llamada" })
    }

    @Test fun nowMovesAndDayRollsOverWithTicker() = runTest {
        clock.current = day.atTime(10, 40)
        activities.upsert(act("Ejercicio", ActivityType.EXERCISE, "10:30", 30))
        activities.upsert(act("Lectura", ActivityType.PERSONAL, "11:30", 30, repeat = Repeat.DAILY))
        val vm = vmFor("r3.preferences_pb", tick = 1000)
        // Room emite en otros hilos: se avanza el reloj virtual del ticker y se espera en tiempo real.
        fun TestScope.await(cond: (TodayUiState) -> Boolean): TodayUiState {
            // un solo tick: avanzar mas reiniciaria la consulta (flatMapLatest) antes de que Room responda
            testScheduler.advanceTimeBy(1000)
            repeat(500) {
                testScheduler.runCurrent()
                if (cond(vm.state.value)) return vm.state.value
                Thread.sleep(10)
            }
            error("estado esperado no alcanzado: ${vm.state.value}")
        }
        val first = await { it.own.size == 2 && it.own.any { e -> e.isNow } }
        assertEquals("Ejercicio", first.own.single { it.isNow }.occurrence.activity.title)
        clock.current = day.atTime(11, 40)
        val second = await { st -> st.own.any { it.isNow && it.occurrence.activity.title == "Lectura" } }
        assertEquals("Lectura", second.own.single { it.isNow }.occurrence.activity.title)
        // 03:59 sigue siendo el dia logico; 04:00 rueda al siguiente
        clock.current = day.plusDays(1).atTime(3, 59)
        assertEquals(day, await { it.day == day && it.own.none { e -> e.isNow } }.day)
        clock.current = day.plusDays(1).atTime(4, 0)
        assertEquals(day.plusDays(1), await { it.day == day.plusDays(1) }.day)
    }

    @Test fun toggleDoneTargetsTheGivenOccurrenceOfOvernightRepeat() = runTest {
        // DAILY 23:30 + 10 h: la de ayer termina 09:30 (cruza la vigilia) y la de hoy empieza 23:30
        val id = activities.upsert(act("Guardia", ActivityType.PERSONAL, "23:30", 600, date = day.minusDays(1), repeat = Repeat.DAILY))
        val yesterday = day.minusDays(1)
        activities.setDone(id, yesterday, true)
        val vm = vm()
        val s = vm.state.first { it.own.size == 2 }
        assertEquals(listOf(yesterday, day), s.own.map { it.occurrence.day })
        assertEquals(listOf(true, false), s.own.map { it.occurrence.done })
        vm.toggleDone(id, day)
        vm.state.first { st -> st.own.size == 2 && st.own.all { it.occurrence.done } }
        assertEquals(setOf(id to yesterday, id to day), activities.observeDoneKeys().first())
        vm.toggleDone(id, day)
        vm.state.first { st -> st.own.map { it.occurrence.done } == listOf(true, false) }
        assertEquals(setOf(id to yesterday), activities.observeDoneKeys().first())
    }

    @Test fun missingPermissionsAreExposed() = runTest {
        perms = PermissionStatus(notifications = false, exactAlarms = true)
        val vm = vm()
        assertFalse(vm.state.first { !it.permissions.ok }.permissions.ok)
        perms = PermissionStatus(notifications = true, exactAlarms = true)
        vm.refreshPermissions()
        assertTrue(vm.state.first { it.permissions.ok }.permissions.ok)
    }

    @Test fun saturdayIsRestWithoutGoalOrWorkdayExercise() = runTest {
        clock.current = LocalDateTime.of(2026, 11, 21, 11, 0)
        val sat = LocalDate.of(2026, 11, 21)
        activities.upsert(act("Ejercicio", ActivityType.EXERCISE, "18:30", 30, date = day, repeat = Repeat.WORKDAYS))
        val s = vm().state.first { it.day == sat && it.dayType.kind == app.brujula.routine.DayKind.WEEKEND && it.freeSlots.isNotEmpty() }
        assertEquals(null, s.workGoal)
        assertEquals(null, s.workStatus)
        assertTrue(s.own.none { it.occurrence.activity.title == "Ejercicio" })
        assertEquals(sat.atTime(10, 0), s.freeSlots.first().start)
    }

    @Test fun holidayTuesdayCarriesName() = runTest {
        val d = LocalDate.of(2026, 12, 8)
        clock.current = d.atTime(11, 0)
        val s = vm().state.first { it.day == d && it.dayType.kind == app.brujula.routine.DayKind.HOLIDAY }
        assertEquals("Inmaculada Concepción", s.dayType.holidayName)
        assertEquals(null, s.workGoal)
    }

    @Test fun workdayKeepsGoal() = runTest {
        val s = vm().state.first { it.day == day }
        assertEquals(app.brujula.routine.DayKind.WORKDAY, s.dayType.kind)
        assertEquals(360..420, s.workGoal)
        assertTrue(s.workStatus != null)
    }

    @Test fun todayCardsAreEssentialOnlyEvenWithWellnessActive() = runTest {
        habits.upsert(
            Habit(name = "Ojos", icon = HabitIcon.EYE, schedule = HabitSchedule.Every(30), dailyTarget = 14,
                group = app.brujula.habits.HabitGroup.WELLNESS, window = app.brujula.habits.HabitWindow.WORK),
        )
        habits.upsert(Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8))
        habits.upsert(Habit(name = "Bálsamo", icon = HabitIcon.LIP_BALM, schedule = HabitSchedule.AtTimes(listOf(LocalTime.of(9, 0))), dailyTarget = 1))
        val s = vm().state.first { it.habits.isNotEmpty() }
        assertEquals(listOf("Agua", "Bálsamo"), s.habits.map { it.name })
    }

    private fun ext(id: Long, title: String, s: LocalDateTime, e: LocalDateTime, allDay: Boolean = false) =
        ExternalEvent(id, 0, 1, "Personal", "Google", title, TimeSpan(s, e), allDay, null)

    private suspend fun TestScope.vmWithCalendar(events: List<ExternalEvent>): TodayViewModel {
        calendar.eventList = events
        val routine = routineRepo("rx.preferences_pb")
        routine.calendarPrefs.setSelectedCalendarIds(setOf(1L))
        return vm(routine)
    }

    @Test fun externalEventAppearsSplitsFreeSlotAndHasNoDone() = runTest {
        activities.upsert(act("Trabajo", ActivityType.WORK, "09:00", 60))
        val vm = vmWithCalendar(listOf(ext(1, "Dentista", day.atTime(15, 0), day.atTime(16, 0))))
        val s = vm.state.first { st -> st.timeline.any { it is TimelineBlock.External } }
        val e = s.timeline.filterIsInstance<TimelineBlock.External>().single()
        assertEquals("Dentista", e.event.title)
        assertEquals(listOf("Trabajo", "Dentista"), s.timeline.map { b ->
            when (b) { is TimelineBlock.Own -> b.occurrence.activity.title; is TimelineBlock.External -> b.event.title }
        })
        assertTrue(s.freeSlots.any { it.end == day.atTime(15, 0) })
        assertTrue(s.freeSlots.any { it.start == day.atTime(16, 0) })
        assertTrue(s.freeSlots.none { it.overlaps(TimeSpan(day.atTime(15, 0), day.atTime(16, 0))) })
        assertFalse(e.countsAsWork)
        assertEquals(60, s.workMinutes)
    }

    @Test fun allDayEventGoesToAllDayAndKeepsSlots() = runTest {
        val vm = vmWithCalendar(listOf(ext(2, "Festivo", day.atStartOfDay(), day.plusDays(1).atStartOfDay(), allDay = true)))
        val s = vm.state.first { it.allDay.isNotEmpty() }
        assertEquals("Festivo", s.allDay.single().title)
        assertTrue(s.timeline.isEmpty())
        assertEquals(day.atTime(8, 15), s.freeSlots.first().start)
        assertEquals(0, s.workMinutes)
    }

    @Test fun markingAsWorkAddsMinutes() = runTest {
        val ev = ext(3, "Reunión de equipo", day.atTime(15, 0), day.atTime(16, 0))
        val vm = vmWithCalendar(listOf(ev))
        vm.state.first { st -> st.timeline.any { it is TimelineBlock.External } }
        classifications.set(classificationKey(ev), true)
        val s = vm.state.first { it.workMinutes == 60 }
        assertTrue((s.timeline.single() as TimelineBlock.External).countsAsWork)
    }

    @Test fun untitledEventNeverCountsAsWork() = runTest {
        val ev = ext(4, "  ", day.atTime(15, 0), day.atTime(16, 0))
        val vm = vmWithCalendar(listOf(ev))
        classifications.set(classificationKey(ev), true)
        val s = vm.state.first { st -> st.timeline.any { it is TimelineBlock.External } }
        assertEquals(0, s.workMinutes)
        assertFalse((s.timeline.single() as TimelineBlock.External).countsAsWork)
    }

    @Test fun eventCrossingMidnightFromYesterdayAppearsOnce() = runTest {
        // ayer 23:00 -> hoy 09:00: cruza la vigilia de hoy (08:15) y la consulta lo devuelve duplicado
        val ev = ext(5, "Guardia", day.minusDays(1).atTime(23, 0), day.atTime(9, 0))
        val vm = vmWithCalendar(listOf(ev, ev.copy()))
        val s = vm.state.first { st -> st.timeline.any { it is TimelineBlock.External } }
        assertEquals(1, s.timeline.count { it is TimelineBlock.External })
        assertEquals(day.atTime(9, 0), s.freeSlots.first().start)
        assertEquals(0, s.workMinutes)
    }

    @Test fun providerIsQueriedOffTheMainThread() = runTest {
        val vm = vmWithCalendar(listOf(ext(6, "X", day.atTime(15, 0), day.atTime(16, 0))))
        vm.state.first { st -> st.timeline.any { it is TimelineBlock.External } }
        assertTrue(calendar.lastQueryThread !== Thread.currentThread())
    }

    @Test fun refreshExternalRequeries() = runTest {
        val vm = vmWithCalendar(emptyList())
        vm.state.first { it.day == day }
        calendar.eventList = listOf(ext(7, "Nuevo", day.atTime(15, 0), day.atTime(16, 0)))
        vm.refreshExternal()
        vm.state.first { st -> st.timeline.any { it is TimelineBlock.External } }
    }
}
