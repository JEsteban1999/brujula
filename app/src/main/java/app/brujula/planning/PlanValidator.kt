package app.brujula.planning

import app.brujula.activities.Activity
import app.brujula.activities.ActivityOrigin
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.activities.form.MAX_DURATION
import app.brujula.activities.form.REMIND_BEFORE_MIN
import app.brujula.claude.PlanChange
import app.brujula.claude.PlanContext
import app.brujula.claude.PlanItemRef
import app.brujula.claude.PlanResult
import app.brujula.core.time.TimeSpan
import app.brujula.core.time.formatMinutes
import app.brujula.core.time.formatTime
import app.brujula.routine.logicalDay
import app.brujula.today.LOGICAL_DAY_START
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Cambios de Claude que pasaron la validación local, y una frase por cada uno descartado (`No pude aplicar:`). */
data class ValidatedPlan(val ops: List<PlanOp>, val rejected: List<String>)

/**
 * Valida en la app cada cambio que propone Claude (D2, regla C) y calcula la vista previa y los totales.
 *
 * Los cambios se revisan en el orden de Claude sobre un estado en curso (lo propio + lo fijo + el almuerzo) que se actualiza
 * tras cada cambio aceptado: un `move` o un `skip` liberan su espacio para lo que sigue. Un cambio inválido se descarta con
 * un motivo legible y el resto sigue. Las referencias se resuelven buscando exactamente en `ctx.items`, nunca leyendo el id
 * (una ocurrencia de ayer de la misma serie lleva sufijo, `A12-2`).
 */
object PlanValidator {
    const val MAX_CHANGES = 20
    /** D10: de hoy (día lógico) a hoy + 6. */
    const val MAX_DAYS_AHEAD = 6L
    const val MIN_DURATION = 5
    const val MAX_TITLE = 80
    const val TOO_MANY = "Claude propuso demasiados cambios; apliqué los primeros $MAX_CHANGES."
    const val APPOINTMENT = "Las citas se agendan dictándolas."
    const val OUT_OF_RANGE = "Por ahora puedo armar hasta 6 días adelante."
    const val PAST_DAY = "Ese día ya pasó. Puedo armar desde hoy hasta 6 días adelante."
    const val LUNCH_TITLE = "Almuerzo y pausa"
    const val BED_TITLE = "A dormir"
    private const val EXTERNAL_SOURCE_FALLBACK = "Calendario"
    private const val CLIP = 40

    private val TIME = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""")
    private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val SPACES = Regex("""\s+""")
    private val CREATABLE = setOf(ActivityType.WORK, ActivityType.EXERCISE, ActivityType.PERSONAL)

    /** Motivo por el que no se puede armar [day] cuando hoy (día lógico) es [logicalToday] (D10), o null si se puede. */
    fun dayProblem(day: LocalDate, logicalToday: LocalDate): String? = when {
        day < logicalToday -> PAST_DAY
        day > logicalToday.plusDays(MAX_DAYS_AHEAD) -> OUT_OF_RANGE
        else -> null
    }

    fun dayProblem(ctx: PlanContext): String? = dayProblem(ctx.day, ctx.logicalToday)

    fun validate(result: PlanResult, ctx: PlanContext): ValidatedPlan {
        dayProblem(ctx)?.let { return ValidatedPlan(emptyList(), listOf(it)) }
        val run = Run(ctx)
        result.changes.take(MAX_CHANGES).forEach { change ->
            val problem = when (change.op.trim().lowercase()) {
                "create" -> run.create(change)
                "move" -> run.move(change)
                "skip" -> run.skip(change)
                else -> "Claude propuso un cambio que no entiendo («${clip(change.op)}»)."
            }
            if (problem != null) run.rejected += problem
        }
        if (result.changes.size > MAX_CHANGES) run.rejected += TOO_MANY
        return ValidatedPlan(run.ops.toList(), run.rejected.toList())
    }

    /** Lo que habría en el día con [ops] aplicados, por hora, con la fila final `A dormir`. */
    fun preview(ctx: PlanContext, ops: List<PlanOp>): List<PreviewBlock> {
        val moves = ops.filterIsInstance<PlanOp.Move>().associateBy { it.ref }
        val skips = ops.filterIsInstance<PlanOp.Skip>().map { it.ref }.toSet()
        val blocks = mutableListOf<PreviewBlock>()
        ctx.items.forEach { item ->
            val title = item.title.ifBlank { "(Sin título)" }
            val moved = moves[item.ref]
            blocks += when {
                item.external -> PreviewBlock(title, item.span, BlockTag.FIJO, "${item.sourceLabel ?: EXTERNAL_SOURCE_FALLBACK} · ${range(item.span)}")
                item.ref in skips -> PreviewBlock(title, item.span, BlockTag.QUITADO, range(item.span))
                moved != null -> PreviewBlock(title, moved.to, BlockTag.MOVIDO, "${range(moved.to)} · antes ${formatTime(moved.from.start.toLocalTime())}")
                item.origin == ActivityOrigin.DICTATION -> PreviewBlock(title, item.span, BlockTag.LO_DICTASTE, range(item.span))
                else -> PreviewBlock(title, item.span, BlockTag.NONE, range(item.span))
            }
        }
        ops.filterIsInstance<PlanOp.Create>().forEach { c ->
            val span = spanOf(c.activity)
            blocks += PreviewBlock(c.activity.title, span, BlockTag.NUEVO, range(span))
        }
        ctx.lunch?.let { blocks += PreviewBlock(LUNCH_TITLE, it, BlockTag.FIJO, range(it)) }
        val bed = bedtimeFor(ctx)
        val sleep = PreviewBlock(
            BED_TITLE, TimeSpan(bed.bedtime, bed.bedtime.plusMinutes(bed.sleepMinutes.toLong())), BlockTag.NONE,
            "Para dormir ${formatMinutes(bed.sleepMinutes)}",
        )
        return blocks.sortedBy { it.span.start } + sleep
    }

    /**
     * Trabajo como en Hoy (WORK propio del día lógico, sin lo de ayer, más lo externo marcado como trabajo recortado al día
     * lógico), ejercicio propio y sueño, con [ops] aplicados.
     */
    fun totals(ctx: PlanContext, ops: List<PlanOp>): PlanTotals {
        val moves = ops.filterIsInstance<PlanOp.Move>().associateBy { it.ref }
        val skips = ops.filterIsInstance<PlanOp.Skip>().map { it.ref }.toSet()
        val own = ctx.items
            .filter { !it.external && it.ref !in skips && logicalDay(it.span.start) == ctx.day }
            .map { it.type to (moves[it.ref]?.to ?: it.span) } +
            ops.filterIsInstance<PlanOp.Create>().map { it.activity.type to spanOf(it.activity) }
        val logicalSpan = TimeSpan(ctx.day.atTime(LOGICAL_DAY_START), ctx.day.plusDays(1).atTime(LOGICAL_DAY_START))
        val externalWork = ctx.items.filter { it.external && it.countsAsWork }.sumOf { minutesWithin(it.span, logicalSpan) }
        val bed = bedtimeFor(ctx)
        return PlanTotals(
            workMinutes = own.filter { it.first == ActivityType.WORK }.sumOf { it.second.minutes.toInt() } + externalWork,
            exerciseMinutes = own.filter { it.first == ActivityType.EXERCISE }.sumOf { it.second.minutes.toInt() },
            sleepMinutes = bed.sleepMinutes,
            bedtime = bed.bedtime,
        )
    }

    /** D12 para el día armado (solo lo fijo de mañana mueve la hora de levantarse); si es hoy, nunca antes de ahora. */
    private fun bedtimeFor(ctx: PlanContext): BedtimeSuggestion =
        suggestBedtime(ctx.day, ctx.routine, ctx.nextDayFirstFixedStart, notBefore = ctx.now.takeIf { ctx.day == ctx.logicalToday })

    private fun minutesWithin(span: TimeSpan, within: TimeSpan): Int {
        val start = maxOf(span.start, within.start)
        val end = minOf(span.end, within.end)
        return if (end > start) TimeSpan(start, end).minutes.toInt() else 0
    }

    private fun spanOf(a: Activity): TimeSpan {
        val start = a.date.atTime(a.start)
        return TimeSpan(start, start.plusMinutes(a.durationMin.toLong()))
    }

    private fun range(span: TimeSpan) = "${formatTime(span.start.toLocalTime())} – ${formatTime(span.end.toLocalTime())}"

    private fun label(title: String) = "«$title»"

    private fun clip(s: String?): String = s.orEmpty().replace(SPACES, " ").trim().take(CLIP)

    private fun parseTime(s: String?): LocalTime? {
        val m = s?.trim()?.let { TIME.matchEntire(it) } ?: return null
        return LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    private fun parseDate(s: String?): LocalDate? {
        val t = s?.trim()?.takeIf { DATE.matches(it) } ?: return null
        return runCatching { LocalDate.parse(t) }.getOrNull()
    }

    private enum class Kind { OWN, EXTERNAL, LUNCH }

    private class Block(val key: String, val title: String, var span: TimeSpan, val kind: Kind)

    /** Estado en curso de una validación. */
    private class Run(val ctx: PlanContext) {
        val ops = mutableListOf<PlanOp>()
        val rejected = mutableListOf<String>()
        private val changedRefs = HashSet<String>()
        private var created = 0
        private val blocks: MutableList<Block> = (
            ctx.items.map { Block(it.ref, it.title, it.span, if (it.external) Kind.EXTERNAL else Kind.OWN) } +
                listOfNotNull(ctx.lunch?.let { Block(LUNCH_KEY, LUNCH_TITLE, it, Kind.LUNCH) })
            ).toMutableList()

        fun create(c: PlanChange): String? {
            val title = c.title.orEmpty().replace(SPACES, " ").trim()
            if (title.isEmpty()) return "Claude propuso un bloque sin nombre."
            if (title.length > MAX_TITLE) return "${label(clip(title) + "…")}: el nombre es demasiado largo."
            val l = label(title)
            val type = ActivityType.entries.firstOrNull { it.name == c.activityType?.trim()?.uppercase() }
                ?: return "$l: tipo de bloque desconocido (${clip(c.activityType)})."
            if (type == ActivityType.APPOINTMENT) return "$l: ${APPOINTMENT.replaceFirstChar { it.lowercase() }}"
            if (type !in CREATABLE) return "$l: tipo de bloque desconocido (${type.name})."
            val minutes = c.durationMin ?: return "$l: falta la duración."
            val span = when (val s = span(l, c.date, c.start, minutes)) {
                is SpanCheck.Bad -> return s.reason
                is SpanCheck.Ok -> s.span
            }
            clash(l, span, selfKey = null)?.let { return it }
            blocks += Block("${NEW_KEY}${++created}", title, span, Kind.OWN)
            ops += PlanOp.Create(
                Activity(
                    title = title,
                    type = type,
                    date = span.start.toLocalDate(),
                    start = span.start.toLocalTime(),
                    durationMin = minutes,
                    repeat = Repeat.NONE,
                    // D11: los bloques de trabajo no avisan; ejercicio y personales, 15 min antes.
                    remindBeforeMin = if (type == ActivityType.WORK) null else REMIND_BEFORE_MIN,
                    copyToGoogle = false,
                    origin = ActivityOrigin.PLAN,
                ),
            )
            return null
        }

        fun move(c: PlanChange): String? {
            val item = when (val r = resolve(c.ref)) {
                is Resolved.Bad -> return r.reason
                is Resolved.Ok -> r.item
            }
            val l = label(item.title)
            val minutes = c.durationMin ?: item.span.minutes.toInt()
            val span = when (val s = span(l, c.date, c.start, minutes)) {
                is SpanCheck.Bad -> return s.reason
                is SpanCheck.Ok -> s.span
            }
            if (span == item.span) return null // Mover a donde ya está no es un cambio.
            clash(l, span, selfKey = item.ref)?.let { return it }
            blocks.first { it.key == item.ref }.span = span
            changedRefs += item.ref
            ops += PlanOp.Move(item.ref, item.activityId!!, item.occurrenceDay!!, item.title, item.span, span, item.oneOff)
            return null
        }

        fun skip(c: PlanChange): String? {
            val item = when (val r = resolve(c.ref)) {
                is Resolved.Bad -> return r.reason
                is Resolved.Ok -> r.item
            }
            blocks.removeAll { it.key == item.ref }
            changedRefs += item.ref
            ops += PlanOp.Skip(item.ref, item.activityId!!, item.occurrenceDay!!, item.title, item.span, item.oneOff)
            return null
        }

        /** Busca [ref] tal cual en el contexto y comprueba que se pueda mover o quitar. */
        private fun resolve(ref: String?): Resolved {
            val key = ref?.trim().orEmpty()
            if (key.isEmpty()) return Resolved.Bad("Claude propuso cambiar algo sin decir qué.")
            val item = ctx.items.firstOrNull { it.ref == key }
                ?: return Resolved.Bad("Claude se refirió a «${clip(key)}», que no está en tu día.")
            val l = label(item.title)
            // Regla C: lo externo nunca se toca.
            if (item.external) return Resolved.Bad("$l: no toco eventos de tu calendario externo.")
            if (item.ref in changedRefs) return Resolved.Bad("$l: Claude lo cambió dos veces; dejé solo el primer cambio.")
            // `movable` ya cubre todo esto (D4, D10); se repite aquí por si el contexto llegara mal armado.
            val reason = when {
                item.done -> "ya está hecho"
                logicalDay(item.span.start) != ctx.day -> "viene del día anterior"
                item.span.start < ctx.now -> "ya empezó"
                item.activityId == null || item.occurrenceDay == null -> "no se puede cambiar"
                item.movable -> null
                !item.oneOff -> "se repite y se copia a tu Google Calendar; por ahora no lo cambio desde un plan"
                else -> "no se puede cambiar"
            }
            return if (reason != null) Resolved.Bad("$l: $reason.") else Resolved.Ok(item)
        }

        /** Fecha, hora y duración válidas, dentro del mismo día lógico, de la vigilia y no en el pasado. */
        private fun span(l: String, date: String?, start: String?, minutes: Int): SpanCheck {
            val d = parseDate(date) ?: return SpanCheck.Bad("$l: la fecha no es válida (${clip(date)}).")
            val t = parseTime(start) ?: return SpanCheck.Bad("$l: la hora no es válida (${clip(start)}).")
            if (minutes !in MIN_DURATION..MAX_DURATION) {
                return SpanCheck.Bad("$l: la duración de $minutes min no es válida (de $MIN_DURATION a $MAX_DURATION).")
            }
            val s: LocalDateTime = d.atTime(t)
            val span = TimeSpan(s, s.plusMinutes(minutes.toLong()))
            if (logicalDay(s) != ctx.day) return SpanCheck.Bad("$l: queda en otro día; solo cambio el día que estoy armando.")
            if (s < ctx.awake.start || span.end > ctx.awake.end) {
                val awake = "${formatTime(ctx.awake.start.toLocalTime())} a ${formatTime(ctx.awake.end.toLocalTime())}"
                return SpanCheck.Bad("$l: queda fuera de tu horario despierto ($awake).")
            }
            if (s < ctx.now) return SpanCheck.Bad("$l: esa hora ya pasó.")
            return SpanCheck.Ok(span)
        }

        /** Choque con lo fijo, el almuerzo u otro bloque propio vigente (sin contarse a sí mismo). */
        private fun clash(l: String, span: TimeSpan, selfKey: String?): String? {
            val hits = blocks.filter { it.key != selfKey && it.span.overlaps(span) }
            hits.firstOrNull { it.kind == Kind.EXTERNAL }?.let { return "$l: choca con «${it.title}» de tu calendario externo." }
            hits.firstOrNull { it.kind == Kind.LUNCH }?.let { return "$l: choca con el almuerzo." }
            hits.firstOrNull()?.let { return "$l: choca con «${it.title}»." }
            return null
        }
    }

    private sealed interface Resolved {
        data class Ok(val item: PlanItemRef) : Resolved
        data class Bad(val reason: String) : Resolved
    }

    private sealed interface SpanCheck {
        data class Ok(val span: TimeSpan) : SpanCheck
        data class Bad(val reason: String) : SpanCheck
    }

    private const val LUNCH_KEY = "#almuerzo"
    private const val NEW_KEY = "#nuevo"
}
