package app.brujula.core.theme

import java.time.LocalTime

enum class ThemeMode { LIGHT, DARK, AUTO }

/**
 * Decide si se usa el tema oscuro. En AUTO es oscuro desde [autoDarkFrom]
 * hasta la hora de levantarse [wake] (cruzando la medianoche).
 */
fun resolveDark(
    mode: ThemeMode,
    now: LocalTime,
    wake: LocalTime,
    autoDarkFrom: LocalTime = LocalTime.of(21, 0),
): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.AUTO -> !now.isBefore(autoDarkFrom) || now.isBefore(wake)
}
