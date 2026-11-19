package app.brujula.habits

import app.brujula.routine.dayTypeOf
import java.time.LocalDate

/**
 * Días seguidos con ejercicio hecho terminando hoy, o ayer si hoy aún no se hizo.
 * Un día de descanso sin hacer no rompe la racha (tampoco suma); un día laboral sin hacer sí.
 */
fun exerciseStreak(
    today: LocalDate,
    doneDays: Set<LocalDate>,
    isRest: (LocalDate) -> Boolean = { dayTypeOf(it).isRest },
): Int {
    var day = if (today in doneDays) today else today.minusDays(1)
    var count = 0
    repeat(366) {
        when {
            day in doneDays -> count++
            isRest(day) -> Unit
            else -> return count
        }
        day = day.minusDays(1)
    }
    return count
}
