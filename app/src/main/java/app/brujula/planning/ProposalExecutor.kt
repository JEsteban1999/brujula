package app.brujula.planning

import android.util.Log
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import app.brujula.activities.Activity
import app.brujula.activities.ActivityOrigin
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.OccurrenceOverride
import app.brujula.activities.Repeat
import app.brujula.activities.form.REMIND_BEFORE_MIN
import app.brujula.activities.occurrencesOn
import app.brujula.calendar.GoogleCopyJournal
import app.brujula.calendar.GoogleCopySync
import app.brujula.claude.ClaudeNote
import app.brujula.claude.ClaudeNotes
import app.brujula.core.time.Clock
import app.brujula.core.time.TimeSpan
import app.brujula.reminders.ReminderPlanner
import app.brujula.reminders.ReminderResync
import app.brujula.reminders.ReminderScheduler
import app.brujula.routine.logicalDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

sealed interface AcceptResult {
    data class Accepted(val proposal: Proposal) : AcceptResult
    /** Ya no está pendiente (aceptada, reemplazada o descartada): no se aplica otra vez. */
    data object AlreadyAccepted : AcceptResult
    data class Stale(val message: String) : AcceptResult
    data class Expired(val message: String) : AcceptResult
    data class Failed(val message: String) : AcceptResult

    companion object {
        const val STALE = "Tu agenda cambió desde esta propuesta."
        const val EXPIRED = "Ya pasó parte de esta propuesta."
        const val FAILED = "No pude aplicar el plan. No cambié nada."
    }
}

sealed interface UndoResult {
    /** [kept]: cambios que el usuario hizo después de aceptar y que se dejaron como estaban (D16). */
    data class Undone(val kept: Int) : UndoResult
    data object NotAccepted : UndoResult
    data class Failed(val message: String) : UndoResult

    companion object {
        const val FAILED = "No pude deshacer el plan. No cambié nada."
        const val PAST = "Ese día ya pasó; no deshice nada."

        /** `Dejé {kept} cambio(s) que hiciste después.`; `null` si no se dejó nada. */
        fun keptMessage(kept: Int): String? = when {
            kept <= 0 -> null
            kept == 1 -> "Dejé 1 cambio que hiciste después."
            else -> "Dejé $kept cambios que hiciste después."
        }
    }
}

/**
 * Un solo vuelo para aceptar y deshacer. AppContainer comparte uno entre todas las instancias de [ProposalExecutor] que
 * arma, así un doble toque nunca aplica dos veces (además de la revisión de PENDING dentro de la transacción).
 */
class ProposalLock {
    internal val mutex = Mutex()

    /** Última tanda de copias a Google: la siguiente espera a que termine (p. ej., borrar la copia antes de recrearla). */
    internal var lastGoogle: Job? = null
}

/**
 * `Aceptar plan` y `Deshacer` (D3, D16), con el patrón de ActionExecutor:
 * - todo cambio de la base va en una sola transacción de Room; cualquier fallo la revierte entera;
 * - después del commit, el resync de avisos corre sin cancelación y las copias de Google en [syncScope]
 *   (producción: `container.syncScope`), nunca en el ámbito de la pantalla;
 * - solo se escribe en Google con [GoogleCopySync] y para actividades propias: nunca un evento que no sea copia de Brújula.
 *
 * Los datos para deshacer ([UndoData]) se capturan al aceptar, en la misma transacción. Deshacer vuelve a crear una
 * actividad de una sola vez quitada con el **mismo id** (AUTOINCREMENT nunca reutiliza ids, así que está libre): sus
 * marcas de hecho y referencias siguen valiendo. Se reinserta sin vínculo de Google (esa copia se borró al aceptar) y
 * `afterSave` crea la copia nueva, o adopta la vieja si el borrado no llegó a correr.
 */
open class ProposalExecutor(
    private val database: RoomDatabase,
    private val activityRepo: ActivityRepository,
    private val proposals: ProposalRepository,
    private val googleCopySync: GoogleCopySync,
    private val reminderSync: ReminderResync,
    private val notes: ClaudeNotes,
    private val clock: Clock,
    private val syncScope: CoroutineScope,
    /** Para cancelar la espera "En 15 min" de una ocurrencia quitada o movida; `null` = no hay alarmas que cancelar. */
    private val reminderScheduler: ReminderScheduler? = null,
    /** Copias a Google pendientes, persistidas antes del commit (producción: `container.googleCopyJournal`). */
    private val journal: GoogleCopyJournal = GoogleCopyJournal(),
    internal val lock: ProposalLock = ProposalLock(),
) {
    /** Solo para pruebas: se invoca dentro de la transacción, después de aplicar los cambios y antes de marcarla aceptada. */
    internal var afterApply: (suspend () -> Unit)? = null

    /** Lo que se hace después del commit. */
    private class Effects(val google: List<GoogleCopyJournal.Entry>, val snoozes: List<String>, val note: ClaudeNote)

    private class Outcome<R>(val result: R, val effects: Effects? = null)

    /**
     * Sin cancelación a medias: con Room 2.6, cancelar a quien llama durante `withTransaction` le devuelve la
     * cancelación mientras el hilo de la transacción igual confirma; así nunca queda ACCEPTED sin resync, Google ni nota.
     */
    open suspend fun accept(id: Long): AcceptResult = lock.mutex.withLock {
        withContext(NonCancellable) {
            val now = clock.now()
            val outcome = try {
                database.withTransaction { acceptInTransaction(id, now) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo aplicar la propuesta: ${e.javaClass.simpleName}")
                return@withContext AcceptResult.Failed(AcceptResult.FAILED)
            }
            outcome.effects?.let { runEffects(it) }
            outcome.result
        }
    }

    /** Sin cancelación a medias, como el deshacer de ActionExecutor. */
    open suspend fun undo(id: Long): UndoResult = lock.mutex.withLock {
        withContext(NonCancellable) {
            val now = clock.now()
            val outcome = try {
                database.withTransaction { undoInTransaction(id, now) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo deshacer la propuesta: ${e.javaClass.simpleName}")
                return@withContext UndoResult.Failed(UndoResult.FAILED)
            }
            outcome.effects?.let { runEffects(it) }
            outcome.result
        }
    }

    /**
     * Lo mismo que revisa `accept` antes de aplicar, sin aplicar nada: el día ya pasó, algo de hoy ya empezó, o lo que la
     * propuesta toca cambió. La pantalla lo usa para no mostrar como vigente una propuesta guardada que ya no sirve.
     */
    open suspend fun isStale(p: Proposal): Boolean = isExpired(p, clock.now()) || !p.ops.all { isCurrent(it) }

    private suspend fun acceptInTransaction(id: Long, now: LocalDateTime): Outcome<AcceptResult> {
        val status = proposals.statusOf(id) ?: return Outcome(AcceptResult.Failed(AcceptResult.FAILED))
        if (status != ProposalStatus.PENDING) return Outcome(AcceptResult.AlreadyAccepted)
        // Un payload ilegible de una pendiente queda DISCARDED (eso sí se confirma) y no se aplica nada.
        val p = proposals.get(id) ?: return Outcome(AcceptResult.Failed(AcceptResult.FAILED))
        if (isExpired(p, now)) return Outcome(AcceptResult.Expired(AcceptResult.EXPIRED))
        if (!p.ops.all { isCurrent(it) }) return Outcome(AcceptResult.Stale(AcceptResult.STALE))

        val createdIds = mutableListOf<Long>()
        val oneOffBefore = mutableListOf<Activity>()
        val oneOffAfter = mutableListOf<Activity>()
        val deleted = mutableListOf<Activity>()
        val overrideBefore = mutableListOf<Pair<Pair<Long, LocalDate>, OccurrenceOverride?>>()
        val overrideAfter = mutableListOf<OccurrenceOverride>()
        val snoozes = mutableListOf<String>()

        suspend fun setOverride(id: Long, day: LocalDate, after: OccurrenceOverride) {
            overrideBefore += (id to day) to activityRepo.getOverride(id, day)
            activityRepo.setOverride(after)
            overrideAfter += after
        }

        for (op in p.ops) {
            when (op) {
                is PlanOp.Create -> createdIds += activityRepo.upsert(planBlock(op.activity))
                is PlanOp.Move -> {
                    snoozes += ReminderPlanner.activitySnoozeKey(op.activityId, op.occurrenceDay)
                    if (op.oneOff) {
                        val before = requireNotNull(activityRepo.get(op.activityId))
                        activityRepo.upsert(
                            before.copy(date = op.to.start.toLocalDate(), start = op.to.start.toLocalTime(), durationMin = op.to.minutes.toInt()),
                        )
                        oneOffBefore += before
                        oneOffAfter += requireNotNull(activityRepo.get(op.activityId))
                    } else {
                        setOverride(op.activityId, op.occurrenceDay, OccurrenceOverride(op.activityId, op.occurrenceDay, op.to.start, op.to.minutes.toInt(), skipped = false))
                    }
                }
                is PlanOp.Skip -> {
                    snoozes += ReminderPlanner.activitySnoozeKey(op.activityId, op.occurrenceDay)
                    if (op.oneOff) {
                        deleted += requireNotNull(activityRepo.get(op.activityId))
                        activityRepo.delete(op.activityId)
                    } else {
                        setOverride(op.activityId, op.occurrenceDay, OccurrenceOverride(op.activityId, op.occurrenceDay, null, null, skipped = true))
                    }
                }
            }
        }
        // El diario se escribe (síncrono) antes del commit: si el proceso muere antes de la tanda, el arranque la completa.
        val google = oneOffAfter.filter { it.copyToGoogle }.map { journal.save(it.id) } +
            deleted.filter { it.copyToGoogle }.map { journal.delete(it) }
        journal.record(google)
        afterApply?.invoke()
        val undo = UndoData(createdIds, oneOffBefore, oneOffAfter, deleted, overrideBefore, overrideAfter)
        proposals.markAccepted(id, ProposalCodec.encodeUndo(undo), now)
        return Outcome(
            AcceptResult.Accepted(p.copy(status = ProposalStatus.ACCEPTED)),
            Effects(google = google, snoozes = snoozes, note = ClaudeNote(p.summary, p.day, p.id)),
        )
    }

    private suspend fun undoInTransaction(id: Long, now: LocalDateTime): Outcome<UndoResult> {
        if (proposals.statusOf(id) != ProposalStatus.ACCEPTED) return Outcome(UndoResult.NotAccepted)
        // Una aceptada con payload ilegible no se descarta (sus cambios siguen en la agenda): solo no se puede deshacer.
        val p = proposals.get(id) ?: return Outcome(UndoResult.Failed(UndoResult.FAILED))
        if (p.day < logicalDay(now)) return Outcome(UndoResult.Failed(UndoResult.PAST))
        val u = proposals.undoOf(id)?.let { ProposalCodec.decodeUndo(it) } ?: return Outcome(UndoResult.Failed(UndoResult.FAILED))

        // Cada elemento se revierte solo si sigue como lo dejó el plan; si no, se deja y se cuenta (salvo que ya esté
        // como lo dejaría deshacer: entonces no hay nada que informar).
        var kept = 0
        val restored = mutableListOf<Activity>()

        val creates = p.ops.filterIsInstance<PlanOp.Create>()
        u.createdIds.forEachIndexed { i, createdId ->
            val current = activityRepo.get(createdId) ?: return@forEachIndexed
            val planned = creates.getOrNull(i)?.let { planBlock(it.activity).copy(id = createdId) }
            // Un bloque que ya terminó es historia del día ("Así te fue hoy"): se deja, como una edición posterior.
            val ended = current.date.atTime(current.start).plusMinutes(current.durationMin.toLong()) <= now
            if (planned != null && sameRow(current, planned) && !ended && !activityRepo.isDone(createdId, current.date)) {
                activityRepo.delete(createdId)
            } else {
                kept++
            }
        }

        u.oneOffAfter.forEachIndexed { i, after ->
            val before = u.oneOffBefore[i]
            val current = activityRepo.get(after.id)
            if (current != null && sameRow(current, after) && !activityRepo.isDone(after.id, after.date)) {
                // Sin googleEventId: el upsert conserva el vínculo guardado (la copia se actualiza con afterSave).
                activityRepo.upsert(before.copy(googleEventId = null))
                activityRepo.get(before.id)?.let { restored += it }
            } else if (current == null || !sameRow(current, before)) {
                kept++
            }
        }

        u.overrideAfter.forEachIndexed { i, after ->
            val (key, before) = u.overrideBefore[i]
            val (activityId, day) = key
            val current = activityRepo.getOverride(activityId, day)
            if (current == after && !activityRepo.isDone(activityId, day)) {
                if (before == null) activityRepo.clearOverride(activityId, day) else activityRepo.setOverride(before)
            } else if (current != before) {
                kept++
            }
        }

        for (gone in u.deleted) {
            if (activityRepo.get(gone.id) != null) continue
            activityRepo.upsert(gone.copy(googleEventId = null))
            activityRepo.get(gone.id)?.let { restored += it }
        }

        val google = restored.filter { it.copyToGoogle }.map { journal.save(it.id) }
        journal.record(google)
        proposals.reopen(id, p.day)
        return Outcome(
            UndoResult.Undone(kept),
            Effects(google = google, snoozes = emptyList(), note = ClaudeNote(UNDONE_NOTE, p.day, null)),
        )
    }

    /** El día ya pasó, o algo de hoy ya empezó o quedaría en el pasado. */
    private fun isExpired(p: Proposal, now: LocalDateTime): Boolean {
        if (p.day < logicalDay(now)) return true
        return p.ops.any { op ->
            when (op) {
                is PlanOp.Create -> op.activity.date.atTime(op.activity.start) < now
                is PlanOp.Move -> op.from.start < now || op.to.start < now
                is PlanOp.Skip -> op.from.start < now
            }
        }
    }

    /** La ocurrencia sigue como cuando se propuso: existe, no está hecha, es de la misma clase y está en [PlanOp.Move.from]. */
    private suspend fun isCurrent(op: PlanOp): Boolean = when (op) {
        is PlanOp.Create -> true
        is PlanOp.Move -> isCurrent(op.activityId, op.occurrenceDay, op.from, op.oneOff)
        // Borrar una de una sola vez arrastra en cascada sus marcas y overrides: si tiene alguno (otra fecha), no se toca.
        is PlanOp.Skip -> isCurrent(op.activityId, op.occurrenceDay, op.from, op.oneOff) &&
            !(op.oneOff && activityRepo.hasDependents(op.activityId))
    }

    private suspend fun isCurrent(activityId: Long, day: LocalDate, from: TimeSpan, oneOff: Boolean): Boolean {
        val a = activityRepo.get(activityId) ?: return false
        if ((a.repeat == Repeat.NONE) != oneOff) return false
        // D4: una recurrente con copia a Google es fija; si llegó hasta aquí (copia activada después), no se toca.
        if (!oneOff && a.copyToGoogle) return false
        if (activityRepo.isDone(activityId, day)) return false
        val override = activityRepo.getOverride(activityId, day)
        val occurrence = occurrencesOn(day, listOf(a), emptySet(), override?.let { mapOf((activityId to day) to it) }.orEmpty())
            .firstOrNull() ?: return false
        return occurrence.span == from
    }

    private suspend fun runEffects(e: Effects) {
        // Google primero (en syncScope, no espera), así un resync lento no la retrasa.
        launchGoogle(e.google)
        // Antes del resync: replaceAll ya no vuelve a armar esas esperas.
        e.snoozes.forEach { key ->
            runCatching { reminderScheduler?.cancelSnooze(key) }.onFailure { Log.w(TAG, "No se pudo cancelar la espera: ${it.javaClass.simpleName}") }
        }
        resyncSafely()
        try {
            notes.set(e.note)
        } catch (ex: Exception) {
            Log.w(TAG, "No se pudo guardar la nota de Claude: ${ex.javaClass.simpleName}")
        }
    }

    /** Llamado con el Mutex tomado: las tandas quedan en orden (borrar la copia vieja antes de crear la nueva). */
    private fun launchGoogle(entries: List<GoogleCopyJournal.Entry>) {
        if (entries.isEmpty()) return
        val previous = lock.lastGoogle
        lock.lastGoogle = syncScope.launch {
            previous?.join()
            // Cada entrada relee la fila por id (nunca la foto del commit) y sale del diario al terminar.
            entries.forEach { journal.run(it, googleCopySync, activityRepo) }
        }
    }

    private suspend fun resyncSafely() {
        try {
            reminderSync.resync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Resync de recordatorios falló: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "ProposalExecutor"
        const val UNDONE_NOTE = "Deshice los cambios del plan."

        /** D11: un bloque de un plan es de una sola vez, sin copia a Google, con origen PLAN; avisa salvo si es trabajo. */
        internal fun planBlock(a: Activity): Activity {
            require(a.type != ActivityType.APPOINTMENT) { "Un plan no crea citas." }
            return a.copy(
                id = 0, repeat = Repeat.NONE, copyToGoogle = false, googleEventId = null, origin = ActivityOrigin.PLAN,
                remindBeforeMin = if (a.type == ActivityType.WORK) null else REMIND_BEFORE_MIN,
            )
        }

        /** Misma fila, sin contar el vínculo de Google (la copia puede crearse o adoptarse después). */
        private fun sameRow(a: Activity, b: Activity) = a.copy(googleEventId = null) == b.copy(googleEventId = null)
    }
}
