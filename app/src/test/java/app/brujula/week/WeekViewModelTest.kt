package app.brujula.week

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.OccurrenceOverride
import app.brujula.activities.Repeat
import app.brujula.calendar.ExternalEvent
import app.brujula.calendar.FakeCalendarSource
import app.brujula.calendar.WorkClassificationRepository
import app.brujula.calendar.classificationKey
import app.brujula.core.time.FixedClock
import app.brujula.core.time.TimeSpan
import app.brujula.data.db.BrujulaDatabase
import app.brujula.reminders.ReminderResync
import app.brujula.routine.RoutineRepository
import app.brujula.sleep.SleepRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
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

@RunWith(RobolectricTestRunner::class)
class WeekViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: BrujulaDatabase
    private lateinit var activities: ActivityRepository
    private lateinit var classifications: WorkClassificationRepository
    private lateinit var sleep: SleepRepository
    private val clock = FixedClock(LocalDateTime.of(2026, 11, 18, 10, 24))
    private val mon = LocalDate.of(2026, 11, 16)
    private val wed = mon.plusDays(2)
    private val thu = mon.plusDays(3)
    private val fri = mon.plusDays(4)
    private var resyncs = 0

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        activities = ActivityRepository(db.activityDao())
        classifications = WorkClassificationRepository(db.workClassificationDao())
        sleep = SleepRepository(db.sleepDao())
    }

    @After fun tearDown() { db.close() }

    private fun TestScope.routineRepo() = RoutineRepository(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "r.preferences_pb") },
    )

    private fun TestScope.vm(
        calendar: app.brujula.calendar.CalendarSource = FakeCalendarSource(),
        routine: RoutineRepository = routineRepo(),
    ) = WeekViewModel(
        clock = clock,
        routineRepository = routine,
        activityRepository = activities,
        calendarSource = calendar,
        classifications = classifications,
        sleepRepository = sleep,
        resync = ReminderResync { resyncs++ },
        scopeOverride = backgroundScope,
        tickMs = 0,
    )

    private fun act(title: String, type: ActivityType, start: String, min: Int, date: LocalDate, repeat: Repeat = Repeat.NONE, copy: Boolean = false) =
        Activity(title = title, type = type, date = date, start = LocalTime.parse(start), durationMin = min, repeat = repeat, copyToGoogle = copy)

    private suspend fun WeekViewModel.loaded(): WeekUiState = state.first { it.data != null }
    private fun WeekUiState.day(d: LocalDate) = data!!.days.first { it.day == d }
    private fun WeekUiState.hasOwn(d: LocalDate) = data?.days?.first { it.day == d }?.events?.any { it is WeekEvent.Own } == true

    @Test fun startsOnCurrentWeekWithTodaySelected() = runTest {
        val s = vm().loaded()
        assertEquals(mon, s.data!!.monday)
        assertEquals(wed, s.selected)
        assertTrue(s.canGoBack)
        assertTrue(s.canGoForward)
        assertNull(s.message)
    }

    @Test fun navigationLimits() = runTest {
        val vm = vm()
        vm.loaded()
        repeat(40) { vm.previousWeek() }
        var s = vm.state.first { it.data?.monday == mon.minusWeeks(26) }
        assertFalse(s.canGoBack)
        assertTrue(s.canGoForward)
        assertEquals(-26, MIN_WEEK_OFFSET)
        repeat(40) { vm.nextWeek() }
        s = vm.state.first { it.data?.monday == mon.plusWeeks(4) }
        assertFalse(s.canGoForward)
        assertTrue(s.canGoBack)
        assertEquals(4, MAX_WEEK_OFFSET)
    }

    @Test fun selectingOtherWeekSelectsMonday() = runTest {
        val vm = vm()
        vm.loaded()
        vm.nextWeek()
        var s = vm.state.first { it.data?.monday == mon.plusWeeks(1) }
        assertEquals(mon.plusWeeks(1), s.selected)
        vm.previousWeek()
        s = vm.state.first { it.data?.monday == mon }
        assertEquals(wed, s.selected) // la semana actual vuelve a hoy
        vm.select(fri)
        assertEquals(fri, vm.state.first { it.selected == fri }.selected)
        vm.previousWeek()
        assertEquals(mon.minusWeeks(1), vm.state.first { it.data?.monday == mon.minusWeeks(1) }.selected)
    }

    @Test fun editInRepositoryRefreshesWeek() = runTest {
        val vm = vm()
        assertEquals(0, vm.loaded().day(thu).workMinutes)
        activities.upsert(act("Trabajo", ActivityType.WORK, "08:00", 240, thu))
        val s = vm.state.first { it.data?.days?.first { d -> d.day == thu }?.workMinutes == 240 }
        assertEquals(WeekDayState.PLANNED, s.day(thu).state)
    }

    @Test fun skipRecurringOnlyThatDayAndUndo() = runTest {
        val id = activities.upsert(act("Trabajo", ActivityType.WORK, "08:00", 120, mon, Repeat.WORKDAYS))
        val moved = OccurrenceOverride(id, thu, thu.atTime(9, 0), 90, skipped = false)
        activities.setOverride(moved)
        val vm = vm()
        vm.loaded()
        vm.select(thu)
        val own = vm.state.first { it.hasOwn(thu) }.day(thu).events.filterIsInstance<WeekEvent.Own>().single()
        assertTrue(own.canSkip)
        val before = resyncs
        vm.skipOccurrence(own).join()
        val s = vm.state.first { it.message != null }
        assertEquals("Quité «Trabajo» del jueves 19.", s.message!!.text)
        assertNotNull(s.message!!.undo)
        assertEquals(thu, s.message!!.undo!!.day)
        assertEquals(moved, s.message!!.undo!!.before)
        assertTrue(activities.getOverride(id, thu)!!.skipped)
        assertNull(activities.getOverride(id, fri))
        assertEquals(before + 1, resyncs)
        val week = vm.state.first { st -> st.data != null && !st.hasOwn(thu) }
        assertTrue(week.hasOwn(fri))

        vm.undoSkip().join()
        vm.state.first { st -> st.message == null && st.hasOwn(thu) }
        assertEquals(moved, activities.getOverride(id, thu))
        assertEquals(before + 2, resyncs)
    }

    @Test fun undoWithoutPreviousOverrideClearsIt() = runTest {
        val id = activities.upsert(act("Trabajo", ActivityType.WORK, "08:00", 120, mon, Repeat.WORKDAYS))
        val vm = vm()
        vm.loaded()
        vm.select(fri)
        val own = vm.state.first { it.hasOwn(fri) }.day(fri).events.filterIsInstance<WeekEvent.Own>().single()
        vm.skipOccurrence(own).join()
        assertNull(vm.state.first { it.message != null }.message!!.undo!!.before)
        vm.undoSkip().join()
        vm.state.first { it.message == null && it.hasOwn(fri) }
        assertNull(activities.getOverride(id, fri))
    }

    @Test fun skipRefusedForGoogleCopy() = runTest {
        val id = activities.upsert(act("Cita", ActivityType.APPOINTMENT, "16:00", 60, thu, Repeat.WEEKLY, copy = true))
        val vm = vm()
        vm.loaded()
        vm.select(thu)
        val own = vm.state.first { it.hasOwn(thu) }.day(thu).events.filterIsInstance<WeekEvent.Own>().single()
        assertFalse(own.canSkip)
        val before = resyncs
        vm.skipOccurrence(own).join()
        // Aunque la vista mande un evento con canSkip = true, el VM lo valida contra lo guardado.
        vm.skipOccurrence(own.copy(canSkip = true)).join()
        assertNull(activities.getOverride(id, thu))
        assertNull(vm.state.value.message)
        assertEquals(before, resyncs)
    }

    @Test fun calendarFailureStillShowsOwn() = runTest {
        val throwing = object : FakeCalendarSource() {
            override suspend fun events(calendarIds: Set<Long>, from: LocalDateTime, to: LocalDateTime): List<ExternalEvent> =
                throw IllegalStateException("proveedor caido")
        }
        val routine = routineRepo()
        routine.calendarPrefs.setSelectedCalendarIds(setOf(1L))
        activities.upsert(act("Trabajo", ActivityType.WORK, "08:00", 240, thu))
        val s = vm(throwing, routine).state.first { it.data?.days?.first { d -> d.day == thu }?.workMinutes == 240 }
        assertEquals(1, s.day(thu).events.size)
    }

    @Test fun externalEventsAreQueriedForTheWeekOffMain() = runTest {
        val routine = routineRepo()
        routine.calendarPrefs.setSelectedCalendarIds(setOf(1L))
        val event = ExternalEvent(
            9, 0, 1, "Cal", "Google", "Reunion",
            TimeSpan(thu.atTime(14, 0), thu.atTime(15, 0)), false, null,
        )
        val calendar = FakeCalendarSource(eventList = listOf(event))
        val vm = vm(calendar, routine)
        val s = vm.state.first { st -> st.data?.days?.first { it.day == thu }?.events?.isNotEmpty() == true }
        assertEquals("Reunion", s.day(thu).events.single().title)
        assertEquals(mon.minusDays(1).atTime(4, 0), calendar.lastFrom)
        assertEquals(mon.plusDays(7).atTime(4, 0), calendar.lastTo)
        assertNotSame(Thread.currentThread(), calendar.lastQueryThread)
    }

    @Test fun externalWorkClassificationRefreshesWeek() = runTest {
        val routine = routineRepo()
        routine.calendarPrefs.setSelectedCalendarIds(setOf(1L))
        val event = ExternalEvent(9, 0, 1, "Cal", "Outlook", "Daily", TimeSpan(thu.atTime(14, 0), thu.atTime(15, 0)), false, null)
        val vm = vm(FakeCalendarSource(eventList = listOf(event)), routine)
        vm.state.first { st -> st.data?.days?.first { it.day == thu }?.events?.isNotEmpty() == true }
        vm.setCountsAsWork(event, true)
        vm.state.first { st -> st.data?.days?.first { it.day == thu }?.workMinutes == 60 }
        assertEquals(true, classifications.observe().first()[classificationKey(event)])
    }

    @Test fun askClaudeOnlyTodayToSixDays() = runTest {
        val vm = vm()
        assertTrue(vm.canAskClaude(LocalDate.of(2026, 11, 18)))
        assertTrue(vm.canAskClaude(LocalDate.of(2026, 11, 24)))
        assertFalse(vm.canAskClaude(LocalDate.of(2026, 11, 17)))
        assertFalse(vm.canAskClaude(LocalDate.of(2026, 11, 25)))
    }

    @Test fun addStartForFutureDayIsFirstFreeSlot() = runTest {
        activities.upsert(act("Trabajo", ActivityType.WORK, "08:00", 240, thu))
        val vm = vm()
        vm.state.first { it.data?.days?.first { d -> d.day == thu }?.workMinutes == 240 }
        assertEquals(thu.atTime(12, 0), vm.addStartFor(thu))
        assertEquals(fri.atTime(8, 15), vm.addStartFor(fri))
        assertNull(vm.addStartFor(wed))
    }

    @Test fun sleepRecordsRefreshAverage() = runTest {
        val vm = vm()
        val before = vm.loaded().data!!.totals.sleepAverage
        sleep.confirmBedtime(wed.minusDays(1), wed.atTime(1, 30))
        val s = vm.state.first { it.data?.totals?.sleepAverage != before }
        assertNotNull(s.data!!.totals.sleepAverage)
    }
}
