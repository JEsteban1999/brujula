package app.brujula.routine

import app.brujula.core.theme.ThemeMode
import app.brujula.core.time.TimeSpan
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class Routine(
    val wake: LocalTime = LocalTime.of(8, 15),
    val bed: LocalTime = LocalTime.of(0, 30),
    val workBlocks: List<Pair<LocalTime, LocalTime>> = listOf(
        LocalTime.of(8, 0) to LocalTime.of(12, 0),
        LocalTime.of(14, 0) to LocalTime.of(18, 0),
    ),
    val flexibleWork: Boolean = true,
    val workGoalMin: IntRange = 360..420,
    val themeMode: ThemeMode = ThemeMode.AUTO,
)

/** Vigilia del día [day]: de levantarse a acostarse (acostarse puede ser del día siguiente). */
fun Routine.awakeWindow(day: LocalDate): TimeSpan {
    val start = day.atTime(wake)
    val endDay = if (bed <= wake) day.plusDays(1) else day
    return TimeSpan(start, endDay.atTime(bed))
}

fun Routine.isAwake(at: LocalDateTime): Boolean {
    val day = at.toLocalDate()
    return at in awakeWindow(day) || at in awakeWindow(day.minusDays(1))
}

private val LOGICAL_DAY_START = LocalTime.of(4, 0)

/** El día lógico cambia a las 04:00. */
fun logicalDay(at: LocalDateTime): LocalDate =
    if (at.toLocalTime() < LOGICAL_DAY_START) at.toLocalDate().minusDays(1) else at.toLocalDate()
