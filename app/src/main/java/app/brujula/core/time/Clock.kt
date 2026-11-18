package app.brujula.core.time

import java.time.LocalDate
import java.time.LocalDateTime

interface Clock {
    fun now(): LocalDateTime
    fun today(): LocalDate
}

object SystemClock : Clock {
    override fun now(): LocalDateTime = LocalDateTime.now()
    override fun today(): LocalDate = LocalDate.now()
}

/** Reloj controlable para pruebas de todos los módulos. */
class FixedClock(var current: LocalDateTime) : Clock {
    override fun now(): LocalDateTime = current
    override fun today(): LocalDate = current.toLocalDate()
}
