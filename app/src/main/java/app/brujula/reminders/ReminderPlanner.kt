package app.brujula.reminders

import app.brujula.activities.Activity
import app.brujula.activities.ActivityType
import app.brujula.activities.Overrides
import app.brujula.activities.occurrencesOn
import app.brujula.core.time.formatTime
import app.brujula.habits.Habit
import app.brujula.habits.HabitGroup
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitSchedule
import app.brujula.routine.logicalDay
import app.brujula.habits.timesFor
import app.brujula.core.time.formatMinutes
import app.brujula.planning.MORNING_FALLBACK_BODY
import app.brujula.routine.BedtimeChoice
import app.brujula.routine.Routine
import app.brujula.routine.awakeWindow
import app.brujula.routine.dayTypeOf
import app.brujula.routine.wakeFor
import app.brujula.sleep.SleepRecord
import app.brujula.sleep.sleepSpan
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object ReminderPlanner {
    private val KEY_MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
    private val KEY_DAY = DateTimeFormatter.ofPattern("yyyyMMdd")
    private const val NIGHT_BEFORE_BED_MIN = 30L

    /** Clave del aviso nocturno de [day] (el que pregunta por el día siguiente). */
    fun nightKey(day: LocalDate): String = "night:${day.format(KEY_DAY)}"

    /** Clave del aviso de la ocurrencia de [activityId] en [day] (fecha de la serie). */
    fun activityKey(activityId: Long, day: LocalDate): String = "act:$activityId:${day.format(KEY_DAY)}"

    /** Clave de la espera "En 15 min" de ese aviso (ver NotificationActionReceiver). */
    fun activitySnoozeKey(activityId: Long, day: LocalDate): String = activityKey(activityId, day) + NotificationActionReceiver.SNOOZE_SUFFIX

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
        overrides: Overrides = emptyMap(),
        /** Días (lógicos) con un plan ACCEPTED: solo ellos reciben el resumen de la mañana, y su noche anterior no avisa. */
        plannedDays: Set<LocalDate> = emptySet(),
        bedtime: BedtimeChoice? = null,
        /** Registros de sueño por noche (día lógico): tras un "Ya me acuesto" los hábitos callan hasta que se despierta. */
        sleep: Map<LocalDate, SleepRecord> = emptyMap(),
    ): List<Reminder> {
        val limit = now.plusHours(horizonHours)
        // La vigilia de ayer puede cruzar la medianoche; la de manana puede caer dentro del horizonte.
        val days = (-1L..1L).map { now.toLocalDate().plusDays(it) }
        val out = mutableListOf<Reminder>()
        // Solo las noches con "Ya me acuesto" registrado; sin él, la noche de la rutina ya no trae avisos de hábitos.
        val asleep = sleep.values.filter { it.bedAt != null }.map { sleepSpan(it.night, routine, it) }

        for (habit in habits) {
            for (day in days) {
                val count = habitCounts[day]?.get(habit.id) ?: 0
                if (count >= habit.dailyTarget) continue
                for (at in habit.timesFor(day, routine)) {
                    if (at <= now || at > limit) continue
                    if (asleep.any { at >= it.start && at < it.end }) continue
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
            for (occ in occurrencesOn(day, activities, doneKeys, overrides)) {
                if (occ.done) continue
                val before = occ.activity.remindBeforeMin ?: continue
                val at = occ.span.start.minusMinutes(before.toLong())
                if (at <= now || at > limit) continue
                out += Reminder(
                    key = activityKey(occ.activity.id, day), at = at, kind = ReminderKind.ACTIVITY,
                    title = "${occ.activity.title} a las ${formatTime(occ.span.start.toLocalTime())}",
                    body = "Empieza en $before min.",
                    activityId = occ.activity.id, day = day,
                )
            }
        }
        for (day in days) {
            if (!dayTypeOf(day).isRest || day in exercisePromptAnswered) continue
            // Saltar el ejercicio de ese día (p. ej. "hoy no voy a entrenar" en un plan) ya es una respuesta.
            val skippedExercise = overrides.any { (key, o) ->
                o.skipped && key.second == day && activities.any { it.id == key.first && it.type == ActivityType.EXERCISE }
            }
            if (skippedExercise) continue
            // Cuenta por dia logico: un ejercicio a las 00:30 del dia siguiente es de este dia; el de hoy a las 00:30 no.
            val exerciseToday = (occurrencesOn(day, activities, doneKeys, overrides) + occurrencesOn(day.plusDays(1), activities, doneKeys, overrides))
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
        planningReminders(now, limit, days, routine, plannedDays, bedtime, out)
        return out.sortedBy { it.at }
    }

    /** Aviso de la noche, resumen de la mañana y hora de dormir (D12, D13, D14); claves estables entre resincronizaciones. */
    private fun planningReminders(
        now: LocalDateTime, limit: LocalDateTime, days: List<LocalDate>, routine: Routine,
        plannedDays: Set<LocalDate>, bedtime: BedtimeChoice?, out: MutableList<Reminder>,
    ) {
        for (day in days) {
            // Por dia logico: la noche de `day` cae en su fecha de calendario; si la hora de acostarse es mas temprana, se corre.
            if (day.plusDays(1) !in plannedDays) {
                val window = routine.awakeWindow(day)
                // Una hora de dormir más temprana (p. ej. una cita temprano mañana) adelanta el aviso para armar el día.
                var at = day.atTime(routine.nightNotice)
                // Media hora antes del aviso de dormir, no en el mismo minuto.
                if (bedtime != null && bedtime.day == day) at = minOf(at, bedtime.remindAt.minusMinutes(NIGHT_BEFORE_BED_MIN))
                if (at < window.start) at = window.start
                if (at !in window) at = window.end.minusMinutes(NIGHT_BEFORE_BED_MIN)
                if (at > now && at <= limit) {
                    out += Reminder(
                        key = nightKey(day), at = at, kind = ReminderKind.PLAN_NIGHT,
                        title = "¿Armamos mañana?", body = "Mira cómo te fue hoy y deja listo tu día de mañana.", day = day,
                    )
                }
            }
            if (day in plannedDays) {
                val at = day.atTime(routine.wakeFor(day))
                if (at > now && at <= limit) {
                    out += Reminder(
                        key = "morning:${day.format(KEY_DAY)}", at = at, kind = ReminderKind.PLAN_MORNING,
                        title = "Tu día está listo", body = MORNING_FALLBACK_BODY, day = day,
                    )
                }
            }
        }
        if (bedtime != null && bedtime.remind && bedtime.remindAt > now) {
            out += Reminder(
                key = "bed:${bedtime.remindAt.format(KEY_MINUTE)}", at = bedtime.remindAt, kind = ReminderKind.BEDTIME,
                title = "Hora de ir a dormir",
                body = "Para dormir ${formatMinutes(bedtime.sleepMinutes)}, acuéstate a las ${formatTime(bedtime.bedtime.toLocalTime())}.",
                day = bedtime.day,
            )
        }
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
