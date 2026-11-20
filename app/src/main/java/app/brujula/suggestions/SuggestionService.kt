package app.brujula.suggestions

import app.brujula.claude.DictationInterpreter
import app.brujula.core.time.Clock
import app.brujula.habits.Habit
import app.brujula.habits.HabitRepository
import app.brujula.reminders.ReminderResync
import app.brujula.routine.logicalDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Lo que muestra la tarjeta de Hábitos: la sugerencia pendiente, o la aceptada hoy con su "Listo: …". */
data class SuggestionCard(val id: Long, val message: String, val accepted: Boolean, val doneText: String?)

sealed interface SuggestRequestResult {
    /** Hay una sugerencia nueva: la muestra la tarjeta. */
    data object Shown : SuggestRequestResult
    data class Message(val text: String) : SuggestRequestResult
}

/**
 * Guarda, pide, acepta y deshace las sugerencias de hábitos (D12, D15). Pedir a Claude es de un solo vuelo (la corrida
 * automática y la pedida a mano comparten el cerrojo); aceptar y deshacer van con un [Mutex]. Todo lo que escribe el hábito
 * y resincroniza los avisos corre junto en `NonCancellable`.
 */
class SuggestionService(
    private val store: SuggestionStore,
    private val suggester: HabitSuggester,
    private val habitRepository: HabitRepository,
    private val resync: ReminderResync,
    private val clock: Clock,
    private val syncScope: CoroutineScope,
    private val hasKey: suspend () -> Boolean,
    private val isOnline: suspend () -> Boolean,
) {
    private val requesting = MutableStateFlow(false)

    /** Hay una petición a Claude en curso (automática o pedida a mano). */
    val running: StateFlow<Boolean> = requesting.asStateFlow()
    private val decideMutex = Mutex()

    /** La pendiente más nueva, o la aceptada en el día lógico de hoy. */
    fun card(): Flow<SuggestionCard?> = store.entries.map { list ->
        val pending = list.firstOrNull { it.status == SuggestionStatus.PENDING }
        val shown = pending ?: list.firstOrNull {
            it.status == SuggestionStatus.ACCEPTED && it.decidedAt?.let { d -> logicalDay(d) == logicalDay(clock.now()) } == true
        }
        shown?.let {
            val accepted = it.status == SuggestionStatus.ACCEPTED
            SuggestionCard(it.id, it.suggestion.message, accepted, if (accepted) it.suggestion.doneText() else null)
        }
    }

    /**
     * Corrida automática (D12), en [syncScope]: ≥ 7 días desde la última corrida exitosa, ≥ 1 día desde el último intento,
     * con clave y red, y ≥ [HabitSuggester.MIN_DAYS_AUTO] días con registros. Nunca en bucle: cada llamada es una sola
     * pasada y un fallo espera al día siguiente (o a una pedida a mano). null si no corre.
     */
    fun maybeRunAuto(): Job? {
        if (!requesting.compareAndSet(false, true)) return null
        return syncScope.launch {
            try {
                runAuto()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Un fallo local (DataStore, base) no reintenta hasta mañana.
                runCatching { withContext(NonCancellable) { store.markAttempt(clock.now(), success = false) } }
            } finally {
                requesting.value = false
            }
        }
    }

    private suspend fun runAuto() {
        val now = clock.now()
        store.lastRun()?.let { if (it.plusDays(AUTO_EVERY_DAYS) > now) return }
        store.lastAttempt()?.let { if (it.plusDays(1) > now) return }
        if (!hasKey() || !isOnline()) return
        record(suggester.suggest(HabitSuggester.MIN_DAYS_AUTO))
    }

    /** Pedida a mano: ignora el límite semanal pero pide [HabitSuggester.MIN_DAYS_ON_DEMAND] días de registros. */
    suspend fun requestNow(): SuggestRequestResult {
        if (!hasKey()) return SuggestRequestResult.Message(HabitSuggester.NO_KEY)
        if (!isOnline()) return SuggestRequestResult.Message(HabitSuggester.OFFLINE)
        if (!requesting.compareAndSet(false, true)) return SuggestRequestResult.Message(THINKING)
        try {
            return when (val outcome = suggester.suggest(HabitSuggester.MIN_DAYS_ON_DEMAND)) {
                is SuggestOutcome.Ok -> {
                    record(outcome)
                    if (outcome.suggestion == null) SuggestRequestResult.Message(HabitSuggester.NONE) else SuggestRequestResult.Shown
                }
                SuggestOutcome.NotEnoughData -> SuggestRequestResult.Message(HabitSuggester.NOT_ENOUGH)
                SuggestOutcome.NoKey -> SuggestRequestResult.Message(HabitSuggester.NO_KEY)
                SuggestOutcome.InvalidKey -> { record(outcome); SuggestRequestResult.Message(DictationInterpreter.INVALID_KEY) }
                SuggestOutcome.Offline -> { record(outcome); SuggestRequestResult.Message(HabitSuggester.OFFLINE) }
                is SuggestOutcome.Refused, is SuggestOutcome.Failed -> {
                    record(outcome)
                    SuggestRequestResult.Message(DictationInterpreter.UNAVAILABLE)
                }
            }
        } finally {
            requesting.value = false
        }
    }

    /**
     * Guarda la sugerencia y anota el intento, juntos y sin cancelación. Sin llamada real (pocos datos, sin clave) no cuenta
     * como intento. `success` = Claude respondió, haya o no sugerencia; un rechazo cuenta como respuesta.
     */
    private suspend fun record(outcome: SuggestOutcome) {
        val now = clock.now()
        withContext(NonCancellable) {
            when (outcome) {
                is SuggestOutcome.Ok -> {
                    outcome.suggestion?.let { store.savePending(it, nextId()) }
                    store.markAttempt(now, success = true)
                }
                is SuggestOutcome.Refused -> store.markAttempt(now, success = true)
                is SuggestOutcome.Failed, SuggestOutcome.Offline, SuggestOutcome.InvalidKey -> store.markAttempt(now, success = false)
                SuggestOutcome.NotEnoughData, SuggestOutcome.NoKey -> Unit
            }
        }
    }

    private suspend fun nextId(): Long = (store.entries.first().maxOfOrNull { it.id } ?: 0L) + 1

    /** null si se aplicó; si no, el motivo para mostrar. */
    suspend fun accept(id: Long): String? = decideMutex.withLock {
        val entry = store.entries.first().firstOrNull { it.id == id } ?: return@withLock CHANGED
        if (entry.status == SuggestionStatus.ACCEPTED) return@withLock null
        if (entry.status != SuggestionStatus.PENDING) return@withLock CHANGED
        val s = entry.suggestion
        val habit = currentHabit(s.habitId)
        if (habit == null || !habit.matches(s.before)) {
            store.setStatus(id, SuggestionStatus.STALE, clock.now())
            return@withLock CHANGED
        }
        withContext(NonCancellable) {
            habitRepository.upsert(habit.copy(schedule = s.after.schedule, dailyTarget = s.after.dailyTarget))
            store.setStatus(id, SuggestionStatus.ACCEPTED, clock.now())
            runCatching { resync.resync() }
        }
        null
    }

    /** Devuelve el texto para mostrar: deshecho, o que el hábito ya cambió y no se tocó. */
    suspend fun undo(id: Long): String = decideMutex.withLock {
        val entry = store.entries.first().firstOrNull { it.id == id }
        if (entry == null || entry.status != SuggestionStatus.ACCEPTED) return@withLock KEPT
        val s = entry.suggestion
        val habit = currentHabit(s.habitId)
        if (habit == null || !habit.matches(s.after)) {
            store.setStatus(id, SuggestionStatus.STALE, clock.now())
            return@withLock KEPT
        }
        withContext(NonCancellable) {
            habitRepository.upsert(habit.copy(schedule = s.before.schedule, dailyTarget = s.before.dailyTarget))
            store.setStatus(id, SuggestionStatus.UNDONE, clock.now())
            runCatching { resync.resync() }
        }
        UNDONE_TEXT
    }

    suspend fun dismiss(id: Long) {
        decideMutex.withLock {
            val entry = store.entries.first().firstOrNull { it.id == id }
            if (entry?.status == SuggestionStatus.PENDING) store.setStatus(id, SuggestionStatus.DISMISSED, clock.now())
        }
    }

    private suspend fun currentHabit(id: Long): Habit? = habitRepository.observeHabits().first().firstOrNull { it.id == id }

    private fun Habit.matches(c: HabitChange) = schedule == c.schedule && dailyTarget == c.dailyTarget

    companion object {
        const val CHANGED = "Ese hábito cambió desde la sugerencia. No lo toqué."
        const val KEPT = "Ya cambiaste ese hábito; no lo toqué."
        const val UNDONE_TEXT = "Deshice el cambio."
        const val THINKING = "Claude está mirando tus hábitos…"
        private const val AUTO_EVERY_DAYS = 7L
    }
}
