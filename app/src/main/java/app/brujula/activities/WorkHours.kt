package app.brujula.activities

import app.brujula.calendar.ExternalEvent
import app.brujula.core.time.TimeSpan

enum class WorkStatus { LOW, BELOW_GOAL, IN_GOAL, ABOVE_GOAL }

/**
 * Minutos de trabajo: bloques propios de tipo Trabajo mas los eventos externos ya clasificados como trabajo
 * ([externalWork]); los de todo el dia no cuentan y, si se da [logicalSpan], solo la parte dentro del dia logico.
 */
fun workMinutes(
    occurrences: List<Occurrence>,
    externalWork: List<ExternalEvent> = emptyList(),
    logicalSpan: TimeSpan? = null,
): Int {
    val own = occurrences.filter { it.activity.type == ActivityType.WORK }.sumOf { it.span.minutes.toInt() }
    val external = externalWork.filter { !it.allDay }.sumOf { e ->
        val start = if (logicalSpan != null) maxOf(e.span.start, logicalSpan.start) else e.span.start
        val end = if (logicalSpan != null) minOf(e.span.end, logicalSpan.end) else e.span.end
        if (end > start) TimeSpan(start, end).minutes.toInt() else 0
    }
    return own + external
}

fun workStatus(minutes: Int, goal: IntRange, lowThreshold: Int = 300): WorkStatus = when {
    minutes < lowThreshold -> WorkStatus.LOW
    minutes < goal.first -> WorkStatus.BELOW_GOAL
    minutes <= goal.last -> WorkStatus.IN_GOAL
    else -> WorkStatus.ABOVE_GOAL
}
