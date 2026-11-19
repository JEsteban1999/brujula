package app.brujula.claude

import app.brujula.activities.ActivityOrigin
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.core.time.Clock
import app.brujula.core.time.TimeSpan
import app.brujula.habits.HabitRepository
import app.brujula.planning.DaySnapshotProvider
import app.brujula.planning.DaySummary
import app.brujula.routine.DayType
import app.brujula.routine.Routine
import app.brujula.routine.RoutineRepository
import app.brujula.routine.awakeWindow
import app.brujula.routine.dayTypeOf
import app.brujula.routine.logicalDay
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Algo que ya hay en el día a armar, con la referencia corta que ve Claude: `A{activityId}` para lo propio y `E{n}` (1..n,
 * por hora) para lo externo. La app resuelve los cambios con estos datos, nunca con lo que diga Claude (D2).
 */
data class PlanItemRef(
    val ref: String,
    val title: String,
    val span: TimeSpan,
    val external: Boolean,
    val type: ActivityType?,
    /** Propio, no hecho, del día lógico (no spill), aún no empezado y no recurrente con copia a Google (D4). */
    val movable: Boolean,
    val done: Boolean,
    val countsAsWork: Boolean,
    val activityId: Long?,
    /** Fecha de la ocurrencia en la serie (clave de hechos y overrides); null en lo externo. */
    val occurrenceDay: LocalDate?,
    val oneOff: Boolean,
    val origin: ActivityOrigin?,
    /** Origen del evento externo ("Google", "Outlook" o el nombre de la cuenta); null en lo propio. */
    val sourceLabel: String?,
)

/** Todo lo que se le envía a Claude para armar [day], más los datos locales para validar su respuesta. */
data class PlanContext(
    val now: LocalDateTime,
    val zone: ZoneId,
    val logicalToday: LocalDate,
    val day: LocalDate,
    val dayType: DayType,
    val routine: Routine,
    val awake: TimeSpan,
    /** "Almuerzo y pausa", fijo (D7); null en descanso o con menos de dos bloques de trabajo. */
    val lunch: TimeSpan?,
    /** Por hora de inicio. */
    val items: List<PlanItemRef>,
    /** Primera hora ocupada (propia o externa) del día lógico siguiente. */
    val nextDayFirstStart: LocalDateTime?,
    val habitNames: List<String>,
    /** Cómo va hoy (día lógico del reloj), aunque se arme otro día. */
    val today: DaySummary,
)

/** D7: en días laborales, el hueco entre los dos primeros bloques de trabajo de la rutina (por defecto 12:00–14:00). */
fun Routine.lunchWindow(day: LocalDate): TimeSpan? {
    if (dayTypeOf(day).isRest) return null
    val blocks = workBlocks.sortedBy { it.first }
    if (blocks.size < 2) return null
    val start = blocks[0].second
    val end = blocks[1].first
    if (end <= start) return null
    return TimeSpan(day.atTime(start), day.atTime(end))
}

class PlanContextProvider(
    private val clock: Clock,
    private val snapshots: DaySnapshotProvider,
    private val habitRepository: HabitRepository,
    private val routineRepository: RoutineRepository,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    suspend fun forDay(day: LocalDate): PlanContext {
        val now = clock.now()
        val today = logicalDay(now)
        val routine = routineRepository.routine.first()
        val snapshot = snapshots.snapshot(day)
        val next = snapshots.snapshot(day.plusDays(1))
        val todaySummary = if (day == today) snapshot.summary else snapshots.snapshot(today).summary

        val (logical, spill) = snapshot.own.partition { logicalDay(it.span.start) == day }
        val used = HashSet<String>()
        // Lo del día lógico primero, para que conserve `A{id}`; una ocurrencia de ayer de la misma serie (spill) toma un sufijo.
        fun refFor(id: Long): String {
            val base = "A$id"
            var ref = base
            var n = 2
            while (!used.add(ref)) ref = "$base-${n++}"
            return ref
        }
        val own = (logical.map { it to false } + spill.map { it to true }).map { (occ, isSpill) ->
            val a = occ.activity
            PlanItemRef(
                ref = refFor(a.id),
                title = a.title,
                span = occ.span,
                external = false,
                type = a.type,
                movable = !isSpill && !occ.done && occ.span.start >= now && !(a.repeat != Repeat.NONE && a.copyToGoogle),
                done = occ.done,
                countsAsWork = a.type == ActivityType.WORK,
                activityId = a.id,
                occurrenceDay = occ.day,
                oneOff = a.repeat == Repeat.NONE,
                origin = a.origin,
                sourceLabel = null,
            )
        }
        val external = snapshot.external.sortedBy { it.event.span.start }.mapIndexed { i, f ->
            PlanItemRef(
                ref = "E${i + 1}",
                title = f.event.displayTitle,
                span = f.event.span,
                external = true,
                type = null,
                movable = false,
                done = false,
                countsAsWork = f.countsAsWork,
                activityId = null,
                occurrenceDay = null,
                oneOff = false,
                origin = null,
                sourceLabel = f.event.source,
            )
        }

        val nextDay = day.plusDays(1)
        val nextStarts = next.own.map { it.span.start } + next.external.map { it.event.span.start }

        return PlanContext(
            now = now,
            zone = zone(),
            logicalToday = today,
            day = day,
            dayType = dayTypeOf(day),
            routine = routine,
            awake = routine.awakeWindow(day),
            lunch = routine.lunchWindow(day),
            items = (own + external).sortedBy { it.span.start },
            nextDayFirstStart = nextStarts.filter { logicalDay(it) == nextDay }.minOrNull(),
            habitNames = habitRepository.observeHabits().first().filter { it.active }.map { it.name },
            today = todaySummary,
        )
    }
}
