package app.brujula.data.db

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.routine.RoutineRepository
import java.time.LocalTime

object Migrations {
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
