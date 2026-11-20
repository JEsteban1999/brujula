package app.brujula.widget

import app.brujula.core.theme.ThemeMode
import app.brujula.core.theme.isDarkAt
import app.brujula.core.time.TimeSpan
import app.brujula.planning.DaySnapshot
import app.brujula.routine.Routine
import app.brujula.routine.logicalDay
import app.brujula.routine.wakeFor
import app.brujula.today.HabitChip
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Lo que pinta el widget; todo ya calculado (el render no decide nada). */
data class WidgetModel(
    val nowLabel: String,
    val nowTitle: String,
    val nextText: String,
    val habits: List<HabitChip>,
    val dark: Boolean,
    /** Próximo instante en que el widget deja de estar al día (fin o inicio de bloque, tema, 04:00 o tope). */
    val nextRefresh: LocalDateTime,
)

/** Tope entre refrescos por la alarma: nunca se queda más de una hora sin repintar. */
const val WIDGET_MAX_REFRESH_MIN = 60L

private val HOUR = DateTimeFormatter.ofPattern("HH:mm")
private val LOGICAL_DAY_START = LocalTime.of(4, 0)
private val AUTO_DARK_FROM = LocalTime.of(21, 0)

private class Block(val title: String, val span: TimeSpan)

fun buildWidgetModel(now: LocalDateTime, snapshot: DaySnapshot, chips: List<HabitChip>, routine: Routine): WidgetModel {
    // Los propios van primero: en un empate de inicio gana el propio (maxByOrNull/minByOrNull conservan el primero).
    val blocks = snapshot.own.map { Block(it.activity.title, it.span) } +
        snapshot.external.map { Block(it.event.displayTitle, it.event.span) }
    val current = blocks.filter { now in it.span }.maxByOrNull { it.span.start }
    val next = blocks.filter { it.span.start > now }.minByOrNull { it.span.start }

    val nowLabel = when {
        current != null -> "Ahora · hasta ${current.span.end.format(HOUR)}"
        next != null -> "Ahora · hasta ${next.span.start.format(HOUR)}"
        else -> "Ahora"
    }
    val nextText = next?.let { "${it.title} ${it.span.start.format(HOUR)}" } ?: "Nada más por hoy"

    val candidates = buildList {
        current?.let { add(it.span.end) }
        next?.let { add(it.span.start) }
        if (routine.themeMode == ThemeMode.AUTO) addAll(themeChanges(now, routine))
        add(nextLogicalDayStart(now))
        add(now.plusMinutes(WIDGET_MAX_REFRESH_MIN))
    }
    return WidgetModel(
        nowLabel = nowLabel,
        nowTitle = current?.title ?: "Libre",
        nextText = nextText,
        habits = chips,
        dark = routine.isDarkAt(now),
        nextRefresh = candidates.filter { it > now }.min(),
    )
}

/** Los próximos cambios de tema en Automático: las 21:00 y la hora de levantarse (hoy y mañana). */
private fun themeChanges(now: LocalDateTime, routine: Routine): List<LocalDateTime> {
    val day = logicalDay(now)
    val date = now.toLocalDate()
    return listOf(
        date.atTime(AUTO_DARK_FROM), date.plusDays(1).atTime(AUTO_DARK_FROM),
        date.atTime(routine.wakeFor(day)), date.plusDays(1).atTime(routine.wakeFor(day.plusDays(1))),
    )
}

/** Las 04:00 siguientes: el día lógico cambia entonces. */
private fun nextLogicalDayStart(now: LocalDateTime): LocalDateTime =
    logicalDay(now).plusDays(1).atTime(LOGICAL_DAY_START)
