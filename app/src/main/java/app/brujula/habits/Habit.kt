package app.brujula.habits

import java.time.DayOfWeek
import java.time.LocalTime

sealed interface HabitSchedule {
    /**
     * Cada [intervalMin] minutos dentro de la ventana de vigilia. [faster]: desde una hora, otro intervalo (más seguido); solo
     * en la ventana AWAKE (en WORK se ignora). Se guarda en el mismo texto del horario (`every:90|15:00/60`, ver Converters).
     */
    data class Every(val intervalMin: Int, val faster: FasterStretch? = null) : HabitSchedule

    /** A horas fijas del día. */
    data class AtTimes(val times: List<LocalTime>) : HabitSchedule

    /** Una vez al día, [minutes] minutos antes de acostarse. */
    data class BeforeBed(val minutes: Int) : HabitSchedule
}

/** Tramo "más seguido desde": a partir de [from] (hora del reloj), un aviso cada [intervalMin] minutos. */
data class FasterStretch(val from: LocalTime, val intervalMin: Int)

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

/** Grupo desde el argumento de ruta; cualquier valor ausente o desconocido es ESSENTIAL. */
fun parseHabitGroup(raw: String?): HabitGroup = HabitGroup.entries.firstOrNull { it.name == raw } ?: HabitGroup.ESSENTIAL
