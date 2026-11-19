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
    /** Instante en que se dictó (cola sin conexión): "hoy/mañana" salen de su día lógico; [now] sigue siendo el momento actual. */
    dictatedAt: LocalDateTime? = null,
): DictationContext {
    val day = logicalDay(dictatedAt ?: now)
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
        dictatedAt = dictatedAt,
    )
}
