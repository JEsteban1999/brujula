package app.brujula.data.db

import androidx.room.TypeConverter
import app.brujula.habits.HabitSchedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class Converters {
    @TypeConverter fun dateToString(v: LocalDate): String = v.toString()
    @TypeConverter fun stringToDate(v: String): LocalDate = LocalDate.parse(v)
    @TypeConverter fun timeToString(v: LocalTime): String = v.toString()
    @TypeConverter fun stringToTime(v: String): LocalTime = LocalTime.parse(v)
    @TypeConverter fun dateTimeToString(v: LocalDateTime): String = v.toString()
    @TypeConverter fun stringToDateTime(v: String): LocalDateTime = LocalDateTime.parse(v)
}

/** "every:90" o "at:09:00,13:00". */
fun HabitSchedule.encode(): String = when (this) {
    is HabitSchedule.Every -> "every:$intervalMin"
    is HabitSchedule.AtTimes -> "at:" + times.joinToString(",") { it.toString() }
}

fun decodeSchedule(text: String): HabitSchedule = when {
    text.startsWith("every:") -> HabitSchedule.Every(text.removePrefix("every:").toInt())
    text.startsWith("at:") -> HabitSchedule.AtTimes(
        text.removePrefix("at:").split(",").filter { it.isNotBlank() }.map { LocalTime.parse(it) },
    )
    else -> error("Horario de habito desconocido: $text")
}

fun Set<DayOfWeek>.encodeDays(): String = sorted().joinToString(",") { it.value.toString() }

fun decodeDays(text: String): Set<DayOfWeek> =
    text.split(",").filter { it.isNotBlank() }.map { DayOfWeek.of(it.toInt()) }.toSet()
