package app.brujula.planning

import app.brujula.activities.ActivityOrigin
import app.brujula.activities.ActivityType
import app.brujula.claude.PlanChange
import app.brujula.claude.PlanContext
import app.brujula.claude.PlanItemRef
import app.brujula.claude.lunchWindow
import app.brujula.core.time.TimeSpan
import app.brujula.routine.Routine
import app.brujula.routine.awakeWindow
import app.brujula.routine.dayTypeOf
import app.brujula.routine.logicalDay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Datos de prueba de planificación, compartidos por las pruebas del validador y de DayPlanner. */
object PlanFixtures {
    val WED: LocalDate = LocalDate.of(2026, 11, 18)
    val THU: LocalDate = WED.plusDays(1)
    val FRI: LocalDate = WED.plusDays(2)
    val SAT: LocalDate = WED.plusDays(3)

    fun own(
        id: Long,
        title: String,
        s: LocalDateTime,
        e: LocalDateTime,
        type: ActivityType = ActivityType.PERSONAL,
        movable: Boolean = true,
        done: Boolean = false,
        oneOff: Boolean = true,
        origin: ActivityOrigin = ActivityOrigin.UNKNOWN,
        ref: String = "A$id",
    ) = PlanItemRef(
        ref = ref, title = title, span = TimeSpan(s, e), external = false, type = type, movable = movable, done = done,
        countsAsWork = type == ActivityType.WORK, activityId = id, occurrenceDay = logicalDay(s), oneOff = oneOff,
        origin = origin, sourceLabel = null,
    )

    fun ext(n: Int, title: String, s: LocalDateTime, e: LocalDateTime, source: String = "Outlook", work: Boolean = false) =
        PlanItemRef(
            ref = "E$n", title = title, span = TimeSpan(s, e), external = true, type = null, movable = false, done = false,
            countsAsWork = work, activityId = null, occurrenceDay = null, oneOff = false, origin = null, sourceLabel = source,
        )

    fun ctx(
        now: LocalDateTime,
        day: LocalDate,
        items: List<PlanItemRef> = emptyList(),
        routine: Routine = Routine(),
        nextDayFirstStart: LocalDateTime? = null,
    ) = PlanContext(
        now = now, zone = ZoneId.of("America/Bogota"), logicalToday = logicalDay(now), day = day, dayType = dayTypeOf(day),
        routine = routine, awake = routine.awakeWindow(day), lunch = routine.lunchWindow(day), items = items.sortedBy { it.span.start },
        nextDayFirstStart = nextDayFirstStart, habitNames = listOf("Tomar agua"),
        today = DaySummary(270, 360..420, 3, 8, ExerciseState.PENDING),
    )

    fun create(title: String?, type: String?, date: LocalDate, start: String, durationMin: Int?) =
        PlanChange(op = "create", title = title, activityType = type, date = date.toString(), start = start, durationMin = durationMin)

    fun move(ref: String, date: LocalDate, start: String, durationMin: Int? = null) =
        PlanChange(op = "move", ref = ref, date = date.toString(), start = start, durationMin = durationMin)

    fun skip(ref: String) = PlanChange(op = "skip", ref = ref)
}
