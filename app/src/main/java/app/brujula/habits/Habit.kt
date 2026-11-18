package app.brujula.habits

import java.time.DayOfWeek
import java.time.LocalTime

sealed interface HabitSchedule {
    /** Cada [intervalMin] minutos dentro de la ventana de vigilia. */
    data class Every(val intervalMin: Int) : HabitSchedule

    /** A horas fijas del día. */
    data class AtTimes(val times: List<LocalTime>) : HabitSchedule
}

enum class HabitIcon { WATER, LIP_BALM, STRETCH, GENERIC }

data class Habit(
    val id: Long = 0,
    val name: String,
    val icon: HabitIcon,
    val schedule: HabitSchedule,
    val dailyTarget: Int,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val active: Boolean = true,
)
