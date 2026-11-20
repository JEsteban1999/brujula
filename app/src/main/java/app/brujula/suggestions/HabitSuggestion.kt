package app.brujula.suggestions

import app.brujula.core.time.formatTime
import app.brujula.habits.HabitSchedule
import java.time.LocalDateTime

/** Los tres cambios que puede proponer una sugerencia (D13); el nombre en minúscula es el `change` del schema. */
enum class SuggestionKind { INTERVAL, TIMES, TARGET }

/** Lo que cambia una sugerencia de un hábito: su horario y su meta diaria. */
data class HabitChange(val schedule: HabitSchedule, val dailyTarget: Int)

/** Una sugerencia ya validada: [before] es el hábito al sugerir y [after] lo que quedaría si el usuario acepta. */
data class HabitSuggestion(
    val habitId: Long,
    val habitName: String,
    val kind: SuggestionKind,
    val message: String,
    val before: HabitChange,
    val after: HabitChange,
    val createdAt: LocalDateTime,
)

/** Texto de la tarjeta después de aceptar ("Listo: …"). */
fun HabitSuggestion.doneText(): String = when (kind) {
    SuggestionKind.INTERVAL -> {
        val after = after.schedule as? HabitSchedule.Every
        val faster = after?.faster
        when {
            after == null -> "Listo."
            faster != null && faster != (before.schedule as? HabitSchedule.Every)?.faster ->
                "Listo: desde las ${formatTime(faster.from)} te aviso cada ${faster.intervalMin} min."
            else -> "Listo: te aviso cada ${after.intervalMin} min."
        }
    }
    SuggestionKind.TIMES -> {
        val t = (after.schedule as? HabitSchedule.AtTimes)?.times.orEmpty().sorted().map { formatTime(it) }
        when (t.size) {
            0 -> "Listo."
            1 -> "Listo: te aviso a las ${t[0]}."
            else -> "Listo: te aviso a las ${t.dropLast(1).joinToString(", ")} y ${t.last()}."
        }
    }
    SuggestionKind.TARGET -> "Listo: tu meta ahora es ${after.dailyTarget} al día."
}
