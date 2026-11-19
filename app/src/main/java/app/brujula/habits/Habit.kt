package app.brujula.habits

import java.time.DayOfWeek
import java.time.LocalTime

sealed interface HabitSchedule {
    /** Cada [intervalMin] minutos dentro de la ventana de vigilia. */
    data class Every(val intervalMin: Int) : HabitSchedule

    /** A horas fijas del día. */
    data class AtTimes(val times: List<LocalTime>) : HabitSchedule

    /** Una vez al día, [minutes] minutos antes de acostarse. */
    data class BeforeBed(val minutes: Int) : HabitSchedule
}

enum class HabitIcon { WATER, LIP_BALM, STRETCH, GENERIC, EYE, WALK, POSTURE, FOOD, SCREEN_OFF, PILL, SUN, BOOK }

enum class HabitGroup { ESSENTIAL, WELLNESS }

/** AWAKE = toda la vigilia; WORK = solo dentro de los bloques de trabajo en días laborales. */
enum class HabitWindow { AWAKE, WORK }

data class Habit(
    val id: Long = 0,
    val name: String,
    val icon: HabitIcon,
    val schedule: HabitSchedule,
    val dailyTarget: Int,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val active: Boolean = true,
    val group: HabitGroup = HabitGroup.ESSENTIAL,
    val window: HabitWindow = HabitWindow.AWAKE,
)
