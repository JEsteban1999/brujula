package app.brujula.week

import app.brujula.activities.ActivityType
import app.brujula.activities.Occurrence
import app.brujula.activities.Repeat
import app.brujula.activities.workStatus
import app.brujula.calendar.ExternalEvent
import app.brujula.calendar.eventsOnLogicalDay
import app.brujula.planning.SnapshotInputs
import app.brujula.planning.buildDaySnapshot
import app.brujula.routine.awakeWindow
import app.brujula.routine.dayTypeOf
import app.brujula.routine.logicalDay
import app.brujula.sleep.SleepRecord
import app.brujula.sleep.sleepSpan
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

enum class WeekDayState { PAST, TODAY, PLANNED }

enum class EventOrigin { BRUJULA, GOOGLE, OUTLOOK, OTHER }

fun originOf(source: String): EventOrigin = when (source) {
    "Google" -> EventOrigin.GOOGLE
    "Outlook" -> EventOrigin.OUTLOOK
    else -> EventOrigin.OTHER
}

private const val ALL_DAY_LABEL = "Todo el día"
private const val BRUJULA_LABEL = "Brújula"

sealed interface WeekEvent {
    val title: String
    val timeLabel: String
    val origin: EventOrigin
    val sourceLabel: String

    data class Own(val occurrence: Occurrence, val canSkip: Boolean) : WeekEvent {
        override val title: String get() = occurrence.activity.title
        override val timeLabel: String get() = timeText(occurrence.span.start)
        override val origin: EventOrigin get() = EventOrigin.BRUJULA
        override val sourceLabel: String get() = BRUJULA_LABEL
    }

    data class External(val event: ExternalEvent, val countsAsWork: Boolean) : WeekEvent {
        override val title: String get() = event.displayTitle
        override val timeLabel: String get() = if (event.allDay) ALL_DAY_LABEL else timeText(event.span.start)
        override val origin: EventOrigin get() = originOf(event.source)
        override val sourceLabel: String get() = event.source
    }
}

private fun timeText(at: LocalDateTime): String = "${at.hour}:${"%02d".format(at.minute)}"

data class WeekDay(
    val day: LocalDate,
    /** L M X J V S D */
    val letter: String,
    val state: WeekDayState,
    val rest: Boolean,
    val workMinutes: Int,
    val barLabel: String,
    val events: List<WeekEvent>,
    val exerciseDone: Int,
    val exerciseTotal: Int,
    val a11y: String,
)

data class WeekTotals(
    val workAverage: Int?,
    val workLabel: String,
    val exerciseDone: Int,
    val exerciseTotal: Int,
    val sleepAverage: Int?,
)

data class WeekData(
    val monday: LocalDate,
    val title: String,
    val rangeLabel: String,
    val goal: IntRange,
    val goalLabel: String,
    val days: List<WeekDay>,
    val totals: WeekTotals,
)

fun mondayOf(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

private val LETTERS = listOf("L", "M", "X", "J", "V", "S", "D")

/**
 * La semana de [monday] con las mismas cuentas de Hoy ([buildDaySnapshot] una vez por día). "Hoy" es el día lógico de
 * [now]. El promedio de trabajo solo cuenta días laborales hasta hoy (en una semana futura, los laborales planeados).
 */
fun buildWeek(monday: LocalDate, now: LocalDateTime, inputs: SnapshotInputs, sleep: Map<LocalDate, SleepRecord>): WeekData {
    val today = logicalDay(now)
    val routine = inputs.routine
    val goal = routine.workGoalMin

    val days = (0L..6L).map { offset ->
        val day = monday.plusDays(offset)
        val snap = buildDaySnapshot(day, inputs)
        val state = when {
            day < today -> WeekDayState.PAST
            day == today -> WeekDayState.TODAY
            else -> WeekDayState.PLANNED
        }
        // Detalle: ocurrencias del día lógico (sin spill) + externos del día lógico, los de todo el día primero.
        val logicalOwn = snap.own.filter { logicalDay(it.span.start) == day }
        val own = logicalOwn.map { o ->
            WeekEvent.Own(o, canSkip = o.activity.repeat != Repeat.NONE && !o.activity.copyToGoogle && day >= today)
        }
        val external = eventsOnLogicalDay(day, inputs.external, routine.awakeWindow(day))
            .map { WeekEvent.External(it, inputs.isWork(it)) }
        val events = (own + external).sortedWith(
            compareBy<WeekEvent>({ !(it is WeekEvent.External && it.event.allDay) }, { it.sortStart() }),
        )
        val exercises = logicalOwn.filter { it.activity.type == ActivityType.EXERCISE }
        val minutes = snap.summary.workMinutes
        WeekDay(
            day = day,
            letter = LETTERS[offset.toInt()],
            state = state,
            rest = dayTypeOf(day).isRest,
            workMinutes = minutes,
            barLabel = barLabel(minutes),
            events = events,
            exerciseDone = exercises.count { it.done },
            exerciseTotal = exercises.size,
            a11y = accessibleText(day, state, minutes),
        )
    }

    val counted = days.filter { !it.rest && (it.day <= today) }
    val futureWeek = monday > today
    val averaged = if (futureWeek) days.filter { !it.rest } else counted
    val avg = if (averaged.isEmpty()) null else Math.round(averaged.sumOf { it.workMinutes }.toDouble() / averaged.size).toInt()
    val workLabel = when {
        avg == null -> "–"
        futureWeek -> "Planeado"
        else -> workStatusLabel(workStatus(avg, goal))
    }

    val nights = (-1L..5L).map { monday.plusDays(it) }.mapNotNull { night ->
        val span = sleepSpan(night, routine, sleep[night])
        if (span.end <= now) span.minutes.toInt() else null
    }
    val sleepAvg = if (nights.isEmpty()) null else Math.round(nights.sum().toDouble() / nights.size).toInt()

    return WeekData(
        monday = monday,
        title = weekTitle(monday, mondayOf(today)),
        rangeLabel = weekRangeLabel(monday),
        goal = goal,
        goalLabel = goalLabel(goal),
        days = days,
        totals = WeekTotals(
            workAverage = avg,
            workLabel = workLabel,
            exerciseDone = days.sumOf { it.exerciseDone },
            exerciseTotal = days.sumOf { it.exerciseTotal },
            sleepAverage = sleepAvg,
        ),
    )
}

private fun WeekEvent.sortStart(): LocalDateTime = when (this) {
    is WeekEvent.Own -> occurrence.span.start
    is WeekEvent.External -> event.span.start
}

private fun accessibleText(day: LocalDate, state: WeekDayState, minutes: Int): String {
    val name = "${weekdayName(day).replaceFirstChar { it.uppercase() }} ${day.dayOfMonth}"
    return when {
        minutes <= 0 -> "$name: sin trabajo"
        state == WeekDayState.PLANNED -> "$name: ${durationText(minutes)} de trabajo planeado"
        else -> "$name: ${durationText(minutes)} de trabajo"
    }
}
