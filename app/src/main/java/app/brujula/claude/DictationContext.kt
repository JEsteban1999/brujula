package app.brujula.claude

import app.brujula.routine.DayType
import app.brujula.routine.Routine
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Lo único que se le envía a Claude además del texto dictado. */
data class DictationContext(
    val now: LocalDateTime,
    val zone: ZoneId,
    val logicalToday: LocalDate,
    val todayType: DayType,
    val tomorrowType: DayType,
    val routine: Routine,
    val busyToday: List<BusyItem>,
    val busyTomorrow: List<BusyItem>,
    val habitNames: List<String>,
)

/** Algo que ya ocupa tiempo: actividad propia o evento externo (solo lectura). */
data class BusyItem(val title: String, val start: LocalDateTime, val end: LocalDateTime, val external: Boolean)
