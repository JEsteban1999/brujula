package app.brujula.habits

import java.time.LocalDate

/** Días seguidos con ejercicio hecho terminando hoy, o ayer si hoy aún no se hizo. */
fun exerciseStreak(today: LocalDate, doneDays: Set<LocalDate>): Int {
    var day = if (today in doneDays) today else today.minusDays(1)
    var count = 0
    while (day in doneDays) {
        count++
        day = day.minusDays(1)
    }
    return count
}