package app.brujula.data.db

import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitRepository
import app.brujula.habits.HabitSchedule
import java.time.LocalDate
import java.time.LocalTime

object Seed {
    /** Inserta los valores por defecto solo si las tablas estan vacias. */
    suspend fun ensureDefaults(activities: ActivityRepository, habits: HabitRepository, today: LocalDate) {
        if (habits.count() == 0) {
            habits.upsert(
                Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8),
            )
            habits.upsert(
                Habit(
                    name = "Bálsamo labial", icon = HabitIcon.LIP_BALM,
                    schedule = HabitSchedule.AtTimes(listOf(9, 13, 17, 21).map { LocalTime.of(it, 0) }),
                    dailyTarget = 4,
                ),
            )
        }
        if (activities.count() == 0) {
            activities.upsert(
                Activity(
                    title = "Ejercicio en casa", type = ActivityType.EXERCISE, date = today,
                    start = LocalTime.of(18, 30), durationMin = 30, repeat = Repeat.DAILY,
                ),
            )
        }
    }
}
