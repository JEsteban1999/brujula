package app.brujula.activities

enum class WorkStatus { LOW, BELOW_GOAL, IN_GOAL, ABOVE_GOAL }

fun workMinutes(occurrences: List<Occurrence>): Int =
    occurrences.filter { it.activity.type == ActivityType.WORK }.sumOf { it.span.minutes.toInt() }

fun workStatus(minutes: Int, goal: IntRange, lowThreshold: Int = 300): WorkStatus = when {
    minutes < lowThreshold -> WorkStatus.LOW
    minutes < goal.first -> WorkStatus.BELOW_GOAL
    minutes <= goal.last -> WorkStatus.IN_GOAL
    else -> WorkStatus.ABOVE_GOAL
}
