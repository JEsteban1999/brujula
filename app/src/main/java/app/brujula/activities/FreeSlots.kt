package app.brujula.activities

import app.brujula.core.time.TimeSpan
import java.time.LocalDateTime

/** Huecos de al menos [minMinutes] dentro de [window] que no tocan ningún intervalo de [busy]. */
fun freeSlots(window: TimeSpan, busy: List<TimeSpan>, minMinutes: Long = 30): List<TimeSpan> {
    val slots = mutableListOf<TimeSpan>()
    var cursor = window.start
    for (b in busy.sortedBy { it.start }) {
        if (b.start > cursor) addSlot(slots, cursor, minOf(b.start, window.end), minMinutes)
        if (b.end > cursor) cursor = b.end
        if (cursor >= window.end) return slots
    }
    if (cursor < window.end) addSlot(slots, cursor, window.end, minMinutes)
    return slots
}

private fun addSlot(slots: MutableList<TimeSpan>, start: LocalDateTime, end: LocalDateTime, minMinutes: Long) {
    val span = TimeSpan(start, end)
    if (span.minutes >= minMinutes) slots.add(span)
}
