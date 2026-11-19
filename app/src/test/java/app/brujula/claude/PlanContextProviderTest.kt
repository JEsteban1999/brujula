package app.brujula.claude

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.activities.Activity
import app.brujula.activities.ActivityOrigin
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.calendar.ExternalEvent
import app.brujula.calendar.FakeCalendarSource
import app.brujula.calendar.WorkClassificationRepository
import app.brujula.core.time.FixedClock
import app.brujula.core.time.TimeSpan
import app.brujula.data.db.BrujulaDatabase
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitRepository
import app.brujula.habits.HabitSchedule
import app.brujula.planning.DaySnapshotProvider
import app.brujula.planning.ExerciseState
import app.brujula.routine.DayKind
import app.brujula.routine.RoutineRepository
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class PlanContextProviderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: BrujulaDatabase
    private lateinit var activities: ActivityRepository
    private lateinit var habits: HabitRepository
    private val wed = LocalDate.of(2026, 11, 18)
    private val thu = wed.plusDays(1)
    private val fri = thu.plusDays(1)
    private val sat = LocalDate.of(2026, 11, 21)
    private val calendar = FakeCalendarSource()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
        activities = ActivityRepository(db.activityDao())
        habits = HabitRepository(db.habitDao())
    }

    @After fun tearDown() { db.close() }

    private fun ext(id: Long, title: String, s: LocalDateTime, e: LocalDateTime) =
        ExternalEvent(id, 0, 1, "Trabajo", "Outlook", title, TimeSpan(s, e), false, null)

    private suspend fun TestScope.provider(now: LocalDateTime): PlanContextProvider {
        val routine = RoutineRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "r.preferences_pb") })
        routine.calendarPrefs.setSelectedCalendarIds(setOf(1L))
        val snapshots = DaySnapshotProvider(routine, activities, habits, calendar, WorkClassificationRepository(db.workClassificationDao()))
        return PlanContextProvider(FixedClock(now), snapshots, habits, routine) { ZoneId.of("America/Bogota") }
    }

    private suspend fun add(
        title: String,
        date: LocalDate,
        start: LocalTime,
        min: Int = 60,
        type: ActivityType = ActivityType.PERSONAL,
        repeat: Repeat = Repeat.NONE,
        copy: Boolean = false,
        origin: ActivityOrigin = ActivityOrigin.UNKNOWN,
    ): Long = activities.upsert(
        Activity(title = title, type = type, date = date, start = start, durationMin = min, repeat = repeat, copyToGoogle = copy, origin = origin),
    )

    @Test fun movableRulesForToday() = runTest {
        val turno = add("Turno", wed, LocalTime.of(23, 0), min = 600)
        val hecho = add("Revisión", thu, LocalTime.of(16, 0), type = ActivityType.WORK)
        activities.setDone(hecho, thu, true)
        val serieCopia = add("Gimnasio", wed, LocalTime.of(17, 0), type = ActivityType.EXERCISE, repeat = Repeat.DAILY, copy = true)
        val empezado = add("Llamadas", thu, LocalTime.of(14, 30))
        val informe = add("Informe", thu, LocalTime.of(19, 0), min = 120, type = ActivityType.WORK, origin = ActivityOrigin.DICTATION)
        val serie = add("Leer", wed, LocalTime.of(21, 0), repeat = Repeat.DAILY)

        val ctx = provider(thu.atTime(15, 0)).forDay(thu)
        val byTitle = ctx.items.associateBy { it.title }

        assertFalse("Viene de ayer (spill)", byTitle.getValue("Turno").movable)
        assertFalse("Hecho", byTitle.getValue("Revisión").movable)
        assertTrue(byTitle.getValue("Revisión").done)
        assertFalse("Recurrente con copia a Google (D4)", byTitle.getValue("Gimnasio").movable)
        assertFalse("Ya empezó a las 14:30", byTitle.getValue("Llamadas").movable)
        assertTrue(byTitle.getValue("Informe").movable)
        assertTrue("Recurrente sin copia: se mueve solo esa ocurrencia", byTitle.getValue("Leer").movable)

        assertEquals("A$turno", byTitle.getValue("Turno").ref)
        assertEquals(wed, byTitle.getValue("Turno").occurrenceDay)
        assertEquals("A$hecho", byTitle.getValue("Revisión").ref)
        assertEquals("A$serieCopia", byTitle.getValue("Gimnasio").ref)
        assertEquals("A$empezado", byTitle.getValue("Llamadas").ref)
        val inf = byTitle.getValue("Informe")
        assertEquals("A$informe", inf.ref)
        assertEquals(informe, inf.activityId)
        assertEquals(thu, inf.occurrenceDay)
        assertTrue(inf.oneOff)
        assertTrue(inf.countsAsWork)
        assertEquals(ActivityType.WORK, inf.type)
        assertEquals(ActivityOrigin.DICTATION, inf.origin)
        assertFalse(inf.external)
        assertEquals(TimeSpan(thu.atTime(19, 0), thu.atTime(21, 0)), inf.span)
        assertFalse(byTitle.getValue("Leer").oneOff)
        assertEquals("A$serie", byTitle.getValue("Leer").ref)
        assertEquals("Ordenados por hora", ctx.items.sortedBy { it.span.start }, ctx.items)
        assertEquals("Refs únicas", ctx.items.size, ctx.items.map { it.ref }.toSet().size)
    }

    @Test fun externalsAreNumberedByTime() = runTest {
        calendar.eventList = listOf(
            ext(20, "Retro", thu.atTime(17, 30), thu.atTime(18, 0)),
            ext(10, "Daily", thu.atTime(10, 0), thu.atTime(10, 15)),
        )
        val ctx = provider(wed.atTime(21, 0)).forDay(thu)
        val external = ctx.items.filter { it.external }
        assertEquals(listOf("E1" to "Daily", "E2" to "Retro"), external.map { it.ref to it.title })
        assertTrue(external.none { it.movable })
        assertTrue(external.all { it.activityId == null && it.type == null && it.origin == null })
        assertEquals("Outlook", external.first().sourceLabel)
    }

    @Test fun lunchOnThursdayAndNoneOnSaturday() = runTest {
        val p = provider(wed.atTime(21, 0))
        val thuCtx = p.forDay(thu)
        assertEquals(TimeSpan(thu.atTime(12, 0), thu.atTime(14, 0)), thuCtx.lunch)
        assertEquals(DayKind.WORKDAY, thuCtx.dayType.kind)
        val satCtx = p.forDay(sat)
        assertNull(satCtx.lunch)
        assertEquals(DayKind.WEEKEND, satCtx.dayType.kind)
    }

    @Test fun planningTomorrowKeepsTodaySummaryAndNextDayStart() = runTest {
        val gym = add("Gimnasio", thu, LocalTime.of(18, 30), type = ActivityType.EXERCISE)
        activities.setDone(gym, thu, true)
        add("Temprano", sat, LocalTime.of(7, 0))
        add("Tarde", fri, LocalTime.of(23, 0), min = 180) // cruza al sábado, pero es del viernes
        calendar.eventList = listOf(ext(1, "Vuelo", sat.atTime(9, 0), sat.atTime(10, 0)))
        habits.upsert(Habit(name = "Tomar agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(60), dailyTarget = 8))
        habits.upsert(Habit(name = "Viejo", icon = HabitIcon.GENERIC, schedule = HabitSchedule.Every(60), dailyTarget = 1, active = false))

        val ctx = provider(thu.atTime(21, 0)).forDay(fri)

        assertEquals(thu, ctx.logicalToday)
        assertEquals(fri, ctx.day)
        assertEquals(thu.atTime(21, 0), ctx.now)
        assertEquals(ZoneId.of("America/Bogota"), ctx.zone)
        assertEquals("Resumen de hoy (jueves), no del viernes", ExerciseState.DONE, ctx.today.exercise)
        assertEquals(0, ctx.today.waterCount)
        assertEquals(8, ctx.today.waterTarget)
        assertEquals(sat.atTime(7, 0), ctx.nextDayFirstStart)
        assertEquals(listOf("Tomar agua"), ctx.habitNames)
        assertEquals(TimeSpan(fri.atTime(8, 15), sat.atTime(0, 30)), ctx.awake)
        assertTrue("Mañana todo lo no hecho se puede mover", ctx.items.single { it.title == "Tarde" }.movable)
    }

    @Test fun at0030ThursdayTodayIsWednesday() = runTest {
        val ctx = provider(thu.atTime(0, 30)).forDay(thu)
        assertEquals(wed, ctx.logicalToday)
        assertEquals(thu, ctx.day)
    }
}
