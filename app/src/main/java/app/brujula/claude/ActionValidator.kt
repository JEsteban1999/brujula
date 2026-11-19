package app.brujula.claude

import app.brujula.activities.Activity
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.activities.form.MAX_DURATION
import app.brujula.activities.form.REMIND_BEFORE_MIN
import app.brujula.habits.Habit
import app.brujula.habits.HabitGroup
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitSchedule
import app.brujula.habits.HabitWindow
import app.brujula.habits.MAX_BEFORE_BED_MIN
import app.brujula.habits.MAX_DAILY_TARGET
import app.brujula.habits.MAX_INTERVAL_MIN
import app.brujula.habits.MIN_BEFORE_BED_MIN
import app.brujula.habits.MIN_INTERVAL_MIN
import app.brujula.habits.autoDailyTarget
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * Valida localmente cada acción de Claude antes de aplicarla. Nada fuera de rango se agenda: se devuelve [Validated.Invalid]
 * con un motivo legible para mostrarlo al usuario.
 */
object ActionValidator {
    const val MIN_DICTATED_DURATION = 5
    const val DEFAULT_DURATION = 60
    const val MAX_DAYS_AHEAD = 365L
    const val DEFAULT_BEFORE_BED = 30
    /** Margen para "a las 3" dicho a las 3:05: no es pasado todavía. */
    private const val PAST_TOLERANCE_MIN = 15L
    private const val MAX_TITLE = 80
    private const val UNSUPPORTED_DEFAULT = "Por ahora solo puedo agendar actividades y hábitos nuevos."

    private val TIME = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""")
    private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")

    fun validate(a: DictationAction, ctx: DictationContext): Validated = when (a.type.trim()) {
        "create_activity" -> activity(a, ctx)
        "create_habit" -> habit(a, ctx)
        "need_more_info" -> a.question?.trim()?.takeIf { it.isNotEmpty() }?.let { Validated.Question(it) }
            ?: Validated.Invalid("Claude pidió más datos pero no dijo cuáles.")
        "unsupported" -> Validated.Unsupported(a.question?.trim()?.takeIf { it.isNotEmpty() } ?: UNSUPPORTED_DEFAULT)
        else -> Validated.Invalid("Acción desconocida: «${a.type}».")
    }

    private fun activity(a: DictationAction, ctx: DictationContext): Validated {
        val title = a.title?.trim()?.take(MAX_TITLE)
        if (title.isNullOrEmpty()) return Validated.Invalid("Una actividad llegó sin nombre.")
        val label = "«$title»"
        val type = ActivityType.entries.firstOrNull { it.name == a.activityType?.trim() }
            ?: return Validated.Invalid("$label: tipo de actividad desconocido (${a.activityType}).")
        val date = parseDate(a.date) ?: return Validated.Invalid("$label: la fecha no es válida (${a.date}).")
        val today = ctx.logicalToday
        if (date < today) return Validated.Invalid("$label: la fecha ${a.date} ya pasó.")
        if (date > today.plusDays(MAX_DAYS_AHEAD)) return Validated.Invalid("$label: la fecha ${a.date} está a más de un año.")
        val start = parseTime(a.start) ?: return Validated.Invalid("$label: la hora no es válida (${a.start}).")
        val duration = a.durationMin ?: DEFAULT_DURATION
        if (duration !in MIN_DICTATED_DURATION..MAX_DURATION) {
            return Validated.Invalid("$label: la duración de $duration min no es válida (de $MIN_DICTATED_DURATION a $MAX_DURATION).")
        }
        val repeat = if (a.repeat.isNullOrBlank()) {
            Repeat.NONE
        } else {
            Repeat.entries.firstOrNull { it.name == a.repeat.trim() }
                ?: return Validated.Invalid("$label: repetición desconocida (${a.repeat}).")
        }
        if (repeat == Repeat.NONE && date.atTime(start) < ctx.now.minusMinutes(PAST_TOLERANCE_MIN)) {
            return Validated.Invalid("$label: esa hora ya pasó.")
        }
        return Validated.Activity(
            Activity(
                title = title, type = type, date = date, start = start, durationMin = duration, repeat = repeat,
                remindBeforeMin = REMIND_BEFORE_MIN, copyToGoogle = a.copyToGoogle ?: (type == ActivityType.APPOINTMENT),
            ),
        )
    }

    private fun habit(a: DictationAction, ctx: DictationContext): Validated {
        val name = a.title?.trim()?.take(MAX_TITLE)
        if (name.isNullOrEmpty()) return Validated.Invalid("Un hábito llegó sin nombre.")
        val label = "«$name»"
        val workOnly = a.workOnly == true
        val (schedule, target, window) = when (a.habitMode?.trim()) {
            "EVERY" -> {
                val interval = a.intervalMin
                if (interval == null || interval !in MIN_INTERVAL_MIN..MAX_INTERVAL_MIN) {
                    return Validated.Invalid("$label: el intervalo debe ir de $MIN_INTERVAL_MIN a $MAX_INTERVAL_MIN minutos.")
                }
                val window = if (workOnly) HabitWindow.WORK else HabitWindow.AWAKE
                Triple(HabitSchedule.Every(interval), autoDailyTarget(interval, window, ctx.routine), window)
            }
            "AT_TIMES" -> {
                val raw = a.times.orEmpty()
                if (raw.isEmpty()) return Validated.Invalid("$label: faltan las horas del hábito.")
                val times = raw.map { parseTime(it) ?: return Validated.Invalid("$label: la hora $it no es válida.") }
                    .distinct().sorted()
                if (times.size > MAX_DAILY_TARGET) return Validated.Invalid("$label: son más de $MAX_DAILY_TARGET horas al día.")
                Triple(HabitSchedule.AtTimes(times), times.size, if (workOnly) HabitWindow.WORK else HabitWindow.AWAKE)
            }
            "BEFORE_BED" -> {
                val minutes = a.beforeBedMin ?: DEFAULT_BEFORE_BED
                if (minutes !in MIN_BEFORE_BED_MIN..MAX_BEFORE_BED_MIN) {
                    return Validated.Invalid("$label: debe ir de $MIN_BEFORE_BED_MIN a $MAX_BEFORE_BED_MIN minutos antes de dormir.")
                }
                // "Antes de dormir" no usa la ventana de trabajo (igual que el formulario).
                Triple(HabitSchedule.BeforeBed(minutes), 1, HabitWindow.AWAKE)
            }
            else -> return Validated.Invalid("$label: modo de hábito desconocido (${a.habitMode}).")
        }
        // Solo en horario de trabajo = pausa de bienestar (grupo WELLNESS); el resto, esencial.
        val group = if (window == HabitWindow.WORK) HabitGroup.WELLNESS else HabitGroup.ESSENTIAL
        return Validated.Habit(
            Habit(name = name, icon = iconFor(name), schedule = schedule, dailyTarget = target, group = group, window = window),
        )
    }

    private fun parseTime(s: String?): LocalTime? {
        val m = s?.trim()?.let { TIME.matchEntire(it) } ?: return null
        return LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    private fun parseDate(s: String?): LocalDate? {
        val t = s?.trim()?.takeIf { DATE.matches(it) } ?: return null
        return runCatching { LocalDate.parse(t) }.getOrNull()
    }

    private val DIACRITICS = Regex("""\p{Mn}+""")

    private val ICON_WORDS = listOf(
        HabitIcon.WATER to listOf("agua", "hidrat"),
        HabitIcon.LIP_BALM to listOf("balsamo", "labio"),
        HabitIcon.STRETCH to listOf("estira", "estiramiento"),
        HabitIcon.EYE to listOf("ojo", "ojos", "vista", "20-20-20"),
        HabitIcon.WALK to listOf("camina", "pasos", "pararme", "levantarme"),
        HabitIcon.POSTURE to listOf("postura", "espalda"),
        HabitIcon.FOOD to listOf("comer", "comida", "fruta", "merienda", "almuerzo", "desayuno"),
        HabitIcon.SCREEN_OFF to listOf("pantalla", "celular", "telefono"),
        HabitIcon.PILL to listOf("pastilla", "medicina", "medicamento", "vitamina", "remedio"),
        HabitIcon.SUN to listOf("sol", "bloqueador", "protector"),
        HabitIcon.BOOK to listOf("leer", "lectura", "libro"),
    )

    /** Ícono por palabras clave del nombre (sin tildes; las de 3 letras deben coincidir enteras); si no hay ninguna, genérico. */
    private fun iconFor(name: String): HabitIcon {
        val n = Normalizer.normalize(name, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase(Locale.ROOT)
        val words = n.split(Regex("[^a-z0-9-]+"))
        return ICON_WORDS.firstOrNull { (_, keys) -> keys.any { k -> words.any { w -> if (k.length <= 3) w == k else w.startsWith(k) } } }?.first ?: HabitIcon.GENERIC
    }
}
