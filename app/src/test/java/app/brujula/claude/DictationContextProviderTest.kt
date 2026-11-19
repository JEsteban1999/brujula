package app.brujula.claude

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.calendar.ExternalEvent
import app.brujula.calendar.FakeCalendarSource
import app.brujula.core.time.FixedClock
import app.brujula.core.time.TimeSpan
import app.brujula.data.db.BrujulaDatabase
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitRepository
import app.brujula.habits.HabitSchedule
import app.brujula.routine.DayKind
import app.brujula.routine.RoutineRepository
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class DictationContextProviderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: BrujulaDatabase
    private lateinit var activities: ActivityRepository
    private lateinit var habits: HabitRepository
    private val wed = LocalDate.of(2026, 11, 18)
    private val thu = wed.plusDays(1)
    private val calendar = FakeCalendarSource()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        activities = ActivityRepository(db.activityDao())
        habits = HabitRepository(db.habitDao())
    }

    @After fun tearDown() { db.close() }

    private fun ext(id: Long, title: String, s: LocalDateTime, e: LocalDateTime, allDay: Boolean = false) =
        ExternalEvent(id, 0, 1, "Personal", "Google", title, TimeSpan(s, e), allDay, null)

    private suspend fun TestScope.provider(now: LocalDateTime): DictationContextProvider {
        val routine = RoutineRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "r.preferences_pb") })
        routine.calendarPrefs.setSelectedCalendarIds(setOf(1L))
        return DictationContextProvider(FixedClock(now), routine, activities, habits, calendar) { ZoneId.of("America/Bogota") }
    }

    @Test fun at0030ThursdayTheContextUsesWednesdayAsToday() = runTest {
        activities.upsert(Activity(title = "Dentista", type = ActivityType.APPOINTMENT, date = wed, start = LocalTime.of(15, 0), durationMin = 60))
        activities.upsert(Activity(title = "Gimnasio", type = ActivityType.EXERCISE, date = thu, start = LocalTime.of(18, 30), durationMin = 60))
        val linked = activities.upsert(Activity(title = "Copia", type = ActivityType.APPOINTMENT, date = wed, start = LocalTime.of(17, 0), durationMin = 30))
        activities.setGoogleEventId(linked, 99L)
        calendar.eventList = listOf(
            ext(1, "Reunión", wed.atTime(9, 0), wed.atTime(10, 0)),
            ext(2, "Cumpleaños", wed.atStartOfDay(), thu.atStartOfDay(), allDay = true),
            ext(99, "Copia", wed.atTime(17, 0), wed.atTime(17, 30)),
            ext(3, "Almuerzo", thu.atTime(12, 0), thu.atTime(13, 0)),
        )
        habits.upsert(Habit(name = "Tomar agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(60), dailyTarget = 10))
        habits.upsert(Habit(name = "Viejo", icon = HabitIcon.GENERIC, schedule = HabitSchedule.Every(60), dailyTarget = 10, active = false))

        val ctx = provider(thu.atTime(0, 30)).current()

        assertEquals(wed, ctx.logicalToday)
        assertEquals(thu.atTime(0, 30), ctx.now)
        assertEquals(DayKind.WORKDAY, ctx.todayType.kind)
        assertEquals(
            listOf("Reunión" to true, "Dentista" to false, "Copia" to false),
            ctx.busyToday.map { it.title to it.external },
        )
        assertEquals(wed.atTime(9, 0), ctx.busyToday.first().start)
        assertEquals(listOf("Almuerzo", "Gimnasio"), ctx.busyTomorrow.map { it.title })
        assertEquals(listOf("Tomar agua"), ctx.habitNames)
        assertEquals(ZoneId.of("America/Bogota"), ctx.zone)
    }

    @Test fun queuedDictationUsesTheLogicalDayOfTheDictationInstantInDeviceZone() = runTest {
        val mon = LocalDate.of(2026, 11, 23)
        val tue = mon.plusDays(1)
        activities.upsert(Activity(title = "Lunes", type = ActivityType.PERSONAL, date = mon, start = LocalTime.of(20, 0), durationMin = 30))
        activities.upsert(Activity(title = "Martes", type = ActivityType.PERSONAL, date = tue, start = LocalTime.of(10, 0), durationMin = 30))
        val at = mon.atTime(23, 0).atZone(ZoneId.of("America/Bogota")).toInstant().toEpochMilli()

        val provider = provider(tue.atTime(8, 0))
        val ctx = provider.current(at)

        assertEquals(mon, ctx.logicalToday)
        assertEquals(mon.atTime(23, 0), ctx.dictatedAt)
        assertEquals(tue.atTime(8, 0), ctx.now)
        assertEquals(listOf("Lunes"), ctx.busyToday.map { it.title })
        assertEquals(listOf("Martes"), ctx.busyTomorrow.map { it.title })
        assertEquals(null, provider.current().dictatedAt)
    }

    @Test fun withoutReadPermissionOnlyOwnItems() = runTest {
        calendar.permission = false
        calendar.eventList = listOf(ext(1, "Reunión", wed.atTime(9, 0), wed.atTime(10, 0)))
        activities.upsert(Activity(title = "Dentista", type = ActivityType.APPOINTMENT, date = wed, start = LocalTime.of(15, 0), durationMin = 60))
        val ctx = provider(wed.atTime(10, 0)).current()
        assertEquals(listOf("Dentista"), ctx.busyToday.map { it.title })
        assertTrue(ctx.busyTomorrow.isEmpty())
        assertEquals(0, calendar.eventQueries)
    }
}
