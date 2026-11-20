package app.brujula.data.db

import androidx.room.TypeConverter
import app.brujula.habits.FasterStretch
import app.brujula.habits.HabitSchedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

class Converters {
    @TypeConverter fun dateToString(v: LocalDate): String = v.toString()
    @TypeConverter fun stringToDate(v: String): LocalDate = LocalDate.parse(v)
    @TypeConverter fun timeToString(v: LocalTime): String = v.toString()
    @TypeConverter fun stringToTime(v: String): LocalTime = LocalTime.parse(v)
    @TypeConverter fun dateTimeToString(v: LocalDateTime): String = v.toString()
    @TypeConverter fun stringToDateTime(v: String): LocalDateTime = LocalDateTime.parse(v)
}

/**
 * "every:90", "every:90|15:00/60" (con tramo más seguido desde las 15:00), "at:09:00,13:00" o "bed:45".
 *
 * Compatibilidad del tramo (sin migración): sin tramo el texto es exactamente el de siempre ("every:90"), y lo que venga
 * después de `|` que esta versión no entienda se ignora (queda el intervalo base). Una versión anterior a la 3B no sabe leer
 * el `|` y fallaría al abrir los hábitos; no hay camino de vuelta a una versión anterior (Android no instala un APK más viejo
 * sin desinstalar, que borra los datos), así que no se protege ese caso.
 */
fun HabitSchedule.encode(): String = when (this) {
    is HabitSchedule.Every -> "every:$intervalMin" + (faster?.let { "|${it.from.encodeHm()}/${it.intervalMin}" } ?: "")
    is HabitSchedule.AtTimes -> "at:" + times.joinToString(",") { it.toString() }
    is HabitSchedule.BeforeBed -> "bed:$minutes"
}

fun decodeSchedule(text: String): HabitSchedule = when {
    text.startsWith("every:") -> {
        val parts = text.removePrefix("every:").split("|", limit = 2)
        HabitSchedule.Every(parts[0].toInt(), parts.getOrNull(1)?.let(::decodeFaster))
    }
    text.startsWith("at:") -> HabitSchedule.AtTimes(
        text.removePrefix("at:").split(",").filter { it.isNotBlank() }.map { LocalTime.parse(it) },
    )
    text.startsWith("bed:") -> HabitSchedule.BeforeBed(text.removePrefix("bed:").toInt())
    else -> error("Horario de habito desconocido: $text")
}

private fun LocalTime.encodeHm(): String = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

/** "15:00/60"; cualquier otra cosa (o un intervalo no positivo) es "sin tramo". */
private fun decodeFaster(text: String): FasterStretch? {
    val (from, interval) = text.split("/").takeIf { it.size == 2 } ?: return null
    if (from.length != 5) return null
    val time = runCatching { LocalTime.parse(from) }.getOrNull() ?: return null
    val min = interval.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return FasterStretch(time, min)
}

fun Set<DayOfWeek>.encodeDays(): String = sorted().joinToString(",") { it.value.toString() }

fun decodeDays(text: String): Set<DayOfWeek> =
    text.split(",").filter { it.isNotBlank() }.map { DayOfWeek.of(it.toInt()) }.toSet()
