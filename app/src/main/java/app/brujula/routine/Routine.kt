package app.brujula.routine

import app.brujula.core.theme.ThemeMode
import app.brujula.core.time.TimeSpan
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** El aviso para dormir sale 30 min antes de la hora de acostarse sugerida. */
const val BEDTIME_REMIND_BEFORE_MIN = 30

data class Routine(
    val wake: LocalTime = LocalTime.of(8, 15),
    val bed: LocalTime = LocalTime.of(0, 30),
    val restWake: LocalTime = LocalTime.of(10, 0),
    val restBed: LocalTime = LocalTime.of(1, 0),
    val workBlocks: List<Pair<LocalTime, LocalTime>> = listOf(
        LocalTime.of(8, 0) to LocalTime.of(12, 0),
        LocalTime.of(14, 0) to LocalTime.of(18, 0),
    ),
    val flexibleWork: Boolean = true,
    val workGoalMin: IntRange = 360..420,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    /** Hora del aviso "¿Armamos mañana?" (se corre dentro de la vigilia si la hora de acostarse es más temprana). */
    val nightNotice: LocalTime = LocalTime.of(22, 45),
)

/** Vigilia del día [day]: de levantarse a acostarse (acostarse puede ser del día siguiente). */
fun Routine.awakeWindow(day: LocalDate): TimeSpan {
    val w = wakeFor(day)
    val b = bedFor(day)
    val endDay = if (b <= w) day.plusDays(1) else day
    return TimeSpan(day.atTime(w), endDay.atTime(b))
}

/** Hora de levantarse de [day]: la de descanso en fines de semana y festivos. */
fun Routine.wakeFor(day: LocalDate): LocalTime = if (dayTypeOf(day).isRest) restWake else wake

/** Hora de acostarse de [day]: la de descanso en fines de semana y festivos. */
fun Routine.bedFor(day: LocalDate): LocalTime = if (dayTypeOf(day).isRest) restBed else bed

fun Routine.isAwake(at: LocalDateTime): Boolean {
    val day = at.toLocalDate()
    return at in awakeWindow(day) || at in awakeWindow(day.minusDays(1))
}

internal val LOGICAL_DAY_START = LocalTime.of(4, 0)

/** El día lógico cambia a las 04:00. */
fun logicalDay(at: LocalDateTime): LocalDate =
    if (at.toLocalTime() < LOGICAL_DAY_START) at.toLocalDate().minusDays(1) else at.toLocalDate()
