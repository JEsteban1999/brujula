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
}
