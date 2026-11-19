package app.brujula.planning

import app.brujula.activities.ActivityType
import app.brujula.core.time.formatMinutes
import app.brujula.core.time.formatTime
import app.brujula.routine.logicalDay

/** Texto de respaldo del resumen cuando no se puede armar con el día real. */
const val MORNING_FALLBACK_BODY = "Toca para ver tu día."

private const val MORNING_ITEMS = 2

/**
 * Cuerpo del resumen de la mañana (D14), armado al entregarlo con el día real: `{trabajo} de trabajo` (se omite en un día
 * de descanso con 0 min) y los dos primeros bloques que no son trabajo, `{título} a las {hora}`, unidos con ` · `. Lo de
 * ayer que cruza la medianoche no cuenta: solo el día lógico de [s].
 */
fun morningBody(s: DaySnapshot): String {
    val parts = mutableListOf<String>()
    val work = s.summary.workMinutes
    if (!(s.summary.workGoal == null && work == 0)) parts += "${formatMinutes(work)} de trabajo"
    val own = s.own.filter { it.activity.type != ActivityType.WORK }.map { it.span.start to it.activity.title }
    val external = s.external.filter { !it.countsAsWork }.map { it.event.span.start to it.event.displayTitle }
    (own + external)
        .filter { logicalDay(it.first) == s.day }
        .sortedBy { it.first }
        .take(MORNING_ITEMS)
        .forEach { (start, title) -> parts += "$title a las ${formatTime(start.toLocalTime())}" }
    return if (parts.isEmpty()) "Hoy no tienes nada agendado." else parts.joinToString(" \u00b7 ")
}
