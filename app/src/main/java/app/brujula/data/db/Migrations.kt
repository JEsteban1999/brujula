package app.brujula.data.db

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.routine.RoutineRepository
import app.brujula.habits.Habit
import app.brujula.habits.HabitGroup
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitRepository
import app.brujula.habits.HabitSchedule
import app.brujula.habits.HabitWindow
import app.brujula.habits.timesFor
import app.brujula.routine.Routine
import java.time.LocalDate
import java.time.LocalTime

object Migrations {
    /**
     * Una sola vez (bandera `wellness_seeded`): crea los nueve habitos de "Pausas y bienestar".
     * La meta diaria es el numero de avisos de un miercoles con la rutina por defecto (minimo 1).
     */
    suspend fun seedWellness(db: BrujulaDatabase, routine: RoutineRepository) {
        if (routine.isWellnessSeeded()) return
        val habits = HabitRepository(db.habitDao())
        // Si el proceso murio entre el commit y la bandera, no se siembra otra vez.
        if (habits.countWellness() > 0) {
            routine.markWellnessSeeded()
            return
        }
        val wed = LocalDate.of(2026, 11, 18)
        val base = Routine()
        fun w(
            name: String, icon: HabitIcon, schedule: HabitSchedule, window: HabitWindow, active: Boolean = true,
        ): Habit {
            val h = Habit(
                name = name, icon = icon, schedule = schedule, dailyTarget = 1, active = true,
                group = HabitGroup.WELLNESS, window = window,
            )
            return h.copy(dailyTarget = h.timesFor(wed, base).size.coerceAtLeast(1), active = active)
        }
        fun at(vararg t: Pair<Int, Int>) = HabitSchedule.AtTimes(t.map { LocalTime.of(it.first, it.second) })
        db.withTransaction {
            listOf(
                w("Descanso visual 20-20-20", HabitIcon.EYE, HabitSchedule.Every(30), HabitWindow.WORK),
                w("Pararte y moverte", HabitIcon.WALK, HabitSchedule.Every(60), HabitWindow.WORK),
                w("Estiramientos", HabitIcon.STRETCH, HabitSchedule.Every(120), HabitWindow.WORK),
                w("Postura", HabitIcon.POSTURE, at(10 to 15, 16 to 15), HabitWindow.WORK),
                w("Comer a horas", HabitIcon.FOOD, at(12 to 30, 16 to 0), HabitWindow.AWAKE),
                w("Desconectar pantallas", HabitIcon.SCREEN_OFF, HabitSchedule.BeforeBed(45), HabitWindow.AWAKE),
                w("Vitaminas", HabitIcon.PILL, at(9 to 0), HabitWindow.AWAKE, active = false),
                w("Protector solar", HabitIcon.SUN, at(9 to 0, 12 to 30), HabitWindow.AWAKE),
                w("Lectura sin pantalla", HabitIcon.BOOK, at(21 to 30), HabitWindow.AWAKE),
            ).forEach { habits.upsert(it) }
        }
        routine.markWellnessSeeded()
    }

    /**
     * Una sola vez: el "Ejercicio en casa" diario sembrado por la Fase 1 pasa a días laborales.
     * Solo toca la actividad que coincide exactamente con la sembrada; nada más.
     */
    suspend fun exerciseToWorkdays(db: BrujulaDatabase, routine: RoutineRepository) {
        if (routine.isExerciseMigrated()) return
        val activities = ActivityRepository(db.activityDao())
        db.withTransaction {
            val all = db.activityDao().observeAll().first()
            all.map { it.toModel() }
                .filter {
                    it.title == "Ejercicio en casa" && it.type == ActivityType.EXERCISE &&
                        it.repeat == Repeat.DAILY && it.start == LocalTime.of(18, 30) && it.durationMin == 30
                }
                .forEach { activities.upsert(it.copy(repeat = Repeat.WORKDAYS)) }
        }
        routine.markExerciseMigrated()
    }
}
