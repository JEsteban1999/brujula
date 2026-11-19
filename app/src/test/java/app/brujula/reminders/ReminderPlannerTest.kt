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
}
