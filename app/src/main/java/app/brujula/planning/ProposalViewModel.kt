package app.brujula.planning

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.brujula.claude.DayPlanner
import app.brujula.claude.PlanOutcome
import app.brujula.core.time.Clock
import app.brujula.core.time.formatTime
import app.brujula.routine.logicalDay
import app.brujula.voice.SpeechEvent
import app.brujula.voice.SpeechFailure
import app.brujula.voice.SpeechInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** Panel `Ajustar` (D9): [partial] es lo que se va entendiendo mientras se habla. */
data class AdjustState(
    val listening: Boolean = false,
    val partial: String = "",
    val speechAvailable: Boolean = true,
    val hint: String? = null,
)

sealed interface ProposalUiState {
    /** `Claude está armando tu día…`: puede tardar hasta unos 3 minutos; se puede cancelar. */
    data object Loading : ProposalUiState

    /** [message]: aviso bajo el resumen (desactualizada, ajuste fallido…). [busy]: aceptando; los botones se apagan. */
    data class Showing(
        val proposal: Proposal,
        val dayTitle: String,
        val stale: Boolean,
        val message: String?,
        val adjusting: AdjustState?,
        val busy: Boolean = false,
    ) : ProposalUiState

    data class Accepted(val title: String, val body: String, val message: String? = null, val busy: Boolean = false) : ProposalUiState
    data class Error(val message: String, val retry: Boolean) : ProposalUiState
    data object Closed : ProposalUiState
}

object ProposalCopy {
    const val LOADING = "Claude está armando tu día…"
    const val STALE = AcceptResult.STALE
    const val NOTHING_TO_CHANGE = "No hace falta cambiar nada."
    const val REJECTED_TITLE = "No pude aplicar:"
    const val MIC_ERROR = "No pude usar el micrófono. Escribe el ajuste."
    const val NOT_HEARD = "No te escuché. Prueba otra vez o escríbelo."
    const val NO_VOICE = "Este teléfono no tiene dictado. Escribe el ajuste."
    const val SPEECH_NETWORK = "El dictado necesita internet. Escribe el ajuste."
    const val ALREADY_DONE = "Esta propuesta ya no está vigente."
}

/**
 * Pantalla Propuesta: pide (o recupera) la propuesta de [day], la muestra, y deja aceptar, ajustar, descartar y deshacer.
 *
 * - La propuesta vigente siempre sale del repositorio, nunca de una copia vieja: deshacer puede reemplazar a una pendiente
 *   más nueva, y una pendiente guardada sobrevive a la muerte del proceso.
 * - Esperar a Claude puede tardar minutos: [cancelWaiting] deja de esperar y vuelve a lo anterior; una respuesta tardía se
 *   descarta si ya no se espera (una "generación" por petición).
 * - Aceptar y deshacer son de un solo vuelo aquí y en [ProposalExecutor]; terminan aunque se cierre la pantalla.
 */
class ProposalViewModel(
    private val day: LocalDate,
    private val request: String?,
    private val planner: DayPlanner,
    private val proposals: ProposalRepository,
    private val executor: ProposalExecutor,
    private val speech: SpeechInput,
    private val clock: Clock,
    private val wakeFor: suspend (LocalDate) -> LocalTime,
    /** Cuándo suena el aviso de dormir tras aceptar (Task 7); `null` = no hay. */
    private val bedtimeRemindAt: suspend () -> LocalDateTime? = { null },
    scopeOverride: CoroutineScope? = null,
    /** Sobrevive a la muerte del proceso: qué propuesta se mostraba y si el pedido de la ruta ya se usó. */
    private val saved: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val scope: CoroutineScope = scopeOverride ?: viewModelScope

    private val _state = MutableStateFlow<ProposalUiState>(ProposalUiState.Loading)
    val state: StateFlow<ProposalUiState> = _state.asStateFlow()

    private var generation = 0
    private var job: Job? = null
    /** Qué había antes de esperar a Claude (un ajuste); `null` = primera carga: al cancelar se cierra. */
    private var beforeWaiting: ProposalUiState.Showing? = null
    private var acting = false
    private var acceptedId: Long? = null
    private var lastRequest: String? = request

    init {
        job = scope.launch { open() }
    }

    /** El pedido de la ruta solo vale una vez: al rotar, volver o revivir el proceso no se vuelve a pedir a Claude. */
    private val freshRequest: String? get() = request?.takeIf { saved.get<Boolean>(KEY_CONSUMED) != true }

    private var loadFailed = false

    private suspend fun open() {
        try {
            loadFailed = false
            openStored()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadFailed = true
            _state.value = ProposalUiState.Error(LOAD_FAILED, retry = true)
        }
    }

    private suspend fun openStored() {
        val fresh = freshRequest
        if (fresh == null) {
            // 1. Lo que se mostraba antes (rotación o proceso muerto): se prefiere por id, según su estado.
            val savedId = saved.get<Long>(KEY_SHOWN)
            val stored = savedId?.let { proposals.get(it) }
            when (stored?.status) {
                ProposalStatus.ACCEPTED -> return showAccepted(stored)
                ProposalStatus.PENDING -> return showStored(stored)
                else -> Unit
            }
            // 2. La pendiente del día; si no hay, el plan ya aceptado del día (con su Deshacer); si no, se pide.
            val pending = proposals.pendingFor(day)
            if (pending != null && !executor.isStale(pending)) return show(pending)
            if (pending == null && day >= logicalDay(clock.now())) proposals.acceptedFor(day)?.let { return showAccepted(it) }
            lastRequest = pending?.request ?: lastRequest
        }
        saved[KEY_CONSUMED] = true
        generate(fresh ?: lastRequest, previous = null, adjustment = null, back = null)
    }

    private suspend fun showStored(p: Proposal) {
        if (executor.isStale(p)) show(p, message = ProposalCopy.STALE, stale = true) else show(p)
    }

    private suspend fun showAccepted(p: Proposal) {
        acceptedId = p.id
        saved[KEY_SHOWN] = p.id
        _state.value = confirmation()
    }

    /** Pasa a "esperando" al instante (antes de lanzar nada) y pide a Claude; reemplaza cualquier espera anterior. */
    private fun startGenerate(req: String?, previous: Proposal?, adjustment: String?, back: ProposalUiState.Showing?) {
        job?.cancel()
        generation++
        beforeWaiting = back
        _state.value = ProposalUiState.Loading
        val mine = generation
        job = scope.launch { run(mine, req, previous, adjustment, back) }
    }

    // ---- pedir a Claude ----

    private suspend fun generate(req: String?, previous: Proposal?, adjustment: String?, back: ProposalUiState.Showing?) {
        generation++
        beforeWaiting = back
        _state.value = ProposalUiState.Loading
        run(generation, req, previous, adjustment, back)
    }

    private suspend fun run(mine: Int, req: String?, previous: Proposal?, adjustment: String?, back: ProposalUiState.Showing?) {
        val outcome = try {
            planner.propose(day, req, previous, adjustment)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PlanOutcome.Failed(DayPlanner.CONTEXT_FAILED, retryable = true)
        }
        // Una respuesta tardía no pisa lo que el usuario ya ve: solo vale si sigue esperando esta petición.
        if (mine != generation || _state.value != ProposalUiState.Loading) return
        when (outcome) {
            is PlanOutcome.Ok -> save(outcome.proposal, mine, back)
            else -> fail(messageOf(outcome), retryable(outcome), req, back)
        }
    }

    private suspend fun save(p: Proposal, mine: Int, back: ProposalUiState.Showing?) {
        // Una vez que empieza a guardarse no se corta a medias; si el usuario canceló justo entonces, [cancelWaiting] vuelve a
        // leer lo vigente del repositorio.
        val id = try {
            withContext(NonCancellable) { proposals.savePending(p) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mine != generation || _state.value != ProposalUiState.Loading) return
            return fail(SAVE_FAILED, retry = true, req = p.request, back = back)
        }
        if (mine != generation || _state.value != ProposalUiState.Loading) return
        lastRequest = p.request
        show(p.copy(id = id, status = ProposalStatus.PENDING))
    }

    private fun fail(message: String, retry: Boolean, req: String?, back: ProposalUiState.Showing?) {
        lastRequest = req
        _state.value = if (back != null) back.copy(message = message, adjusting = null, busy = false)
        else ProposalUiState.Error(message, retry)
        beforeWaiting = null
    }

    private fun messageOf(o: PlanOutcome): String = when (o) {
        PlanOutcome.Offline -> DayPlanner.OFFLINE
        PlanOutcome.NoKey -> DayPlanner.NO_KEY
        PlanOutcome.InvalidKey -> DayPlanner.INVALID_KEY
        is PlanOutcome.Refused -> o.message
        is PlanOutcome.Failed -> o.message
        is PlanOutcome.Ok -> ""
    }

    private fun retryable(o: PlanOutcome): Boolean = when (o) {
        PlanOutcome.Offline -> true
        is PlanOutcome.Failed -> o.retryable
        else -> false
    }

    private fun show(p: Proposal, message: String? = null, stale: Boolean = false) {
        beforeWaiting = null
        saved[KEY_SHOWN] = p.id
        _state.value = ProposalUiState.Showing(p, dayTitle(day), stale, message, adjusting = null)
    }

    /** Reintentar tras un error: con el mismo pedido. */
    fun retry() {
        if (_state.value !is ProposalUiState.Error) return
        if (loadFailed) {
            // Falló leer lo guardado: se vuelve a leer, no a pedir.
            _state.value = ProposalUiState.Loading
            job = scope.launch { open() }
            return
        }
        startGenerate(lastRequest, previous = null, adjustment = null, back = null)
    }

    /** `Pedir una nueva`: otra propuesta del mismo día con el mismo pedido (sin ajuste). */
    fun requestNew() {
        val s = _state.value as? ProposalUiState.Showing ?: return
        if (s.busy) return
        startGenerate(s.proposal.request ?: lastRequest, previous = null, adjustment = null, back = s.copy(message = null, adjusting = null))
    }

    /** `Cancelar` mientras Claude arma: deja de esperar y vuelve a lo que había; lo que llegue después se descarta. */
    fun cancelWaiting() {
        if (_state.value != ProposalUiState.Loading) return
        generation++
        job?.cancel()
        val back = beforeWaiting
        beforeWaiting = null
        if (back == null) {
            _state.value = ProposalUiState.Closed
            return
        }
        _state.value = back.copy(message = null, adjusting = null, busy = false)
        // Si la respuesta ya se estaba guardando, lo vigente es otra: se vuelve a leer del repositorio.
        scope.launch { refreshCurrent(fallback = back) }
    }

    private suspend fun refreshCurrent(fallback: ProposalUiState.Showing) {
        val current = try {
            proposals.pendingFor(day)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return
        if (_state.value !is ProposalUiState.Showing) return
        if (current.id != fallback.proposal.id) show(current)
    }

    // ---- aceptar y deshacer ----

    fun accept() {
        val s = _state.value as? ProposalUiState.Showing ?: return
        if (acting || s.stale || s.busy || s.proposal.ops.isEmpty()) return
        acting = true
        _state.value = s.copy(busy = true, message = null)
        scope.launch {
            try {
                when (val r = executor.accept(s.proposal.id)) {
                    is AcceptResult.Accepted -> {
                        acceptedId = s.proposal.id
                        _state.value = confirmation()
                    }
                    // Vencida y desactualizada se explican igual: la agenda ya no es la de la propuesta.
                    is AcceptResult.Stale, is AcceptResult.Expired ->
                        _state.value = s.copy(stale = true, message = ProposalCopy.STALE, busy = false, adjusting = null)
                    AcceptResult.AlreadyAccepted -> alreadyHandled(s)
                    is AcceptResult.Failed -> _state.value = s.copy(busy = false, message = r.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = s.copy(busy = false, message = AcceptResult.FAILED)
            } finally {
                acting = false
            }
        }
    }

    /** No estaba pendiente: se muestra lo que sí está vigente (aceptada por otro camino, o una nueva pendiente). */
    private suspend fun alreadyHandled(s: ProposalUiState.Showing) {
        if (proposals.get(s.proposal.id)?.status == ProposalStatus.ACCEPTED) {
            acceptedId = s.proposal.id
            _state.value = confirmation()
            return
        }
        val pending = proposals.pendingFor(day)
        if (pending != null) show(pending, message = ProposalCopy.ALREADY_DONE) else _state.value = ProposalUiState.Closed
    }

    private suspend fun confirmation(): ProposalUiState.Accepted {
        val today = logicalDay(clock.now())
        val title = when {
            day == today -> "Hoy está listo"
            day == today.plusDays(1) -> "Mañana está listo"
            else -> "${dayTitle(day)} está listo"
        }
        val bed = runCatching { bedtimeRemindAt() }.getOrNull()?.takeIf { it > clock.now() }
        val wake = if (day > today) runCatching { wakeFor(day) }.getOrNull() else null
        val body = buildString {
            append("Lo puse en tu agenda.")
            when {
                wake != null && bed != null ->
                    append(" A las ${formatTime(wake)} te llega el resumen y a las ${formatTime(bed.toLocalTime())} el aviso para ir a dormir.")
                wake != null -> append(" A las ${formatTime(wake)} te llega el resumen.")
                bed != null -> append(" A las ${formatTime(bed.toLocalTime())} te llega el aviso para ir a dormir.")
            }
        }
        return ProposalUiState.Accepted(title, body)
    }

    /** `Volver a la propuesta`: deshace el plan y muestra la propuesta que quedó vigente (D16). */
    fun undo() {
        val s = _state.value as? ProposalUiState.Accepted ?: return
        val id = acceptedId ?: return
        if (acting || s.busy) return
        acting = true
        _state.value = s.copy(busy = true, message = null)
        scope.launch {
            try {
                when (val r = executor.undo(id)) {
                    is UndoResult.Undone -> {
                        acceptedId = null
                        // Deshacer reabre esta propuesta y reemplaza a cualquier pendiente más nueva: se lee lo vigente.
                        val current = proposals.pendingFor(day) ?: proposals.get(id)
                        if (current == null) _state.value = ProposalUiState.Closed
                        else show(current, message = UndoResult.keptMessage(r.kept))
                    }
                    UndoResult.NotAccepted -> _state.value = s.copy(busy = false, message = UndoResult.FAILED)
                    is UndoResult.Failed -> _state.value = s.copy(busy = false, message = r.message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = s.copy(busy = false, message = UndoResult.FAILED)
            } finally {
                acting = false
            }
        }
    }

    fun discard() {
        val s = _state.value as? ProposalUiState.Showing ?: return
        if (acting || s.busy) return
        stopListening()
        acting = true
        scope.launch {
            try {
                withContext(NonCancellable) { proposals.discard(s.proposal.id) }
                _state.value = ProposalUiState.Closed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = s.copy(message = DISCARD_FAILED, busy = false)
            } finally {
                acting = false
            }
        }
    }

    // ---- ajustar (D9) ----

    fun openAdjust() {
        val s = _state.value as? ProposalUiState.Showing ?: return
        if (s.busy || s.stale) return
        _state.value = s.copy(adjusting = AdjustState(speechAvailable = speech.isAvailable), message = null)
    }

    fun closeAdjust() {
        stopListening()
        val s = _state.value as? ProposalUiState.Showing ?: return
        _state.value = s.copy(adjusting = null)
    }

    fun adjustVoice() {
        val s = _state.value as? ProposalUiState.Showing ?: return
        if (s.adjusting == null) return
        if (!speech.isAvailable) {
            _state.value = s.copy(adjusting = s.adjusting.copy(speechAvailable = false, hint = ProposalCopy.NO_VOICE))
            return
        }
        _state.value = s.copy(adjusting = s.adjusting.copy(listening = true, partial = "", hint = null))
        speech.start(::onSpeech)
    }

    fun stopAdjustVoice() { speech.stop() }

    private fun onSpeech(e: SpeechEvent) {
        val s = _state.value as? ProposalUiState.Showing ?: return
        val a = s.adjusting ?: return
        if (!a.listening) return
        when (e) {
            is SpeechEvent.Partial -> _state.value = s.copy(adjusting = a.copy(partial = e.text))
            is SpeechEvent.Final -> adjust(e.text)
            is SpeechEvent.Failure -> {
                val hint = when (e.kind) {
                    SpeechFailure.NO_MATCH -> ProposalCopy.NOT_HEARD
                    SpeechFailure.NETWORK -> ProposalCopy.SPEECH_NETWORK
                    SpeechFailure.OTHER -> ProposalCopy.MIC_ERROR
                }
                _state.value = s.copy(adjusting = a.copy(listening = false, hint = hint))
            }
        }
    }

    /** El micrófono se negó (permiso): solo queda escribir. */
    fun onMicDenied() {
        val s = _state.value as? ProposalUiState.Showing ?: return
        val a = s.adjusting ?: return
        _state.value = s.copy(adjusting = a.copy(listening = false, speechAvailable = false, hint = ProposalCopy.MIC_ERROR))
    }

    /** Una llamada nueva con los cambios de la propuesta actual más el ajuste; la nueva reemplaza a la anterior. */
    fun adjust(text: String) {
        val s = _state.value as? ProposalUiState.Showing ?: return
        val clean = text.trim()
        if (clean.isEmpty() || s.busy || s.stale) return
        stopListening()
        startGenerate(s.proposal.request, previous = s.proposal, adjustment = clean, back = s.copy(adjusting = null, message = null))
    }

    private fun stopListening() {
        speech.cancel()
        val s = _state.value as? ProposalUiState.Showing ?: return
        val a = s.adjusting ?: return
        if (a.listening) _state.value = s.copy(adjusting = a.copy(listening = false, partial = ""))
    }

    override fun onCleared() {
        speech.destroy()
        super.onCleared()
    }

    companion object {
        private const val KEY_SHOWN = "shownProposalId"
        private const val KEY_CONSUMED = "requestConsumed"
        const val LOAD_FAILED = "No pude leer tu agenda. Inténtalo de nuevo."
        const val DISCARD_FAILED = "No pude descartar la propuesta. Inténtalo de nuevo."
        const val SAVE_FAILED = "No pude guardar la propuesta. Inténtalo de nuevo."
        private val ES = Locale.forLanguageTag("es")

        /** `Tu jueves 19`. */
        fun dayTitle(day: LocalDate): String = "Tu ${day.dayOfWeek.getDisplayName(TextStyle.FULL, ES).lowercase(ES)} ${day.dayOfMonth}"
    }
}
