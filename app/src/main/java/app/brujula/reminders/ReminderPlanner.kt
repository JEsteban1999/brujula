package app.brujula.reminders

import app.brujula.activities.Activity
import app.brujula.activities.ActivityType
import app.brujula.activities.occurrencesOn
import app.brujula.core.time.formatTime
import app.brujula.habits.Habit
import app.brujula.habits.HabitGroup
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitSchedule
import app.brujula.routine.logicalDay
import app.brujula.habits.timesFor
import app.brujula.routine.Routine
import app.brujula.routine.awakeWindow
import app.brujula.routine.dayTypeOf
import app.brujula.routine.wakeFor
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

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
        exercisePromptAnswered: Set<LocalDate> = emptySet(),
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
                        habitId = habit.id, day = day, wellness = habit.group == HabitGroup.WELLNESS,
                    )
                }
            }
        }

        val wellnessOnes = out.filter { it.wellness }
        out.removeAll(wellnessOnes.toSet())
        out += mergeWellness(wellnessOnes)

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
        for (day in days) {
            if (!dayTypeOf(day).isRest || day in exercisePromptAnswered) continue
            // Cuenta por dia logico: un ejercicio a las 00:30 del dia siguiente es de este dia; el de hoy a las 00:30 no.
            val exerciseToday = (occurrencesOn(day, activities, doneKeys) + occurrencesOn(day.plusDays(1), activities, doneKeys))
                .any { it.activity.type == ActivityType.EXERCISE && logicalDay(it.span.start) == day }
            if (exerciseToday) continue
            val at = day.atTime(routine.wakeFor(day)).plusHours(2)
            if (at <= now || at > limit || at !in routine.awakeWindow(day)) continue
            out += Reminder(
                key = "exprompt:${day.format(KEY_DAY)}", at = at, kind = ReminderKind.EXERCISE_PROMPT,
                title = "¿Te animas a hacer ejercicio hoy?",
                body = "Si te sientes con ganas, te busco un hueco de 30 min.",
                day = day,
            )
        }
        return out.sortedBy { it.at }
    }

    /** Une los avisos de bienestar que caen en el mismo minuto; uno solo queda como aviso normal. */
    private fun mergeWellness(items: List<Reminder>): List<Reminder> =
        items.groupBy { it.at.truncatedTo(ChronoUnit.MINUTES) }.values.map { group ->
            if (group.size == 1) return@map group.single()
            val first = group.first()
            Reminder(
                key = "wellness:${first.at.format(KEY_MINUTE)}", at = first.at, kind = ReminderKind.HABIT,
                title = "Pausa", body = group.joinToString(" \u00b7 ") { it.body },
                habitId = first.habitId, habitIds = group.mapNotNull { it.habitId }, day = first.day, wellness = true,
            )
        }

    private fun wellnessBody(h: Habit): String? = when (h.icon) {
        HabitIcon.EYE -> "Mira algo lejano 20 segundos."
        HabitIcon.WALK -> "P\u00e1rate y camina 2 minutos."
        HabitIcon.STRETCH -> "Estira cuello, espalda y mu\u00f1ecas."
        HabitIcon.POSTURE -> "Endereza la espalda y relaja los hombros."
        HabitIcon.FOOD -> "Hora de comer algo."
        HabitIcon.SCREEN_OFF -> (h.schedule as? HabitSchedule.BeforeBed)
            ?.let { "Deja las pantallas: en ${it.minutes} min te acuestas." }
            ?: "Deja las pantallas y prepárate para dormir."
        HabitIcon.PILL -> "T\u00f3mate las vitaminas."
        HabitIcon.SUN -> "Ponte protector solar."
        HabitIcon.BOOK -> "Un rato de lectura sin pantalla."
        else -> null
    }

    private fun habitTitle(h: Habit) = when (h.icon) {
        HabitIcon.WATER -> "Hora de tomar agua"
        HabitIcon.LIP_BALM -> "Bálsamo labial"
        else -> h.name
    }

    private fun habitBody(h: Habit, count: Int) = when (h.icon) {
        HabitIcon.WATER -> "Vas $count de ${h.dailyTarget} vasos."
        HabitIcon.LIP_BALM -> "Vez ${count + 1} de ${h.dailyTarget} del día."
        else -> wellnessBody(h) ?: "Vas $count de ${h.dailyTarget} hoy."
    }
}
