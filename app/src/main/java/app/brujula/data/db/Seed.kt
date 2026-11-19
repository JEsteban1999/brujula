package app.brujula.data.db

import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.habits.Habit
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitRepository
import app.brujula.habits.HabitSchedule
import androidx.room.withTransaction
import app.brujula.routine.RoutineRepository
import java.time.LocalDate
import java.time.LocalTime

object Seed {
    /**
     * Siembra los valores por defecto una sola vez (marca "seeded" en DataStore), de forma atomica:
     * si algo falla se revierte todo y la marca no se escribe. Borrar los datos despues no los repone.
     */
    suspend fun ensureDefaults(db: BrujulaDatabase, routine: RoutineRepository, today: LocalDate) {
        if (routine.isSeeded()) return
        val activities = ActivityRepository(db.activityDao())
        val habits = HabitRepository(db.habitDao())
        db.withTransaction {
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
            activities.upsert(
                Activity(
                    title = "Ejercicio en casa", type = ActivityType.EXERCISE, date = today,
                    start = LocalTime.of(18, 30), durationMin = 30, repeat = Repeat.WORKDAYS,
                ),
            )
        }
        routine.markSeeded()
        Migrations.seedWellness(db, routine)
    }
}
