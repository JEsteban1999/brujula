package app.brujula.reminders

import app.brujula.activities.Activity
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitSchedule
import app.brujula.routine.Routine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderPlannerTest {
    private val d = LocalDate.of(2026, 11, 18)
    private val water = Habit(1, "Agua", HabitIcon.WATER, HabitSchedule.Every(90), 8)
    private fun plan(now: LocalDateTime, counts: Map<LocalDate, Map<Long, Int>> = emptyMap(), handled: Set<String> = emptySet(),
                     acts: List<Activity> = emptyList(), done: Set<Pair<Long, LocalDate>> = emptySet()) =
        ReminderPlanner.plan(now, Routine(), listOf(water), counts, handled, acts, done)

    @Test fun onlyFutureWithin24h() {
        val r = plan(d.atTime(12, 0)).filter { it.kind == ReminderKind.HABIT }
        assertEquals(d.atTime(12, 45), r.first().at)
        assertTrue(r.all { it.at > d.atTime(12, 0) && it.at <= d.atTime(12, 0).plusHours(24) })
    }

    @Test fun lateNightReminderFiresBeforeBed() {
        assertTrue(plan(d.atTime(23, 0)).any { it.at == d.atTime(23, 15) })
    }

    @Test fun noHabitReminderBetweenBedAndWake() {
        val r = plan(d.atTime(23, 0))
        assertTrue(r.none { it.at.isAfter(d.plusDays(1).atTime(0, 30)) && it.at.isBefore(d.plusDays(1).atTime(8, 15)) })
    }

    @Test fun handledKeysAndReachedTargetAreSkipped() {
        assertTrue(plan(d.atTime(12, 0), handled = setOf("habit:1:202611181245")).none { it.key == "habit:1:202611181245" })
        assertTrue(plan(d.atTime(12, 0), counts = mapOf(d to mapOf(1L to 8))).none { it.day == d && it.habitId == 1L })
    }

    @Test fun activityReminderBeforeStartUnlessDone() {
        val gym = Activity(5, "Ejercicio en casa", ActivityType.EXERCISE, d, LocalTime.of(18, 30), 30, Repeat.DAILY, remindBeforeMin = 15)
        assertTrue(plan(d.atTime(12, 0), acts = listOf(gym)).any { it.key == "act:5:20261118" && it.at == d.atTime(18, 15) })
        assertTrue(plan(d.atTime(12, 0), acts = listOf(gym), done = setOf(5L to d)).none { it.key == "act:5:20261118" })
    }

    @Test fun copyMatchesSpec() {
        val r = plan(d.atTime(12, 0), counts = mapOf(d to mapOf(1L to 3))).first { it.at == d.atTime(12, 45) }
        assertEquals("Hora de tomar agua", r.title)
        assertEquals("Vas 3 de 8 vasos.", r.body)
        val gym = Activity(5, "Ejercicio en casa", ActivityType.EXERCISE, d, LocalTime.of(18, 30), 30, Repeat.DAILY, remindBeforeMin = 15)
        assertEquals("Ejercicio en casa a las 18:30", plan(d.atTime(12, 0), acts = listOf(gym)).first { it.kind == ReminderKind.ACTIVITY }.title)
    }

    private val sat = LocalDate.of(2026, 11, 21)
    private fun prompts(now: LocalDateTime, acts: List<Activity> = emptyList(), answered: Set<LocalDate> = emptySet()) =
        ReminderPlanner.plan(now, Routine(), emptyList(), emptyMap(), emptySet(), acts, emptySet(), exercisePromptAnswered = answered)
            .filter { it.kind == ReminderKind.EXERCISE_PROMPT }

    @Test fun restDayGetsExercisePromptTwoHoursAfterWake() {
        val r = prompts(sat.atTime(9, 0)).single()
        assertEquals("exprompt:20261121", r.key)
        assertEquals(sat.atTime(12, 0), r.at)
        assertEquals("¿Te animas a hacer ejercicio hoy?", r.title)
        assertEquals("Si te sientes con ganas, te busco un hueco de 30 min.", r.body)
        assertEquals(sat, r.day)
    }

    @Test fun workdayHasNoExercisePrompt() {
        assertTrue(prompts(d.atTime(7, 0)).isEmpty())
    }

    @Test fun exerciseActivityThatDayOrAnsweredSuppressesPrompt() {
        val ex = Activity(9, "Ejercicio en casa", ActivityType.EXERCISE, sat, LocalTime.of(17, 0), 30, Repeat.NONE)
        assertTrue(prompts(sat.atTime(9, 0), acts = listOf(ex)).isEmpty())
        assertTrue(prompts(sat.atTime(9, 0), answered = setOf(sat)).isEmpty())
        val other = ex.copy(type = ActivityType.PERSONAL)
        assertEquals(1, prompts(sat.atTime(9, 0), acts = listOf(other)).size)
    }

    @Test fun skippedExerciseThatDayCountsAsAnswered() {
        // "Hoy no voy a entrenar": el plan saltó el ejercicio del sábado; no se pregunta "¿Te animas…?".
        val gym = Activity(9, "Gimnasio", ActivityType.EXERCISE, sat.minusDays(3), LocalTime.of(17, 0), 60, Repeat.DAILY)
        val skipped = mapOf((9L to sat) to app.brujula.activities.OccurrenceOverride(9, sat, null, null, true))
        val r = ReminderPlanner.plan(
            sat.atTime(9, 0), Routine(), emptyList(), emptyMap(), emptySet(), listOf(gym), emptySet(), overrides = skipped,
        ).filter { it.kind == ReminderKind.EXERCISE_PROMPT }
        assertTrue(r.toString(), r.isEmpty())
        // Saltar algo que no es ejercicio no cuenta.
        val walk = gym.copy(type = ActivityType.PERSONAL)
        val other = ReminderPlanner.plan(
            sat.atTime(9, 0), Routine(), emptyList(), emptyMap(), emptySet(), listOf(walk), emptySet(), overrides = skipped,
        ).filter { it.kind == ReminderKind.EXERCISE_PROMPT }
        assertEquals(1, other.size)
    }

    @Test fun weekdayHolidayGetsPrompt() {
        val holiday = LocalDate.of(2026, 12, 8)
        assertEquals("exprompt:20261208", prompts(holiday.atTime(9, 0)).single().key)
    }

    @Test fun pastPromptIsNotPlannedAndKeyIsStableAcrossResyncs() {
        assertTrue(prompts(sat.atTime(12, 30)).none { it.day == sat })
        assertEquals(prompts(sat.atTime(9, 0)).single().key, prompts(sat.atTime(11, 59)).single().key)
    }

    private val eye = Habit(10, "Descanso visual 20-20-20", HabitIcon.EYE, HabitSchedule.Every(30), 16,
        group = app.brujula.habits.HabitGroup.WELLNESS, window = app.brujula.habits.HabitWindow.WORK)
    private val walk = Habit(11, "Pararte y moverte", HabitIcon.WALK, HabitSchedule.Every(60), 8,
        group = app.brujula.habits.HabitGroup.WELLNESS, window = app.brujula.habits.HabitWindow.WORK)
    private fun wellness(now: LocalDateTime, counts: Map<LocalDate, Map<Long, Int>> = emptyMap(), handled: Set<String> = emptySet()) =
        ReminderPlanner.plan(now, Routine(), listOf(eye, walk), counts, handled, emptyList(), emptySet())

    @Test fun sameMinuteWellnessRemindersMergeIntoOnePausa() {
        val r = wellness(d.atTime(8, 0)).filter { it.at == d.atTime(9, 0) }.single()
        assertEquals("wellness:202611180900", r.key)
        assertEquals("Pausa", r.title)
        assertEquals(listOf(10L, 11L), r.habitIds)
        assertEquals(10L, r.habitId)
        assertEquals("Mira algo lejano 20 segundos. · Párate y camina 2 minutos.", r.body)
        assertEquals(ReminderKind.HABIT, r.kind)
        val half = wellness(d.atTime(8, 0)).filter { it.at == d.atTime(8, 30) }.single()
        assertEquals("habit:10:202611180830", half.key)
        assertEquals("Descanso visual 20-20-20", half.title)
        assertTrue(half.habitIds.isEmpty())
    }

    @Test fun groupShrinksWhenOneReachedTargetOrHandled() {
        val reached = wellness(d.atTime(8, 0), counts = mapOf(d to mapOf(11L to 8))).single { it.at == d.atTime(9, 0) }
        assertEquals("habit:10:202611180900", reached.key)
        val handled = wellness(d.atTime(8, 0), handled = setOf("habit:11:202611180900")).single { it.at == d.atTime(9, 0) }
        assertEquals("habit:10:202611180900", handled.key)
    }

    @Test fun saturdayHasNoWorkWindowWellness() {
        val r = wellness(LocalDate.of(2026, 11, 21).atTime(8, 0)).filter { it.kind == ReminderKind.HABIT }; assertTrue(r.toString(), r.isEmpty())
    }

    @Test fun screensOffBodyUsesHabitMinutes() {
        val h = Habit(30, "Desconectar pantallas", HabitIcon.SCREEN_OFF, HabitSchedule.BeforeBed(60), 1,
            group = app.brujula.habits.HabitGroup.WELLNESS)
        val r = ReminderPlanner.plan(d.atTime(20, 0), Routine(), listOf(h), emptyMap(), emptySet(), emptyList(), emptySet())
            .single { it.kind == ReminderKind.HABIT }
        assertEquals("Deja las pantallas: en 60 min te acuestas.", r.body)
    }

    @Test fun exerciseAfterMidnightFromPreviousDayDoesNotSuppressPrompt() {
        // Domingo 00:30 pertenece al sabado logico; no es ejercicio del domingo.
        val sun = sat.plusDays(1)
        val late = Activity(9, "Ejercicio", ActivityType.EXERCISE, sun, LocalTime.of(0, 30), 30, Repeat.NONE)
        val r = prompts(sat.atTime(13, 0), acts = listOf(late))
        assertEquals(listOf("exprompt:20261122"), r.map { it.key })
    }

    @Test fun movedOccurrenceRemindsAtNewTime() {
        val gym = Activity(5, "Ejercicio en casa", ActivityType.EXERCISE, d, LocalTime.of(18, 30), 30, Repeat.DAILY, remindBeforeMin = 15)
        val overrides = mapOf((5L to d) to app.brujula.activities.OccurrenceOverride(5, d, d.atTime(19, 30), null, false))
        val r = ReminderPlanner.plan(d.atTime(12, 0), Routine(), emptyList(), emptyMap(), emptySet(), listOf(gym), emptySet(), overrides = overrides)
            .single { it.key == "act:5:20261118" }
        assertEquals(d.atTime(19, 15), r.at)
        assertEquals("Ejercicio en casa a las 19:30", r.title)
    }

    @Test fun skippedOccurrenceHasNoReminder() {
        val gym = Activity(5, "Ejercicio en casa", ActivityType.EXERCISE, d, LocalTime.of(18, 30), 30, Repeat.DAILY, remindBeforeMin = 15)
        val overrides = mapOf((5L to d) to app.brujula.activities.OccurrenceOverride(5, d, null, null, true))
        val r = ReminderPlanner.plan(d.atTime(12, 0), Routine(), emptyList(), emptyMap(), emptySet(), listOf(gym), emptySet(), overrides = overrides)
        assertTrue(r.none { it.key == "act:5:20261118" })
    }

    // --- Planificacion: noche, manana y dormir ---
    private val thu = LocalDate.of(2026, 11, 19)
    private fun planning(
        now: LocalDateTime, routine: Routine = Routine(), planned: Set<LocalDate> = emptySet(),
        bedtime: app.brujula.routine.BedtimeChoice? = null,
    ) = ReminderPlanner.plan(
        now, routine, emptyList(), emptyMap(), emptySet(), emptyList(), emptySet(), plannedDays = planned, bedtime = bedtime,
    ).filter { it.kind in setOf(ReminderKind.PLAN_NIGHT, ReminderKind.PLAN_MORNING, ReminderKind.BEDTIME) }

    @Test fun nightNoticeAt2245() {
        val r = planning(thu.atTime(20, 0)).single()
        assertEquals("night:20261119", r.key)
        assertEquals(ReminderKind.PLAN_NIGHT, r.kind)
        assertEquals(thu.atTime(22, 45), r.at)
        assertEquals("¿Armamos mañana?", r.title)
        assertEquals("Mira cómo te fue hoy y deja listo tu día de mañana.", r.body)
        assertEquals(thu, r.day)
        // Pasada la hora, el de hoy ya no sale.
        assertTrue(planning(thu.atTime(23, 0)).none { it.key == "night:20261119" })
        // Misma clave entre resincronizaciones.
        assertEquals(planning(thu.atTime(20, 0)).map { it.key }, planning(thu.atTime(20, 30)).map { it.key })
    }

    @Test fun nightNoticeFollowsTheConfiguredTime() {
        val r = planning(thu.atTime(12, 0), Routine(nightNotice = LocalTime.of(21, 0))).single { it.key == "night:20261119" }
        assertEquals(thu.atTime(21, 0), r.at)
    }

    @Test fun nightNoticeSkippedWhenTomorrowPlanned() {
        assertTrue(planning(thu.atTime(20, 0), planned = setOf(thu.plusDays(1))).none { it.kind == ReminderKind.PLAN_NIGHT })
        // Un plan de hoy no apaga el aviso de esta noche.
        assertEquals(1, planning(thu.atTime(20, 0), planned = setOf(thu)).count { it.kind == ReminderKind.PLAN_NIGHT })
    }

    @Test fun nightNoticeMovesInsideAwakeWindow() {
        val early = Routine(bed = LocalTime.of(22, 0))
        val r = planning(thu.atTime(12, 0), early).single { it.key == "night:20261119" }
        assertEquals(thu.atTime(21, 30), r.at)
        // Con la hora de acostarse pasada la medianoche, 22:45 sigue siendo vigilia: no se mueve.
        assertEquals(thu.atTime(22, 45), planning(thu.atTime(12, 0), Routine(bed = LocalTime.of(0, 30))).single { it.key == "night:20261119" }.at)
    }

    @Test fun nightNoticeAfterMidnightCountsTheLogicalDay() {
        // A las 00:30 del viernes sigue siendo la noche del jueves: el aviso del jueves ya paso y el siguiente es el del viernes.
        val fri = thu.plusDays(1)
        val r = planning(fri.atTime(0, 30))
        assertEquals(listOf("night:20261120"), r.map { it.key })
        assertEquals(fri.atTime(22, 45), r.single().at)
    }

    @Test fun morningAtWakeOnlyForPlannedDays() {
        val fri = thu.plusDays(1)
        val sat = LocalDate.of(2026, 11, 21)
        val now = fri.atTime(23, 0)
        val r = planning(now, planned = setOf(sat)).single { it.kind == ReminderKind.PLAN_MORNING }
        assertEquals("morning:20261121", r.key)
        assertEquals(sat.atTime(10, 0), r.at)
        assertEquals("Tu día está listo", r.title)
        assertEquals("Toca para ver tu día.", r.body)
        assertEquals(sat, r.day)
        assertTrue(planning(now).none { it.kind == ReminderKind.PLAN_MORNING })
        // Viernes laboral: levantarse 8:15.
        assertEquals(fri.atTime(8, 15), planning(thu.atTime(23, 0), planned = setOf(fri)).single { it.kind == ReminderKind.PLAN_MORNING }.at)
    }

    @Test fun bedtimeReminderOnlyWhenOnAndFuture() {
        val bed = app.brujula.routine.BedtimeChoice(thu, thu.plusDays(1).atTime(0, 15), 480, remind = true)
        val on = planning(thu.atTime(20, 0), bedtime = bed).single { it.kind == ReminderKind.BEDTIME }
        assertEquals("bed:202611192345", on.key)
        assertEquals(thu.atTime(23, 45), on.at)
        assertEquals("Hora de ir a dormir", on.title)
        assertEquals("Para dormir 8 h, acuéstate a las 0:15.", on.body)
        assertTrue(planning(thu.atTime(20, 0), bedtime = bed.copy(remind = false)).none { it.kind == ReminderKind.BEDTIME })
        assertTrue(planning(thu.atTime(23, 45), bedtime = bed).none { it.kind == ReminderKind.BEDTIME })
        assertTrue(planning(thu.atTime(23, 50), bedtime = bed).none { it.kind == ReminderKind.BEDTIME })
    }

    @Test fun bedtimeAfterMidnightKeepsLogicalDay() {
        val bed = app.brujula.routine.BedtimeChoice(thu, thu.plusDays(1).atTime(0, 15), 480, remind = true)
        val r = planning(thu.atTime(20, 0), bedtime = bed).single { it.kind == ReminderKind.BEDTIME }
        // Dormir 00:15 del viernes: el aviso cae 23:45 del jueves y pertenece al dia logico del jueves.
        assertEquals(thu.atTime(23, 45), r.at)
        assertEquals(thu, r.day)
        // A las 00:05 del viernes el aviso del jueves ya paso.
        assertTrue(planning(thu.plusDays(1).atTime(0, 5), bedtime = bed).none { it.kind == ReminderKind.BEDTIME })
    }

    @Test fun nightNoticeFollowsAnEarlierBedtime() {
        // Una cita temprano mañana pulla acostarse a las 22:00 (aviso de dormir 21:30): el de armar mañana sale 30 min
        // antes de ese aviso (21:00), no a las 22:45 ni en el mismo minuto que el de dormir.
        val bed = app.brujula.routine.BedtimeChoice(thu, thu.atTime(22, 0), 480, remind = true)
        val all = planning(thu.atTime(12, 0), bedtime = bed)
        assertEquals(thu.atTime(21, 0), all.single { it.kind == ReminderKind.PLAN_NIGHT }.at)
        assertEquals(thu.atTime(21, 30), all.single { it.kind == ReminderKind.BEDTIME }.at)
        // Dormir 23:00 (aviso 22:30): el de armar mañana, 22:00.
        val eleven = bed.copy(bedtime = thu.atTime(23, 0))
        assertEquals(thu.atTime(22, 0), planning(thu.atTime(12, 0), bedtime = eleven).single { it.kind == ReminderKind.PLAN_NIGHT }.at)
        // Solo a futuro: a las 21:10 el de las 21:00 ya pasó.
        assertTrue(planning(thu.atTime(21, 10), bedtime = bed).none { it.kind == ReminderKind.PLAN_NIGHT && it.day == thu })
        // Una hora de dormir tardía no atrasa el aviso: manda la hora configurada.
        val late = app.brujula.routine.BedtimeChoice(thu, thu.plusDays(1).atTime(0, 15), 480, remind = false)
        assertEquals(thu.atTime(22, 45), planning(thu.atTime(12, 0), bedtime = late).single { it.kind == ReminderKind.PLAN_NIGHT }.at)
    }
}
