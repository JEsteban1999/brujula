package app.brujula.claude

import app.brujula.routine.Routine
import app.brujula.routine.dayTypeOf
import app.brujula.routine.logicalDay
import java.time.LocalDateTime
import java.time.ZoneId

/** Contexto de prueba con el día lógico calculado igual que en la app. */
fun dictationCtx(
    now: LocalDateTime,
    busyToday: List<BusyItem> = emptyList(),
    busyTomorrow: List<BusyItem> = emptyList(),
    habitNames: List<String> = emptyList(),
    routine: Routine = Routine(),
): DictationContext {
    val day = logicalDay(now)
    return DictationContext(
        now = now,
        zone = ZoneId.of("America/Bogota"),
        logicalToday = day,
        todayType = dayTypeOf(day),
        tomorrowType = dayTypeOf(day.plusDays(1)),
        routine = routine,
        busyToday = busyToday,
        busyTomorrow = busyTomorrow,
        habitNames = habitNames,
    )
}
