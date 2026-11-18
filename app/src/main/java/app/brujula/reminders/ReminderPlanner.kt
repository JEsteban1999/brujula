package app.brujula.reminders

import app.brujula.activities.Activity
import app.brujula.activities.occurrencesOn
import app.brujula.core.time.formatTime
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.timesFor
import app.brujula.routine.Routine
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ReminderPlanner {
    private val KEY_MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
    private val KEY_DAY = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** Avisos futuros dentro de [horizonHours]; [habitCounts] va por dia logico (el dia de la vigilia). */
    fun plan(
        now: LocalDateTime,
        routine: Routine,
        habits: List<Habit>,
        habitCounts: Map<LocalDate, Map<Long, Int>>,
        handledKeys: Set<String>,
        activities: List<Activity>,
        doneKeys: Set<Pair<Long, LocalDate>>,
        horizonHours: Long = 24,
    ): List<Reminder> {
        val limit = now.plusHours(horizonHours)
        // La vigilia de ayer puede cruzar la medianoche; la de manana puede caer dentro del horizonte.
        val days = (-1L..1L).map { now.toLocalDate().plusDays(it) }
        val out = mutableListOf<Reminder>()

        for (habit in habits) {
            for (day in days) {
                val count = habitCounts[day]?.get(habit.id) ?: 0
                if (count >= habit.dailyTarget) continue
                for (at in habit.timesFor(day, routine)) {
                    if (at <= now || at > limit) continue
                    val key = "habit:${habit.id}:${at.format(KEY_MINUTE)}"
                    if (key in handledKeys) continue
                    out += Reminder(
                        key = key, at = at, kind = ReminderKind.HABIT,
                        title = habitTitle(habit), body = habitBody(habit, count),
                        habitId = habit.id, day = day,
                    )
                }
            }
        }

        for (day in days) {
            for (occ in occurrencesOn(day, activities, doneKeys)) {
                if (occ.done) continue
                val before = occ.activity.remindBeforeMin ?: continue
                val at = occ.span.start.minusMinutes(before.toLong())
                if (at <= now || at > limit) continue
                out += Reminder(
                    key = "act:${occ.activity.id}:${day.format(KEY_DAY)}", at = at, kind = ReminderKind.ACTIVITY,
                    title = "${occ.activity.title} a las ${formatTime(occ.activity.start)}",
                    body = "Empieza en $before min.",
                    activityId = occ.activity.id, day = day,
                )
            }
        }
        return out.sortedBy { it.at }
    }

    private fun habitTitle(h: Habit) = when (h.icon) {
        HabitIcon.WATER -> "Hora de tomar agua"
        HabitIcon.LIP_BALM -> "Bálsamo labial"
        else -> h.name
    }

    private fun habitBody(h: Habit, count: Int) = when (h.icon) {
        HabitIcon.WATER -> "Vas $count de ${h.dailyTarget} vasos."
        HabitIcon.LIP_BALM -> "Vez ${count + 1} de ${h.dailyTarget} del día."
        else -> "Vas $count de ${h.dailyTarget} hoy."
    }
}
