package app.brujula.routine

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** Domingo de Pascua (algoritmo gregoriano anónimo). */
fun easterSunday(year: Int): LocalDate {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31
    val day = (h + l - 7 * m + 114) % 31 + 1
    return LocalDate.of(year, month, day)
}

/** Si no cae en lunes, pasa al lunes siguiente (ley Emiliani). */
private fun nextMonday(d: LocalDate): LocalDate =
    if (d.dayOfWeek == DayOfWeek.MONDAY) d else d.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY))

/** Los 18 festivos de Colombia del año; si dos coinciden en fecha, los nombres se unen con " y ". */
fun colombianHolidays(year: Int): Map<LocalDate, String> {
    val easter = easterSunday(year)
    val entries = listOf(
        LocalDate.of(year, 1, 1) to "Año Nuevo",
        nextMonday(LocalDate.of(year, 1, 6)) to "Reyes Magos",
        nextMonday(LocalDate.of(year, 3, 19)) to "San José",
        easter.minusDays(3) to "Jueves Santo",
        easter.minusDays(2) to "Viernes Santo",
        LocalDate.of(year, 5, 1) to "Día del Trabajo",
        easter.plusDays(43) to "Ascensión del Señor",
        easter.plusDays(64) to "Corpus Christi",
        easter.plusDays(71) to "Sagrado Corazón",
        nextMonday(LocalDate.of(year, 6, 29)) to "San Pedro y San Pablo",
        LocalDate.of(year, 7, 20) to "Día de la Independencia",
        LocalDate.of(year, 8, 7) to "Batalla de Boyacá",
        nextMonday(LocalDate.of(year, 8, 15)) to "Asunción de la Virgen",
        nextMonday(LocalDate.of(year, 10, 12)) to "Día de la Raza",
        nextMonday(LocalDate.of(year, 11, 1)) to "Todos los Santos",
        nextMonday(LocalDate.of(year, 11, 11)) to "Independencia de Cartagena",
        LocalDate.of(year, 12, 8) to "Inmaculada Concepción",
        LocalDate.of(year, 12, 25) to "Navidad",
    )
    val result = LinkedHashMap<LocalDate, String>()
    for ((date, name) in entries) {
        result[date] = result[date]?.let { "$it y $name" } ?: name
    }
    return result
}

enum class DayKind { WORKDAY, WEEKEND, HOLIDAY }

data class DayType(val kind: DayKind, val holidayName: String? = null) {
    val isRest: Boolean get() = kind != DayKind.WORKDAY
}

private val holidayCache = ConcurrentHashMap<Int, Map<LocalDate, String>>()

/** Tipo de día: el festivo gana sobre el fin de semana. */
fun dayTypeOf(day: LocalDate): DayType {
    val name = holidayCache.getOrPut(day.year) { colombianHolidays(day.year) }[day]
    return when {
        name != null -> DayType(DayKind.HOLIDAY, name)
        day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY -> DayType(DayKind.WEEKEND)
        else -> DayType(DayKind.WORKDAY)
    }
}
