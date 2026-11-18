package app.brujula.core.time

import java.time.Duration
import java.time.LocalDateTime

/** Intervalo semiabierto [start, end). */
data class TimeSpan(val start: LocalDateTime, val end: LocalDateTime) {
    fun overlaps(other: TimeSpan): Boolean = start < other.end && other.start < end
    operator fun contains(t: LocalDateTime): Boolean = t >= start && t < end
    val minutes: Long get() = Duration.between(start, end).toMinutes()
}
