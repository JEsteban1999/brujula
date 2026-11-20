package app.brujula.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.brujula.claude.ActionApplier
import app.brujula.claude.DictationInterpreter
import app.brujula.claude.InterpretOutcome
import app.brujula.claude.PendingDictations
import app.brujula.claude.TextInterpreter
import app.brujula.claude.UndoToken
import app.brujula.claude.Validated
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

object VoiceCopy {
    const val LISTENING = "Escuchando…"
    const val THINKING = "Claude está entendiendo…"
    const val SCHEDULED = "Agendado"
    const val NOTHING_SCHEDULED = "No agendé nada"
    /** Título del Resultado cuando no se agendó nada pero sigue una Propuesta. */
    const val PLAN_OFFERED = "Claude puede reorganizar tu día"
    const val UNDONE = "Deshecho"
    const val UNDO = "Deshacer"
    const val DONE = "Listo"
    const val VIEW_PROPOSAL = "Ver propuesta"
    const val RETRY = "Reintentar"
    const val STOP_TALKING = "Terminar de hablar"
    const val PREFER_WRITING = "Prefiero escribir"
    const val WRITE_INSTEAD = "Escribir en vez de hablar"
    const val OFFLINE = DictationInterpreter.OFFLINE
    const val NOT_HEARD = "No te escuché. Toca para intentar de nuevo."
    const val NETWORK = "El reconocimiento de voz necesita internet. Puedes escribirlo."
    const val UNDO_FAILED = "No pude deshacer. Inténtalo de nuevo."
    const val QUEUE_FAILED = "No pude guardar tu dictado. Inténtalo de nuevo."
    const val MIC_ERROR = "No pude usar el micrófono. Toca para intentar de nuevo o escríbelo."
    const val NO_VOICE = "El dictado por voz no está disponible en este teléfono. Escribe lo que quieres agendar."
    const val ENABLE_MIC = "Activar micrófono"
    const val MIC_DENIED = "Sin permiso de micrófono. Escribe lo que quieres agendar."
    const val SAVE_FAILED = "No pude guardar lo que pediste. No se agendó nada."
}

sealed interface VoiceState {
    /** Nada en curso: se puede tocar el micrófono o escribir. */
    data object Idle : VoiceState
    data class Listening(val partial: String) : VoiceState
    data object Thinking : VoiceState
    data class Result(
        val created: List<String>,
        val reply: String,
        /** Pendientes que el validador rechazó, preguntas y avisos: se muestran junto a la respuesta de Claude. */
        val messages: List<String>,
        val undoAvailable: Boolean,
        /** Además de lo agendado, el dictado pidió cambiar o armar un día: lleva a la Propuesta (no se aplicó nada de eso). */
        val proposal: Validated.PlanRequest? = null,
    ) : VoiceState {
        val title: String get() = when {
            created.isNotEmpty() -> VoiceCopy.SCHEDULED
            proposal != null -> VoiceCopy.PLAN_OFFERED
            else -> VoiceCopy.NOTHING_SCHEDULED
        }
    }
    /** El dictado solo pidió un cambio o armar un día: no se aplica nada, la pantalla pasa a la Propuesta de [day]. */
    data class OpenProposal(val day: LocalDate, val request: String) : VoiceState
    data object Undone : VoiceState
    data class NeedsMore(val question: String) : VoiceState
    data class Error(val message: String, val retry: Boolean) : VoiceState
    /** Guardado para interpretarlo cuando vuelva internet. */
    data object SavedOffline : VoiceState
    /** Guardado porque falta la clave de Claude (o no vale); solo con `queueWithoutKey`. Se agenda al arreglar la clave. */
    data object SavedNeedsKey : VoiceState
}

class VoiceViewModel(
    private val speech: SpeechInput,
    private val interpreter: TextInterpreter,
    private val executor: ActionApplier,
    private val pending: PendingDictations,
    private val now: () -> Long = System::currentTimeMillis,
    private val scopeOverride: CoroutineScope? = null,
    /** Pide procesar la cola de pendientes (producción: `drainPendingDictations` en `syncScope`). */
    private val requestDrain: () -> Unit = {},
    /** La ventanita del widget no puede mandar a Tu rutina: sin clave (o clave inválida) el dictado va a la cola. */
    private val queueWithoutKey: Boolean = false,
    /**
     * Ventanita del widget: no hay campo de texto, así que el borrador nunca bloquea un dictado y nada se pierde: los fallos
     * recuperables y salir de un error o una pregunta pasan el texto a la cola.
     */
    private val floating: Boolean = false,
    /** Alcance que sobrevive a la pantalla para encolar al salir (producción: `syncScope`); `viewModelScope` ya está cancelado en `onCleared`. */
    private val leaveScope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** Contenido del campo "Escribir en vez de hablar"; vive aquí para sobrevivir a la rotación. */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _undoing = MutableStateFlow(false)
    /** Un "Deshacer" está en curso (la ventanita no debe cerrarse todavía). */
    val undoing: StateFlow<Boolean> = _undoing.asStateFlow()

    private val _hint = MutableStateFlow<String?>(null)
    val hint: StateFlow<String?> = _hint.asStateFlow()

    /** Sin micrófono utilizable (permiso negado o sin servicio): solo campo de texto. */
    private val _textOnly = MutableStateFlow(!speech.isAvailable)
    val textOnly: StateFlow<Boolean> = _textOnly.asStateFlow()

    val voiceAvailable: Boolean get() = speech.isAvailable

    private var job: Job? = null
    private var undoToken: UndoToken? = null
    /** Dictado original mientras Claude espera una respuesta ([VoiceState.NeedsMore]). */
    private var base: String? = null
    /** Texto a reintentar tras un fallo recuperable; nulo si el fallo fue del micrófono. */
    private var lastText: String? = null
    private var lastPartial = ""
    /** El dictado ya esta en manos de apply o de la cola: una cancelacion posterior no debe encolarlo otra vez. */
    private var handedOff = false
    /** Solo en modo ventanita: dictado completo que se encola si se sale de un error recuperable o de una pregunta. */
    private var unsent: String? = null
    /** Solo en modo ventanita: dictado de un error definitivo; pasa a la cola solo si el usuario abre la app desde ahí. */
    private var carry: String? = null
    /** Fuera del ciclo de la pantalla: sobrevive a `onCleared` (que cancela `viewModelScope`). */
    private val detached: CoroutineScope get() = leaveScope ?: detachedScope
    private val detachedScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    /** Un envío está en vuelo: de él se encarga la captura de cancelación de `interpret`, no `onCleared`. */
    private var inFlight = false

    /**
     * Lo que escribe el usuario manda: si escribe mientras se escucha, se cancela el reconocedor y se vuelve a reposo
     * (el habla no puede pisar el texto). Los eventos tardíos del reconocedor se ignoran porque ya no se está escuchando.
     */
    fun setDraft(text: String) {
        if (text.isNotBlank() && _state.value is VoiceState.Listening) {
            speech.cancel()
            lastPartial = ""
            _state.value = VoiceState.Idle
        }
        _draft.value = text
    }

    fun onMicDenied() { _hint.value = VoiceCopy.MIC_DENIED; _textOnly.value = true }

    /** El permiso ya está concedido (p. ej. al volver de Ajustes). */
    fun onMicGranted() { if (speech.isAvailable && _textOnly.value) { _textOnly.value = false; _hint.value = null } }

    fun start() {
        if (_state.value == VoiceState.Thinking) return
        if (!speech.isAvailable) { _hint.value = VoiceCopy.NO_VOICE; _textOnly.value = true; return }
        _hint.value = null
        lastText = null
        lastPartial = ""
        if (floating) _draft.value = ""
        // Un dictado nuevo olvida la pregunta pendiente; responder a una pregunta (NeedsMore) la conserva.
        if (_state.value !is VoiceState.NeedsMore) base = null
        _state.value = VoiceState.Listening("")
        speech.start(::onSpeech)
    }

    fun stop() { speech.stop() }

    private fun onSpeech(e: SpeechEvent) {
        if (_state.value !is VoiceState.Listening) return
        when (e) {
            is SpeechEvent.Partial -> { lastPartial = e.text; _state.value = VoiceState.Listening(e.text) }
            is SpeechEvent.Final -> {
                val typed = if (floating) "" else _draft.value
                if (typed.isBlank()) {
                    submitText(e.text)
                } else {
                    // Había texto escrito: no se envía solo. Lo dicho se agrega al final del texto escrito y el usuario decide.
                    speech.cancel()
                    _draft.value = typed.trimEnd() + " " + e.text.trim()
                    _state.value = VoiceState.Idle
                }
            }
            is SpeechEvent.Failure -> {
                // Lo ya entendido no se pierde: queda en el campo para enviarlo o editarlo, pero nunca pisa lo escrito.
                if (!floating && lastPartial.isNotBlank() && _draft.value.isBlank()) _draft.value = lastPartial
                _state.value = when (e.kind) {
                    SpeechFailure.NO_MATCH -> VoiceState.Error(VoiceCopy.NOT_HEARD, retry = true)
                    SpeechFailure.NETWORK -> VoiceState.Error(VoiceCopy.NETWORK, retry = false)
                    SpeechFailure.OTHER -> VoiceState.Error(VoiceCopy.MIC_ERROR, retry = true)
                }
            }
        }
    }

    fun submitText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            if (_state.value is VoiceState.Listening) _state.value = VoiceState.Error(VoiceCopy.NOT_HEARD, retry = true)
            return
        }
        if (_state.value == VoiceState.Thinking) return
        speech.cancel()
        lastPartial = ""
        inFlight = true
        val full = base?.let { "$it. $clean" } ?: clean
        _draft.value = ""
        _state.value = VoiceState.Thinking
        job = scope.launch { interpret(full, answer = clean) }
    }

    fun retry() {
        val text = lastText
        if (text == null) { start(); return }
        if (_state.value == VoiceState.Thinking) return
        if (floating) _draft.value = ""
        inFlight = true
        _state.value = VoiceState.Thinking
        job = scope.launch { interpret(text, answer = _draft.value) }
    }

    fun undo() {
        val token = undoToken ?: return
        undoToken = null
        _undoing.value = true
        scope.launch {
            try {
                executor.undo(token)
                _state.value = VoiceState.Undone
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                undoToken = token
                val s = _state.value
                if (s is VoiceState.Result && VoiceCopy.UNDO_FAILED !in s.messages) {
                    _state.value = s.copy(messages = s.messages + VoiceCopy.UNDO_FAILED)
                }
            } finally {
                _undoing.value = false
            }
        }
    }

    private suspend fun interpret(full: String, answer: String) {
        var settled = false
        handedOff = false
        unsent = null
        carry = null
        try {
            val outcome = try {
                interpreter.interpret(full)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                InterpretOutcome.Failed(DictationInterpreter.UNAVAILABLE, retryable = true)
            }
            // Texto nunca perdido: en un fallo con reintento vuelve al campo (solo la respuesta si hay una pregunta pendiente).
            val back = if (base != null) answer else full
            when (outcome) {
                is InterpretOutcome.Ok -> applyOk(outcome, full, back)
                InterpretOutcome.NoKey, InterpretOutcome.InvalidKey -> if (queueWithoutKey) queueNeedsKey(full) else fail(
                    if (outcome == InterpretOutcome.NoKey) DictationInterpreter.NO_KEY else DictationInterpreter.INVALID_KEY,
                    retry = false, text = null, back = back, full = full,
                )
                InterpretOutcome.Offline -> queue(full)
                is InterpretOutcome.Refused -> fail(outcome.message, retry = false, text = null, back = back, full = full)
                is InterpretOutcome.Failed ->
                    if (outcome.network || (floating && outcome.retryable)) queue(full)
                    else fail(outcome.message, outcome.retryable, if (outcome.retryable) full else null, back, full)
            }
            settled = true
            inFlight = false
        } catch (e: CancellationException) {
            // Salir de la pantalla en pleno "entendiendo": el dictado pasa a la cola y se procesa enseguida.
            if (!settled && !handedOff) {
                withContext(NonCancellable) {
                    runCatching { pending.add(full, now()) }.onSuccess { requestDrain() }
                }
            }
            throw e
        }
    }

    private suspend fun applyOk(outcome: InterpretOutcome.Ok, full: String, back: String) {
        val creatable = outcome.items.any { it is Validated.Activity || it is Validated.Habit }
        val question = outcome.items.filterIsInstance<Validated.Question>().firstOrNull()
        val plan = outcome.items.filterIsInstance<Validated.PlanRequest>().firstOrNull()
        val notes = outcome.items.mapNotNull {
            when (it) {
                is Validated.Question -> it.text
                is Validated.Unsupported -> it.text
                is Validated.Invalid -> it.reason
                else -> null
            }
        }
        if (!creatable && plan != null && notes.isNotEmpty()) {
            // Hay avisos que el usuario debe leer: un Resultado corto antes de pasar a la Propuesta.
            base = null
            lastText = null
            undoToken = null
            _state.value = VoiceState.Result(
                created = emptyList(), reply = outcome.reply, messages = notes, undoAvailable = false,
                proposal = plan.copy(text = plan.text.ifBlank { full }),
            )
            return
        }
        if (!creatable && plan != null) {
            // Nada se aplica: la propuesta la genera y la acepta el usuario en su pantalla.
            base = null
            lastText = null
            undoToken = null
            _state.value = VoiceState.OpenProposal(plan.day, plan.text.ifBlank { full })
            return
        }
        if (!creatable && question != null) {
            base = full
            if (floating) unsent = full
            _state.value = VoiceState.NeedsMore(question.text)
            return
        }
        // Desde aqui el dictado es de apply (termina su transaccion, resync y copia a Google): no se encola de nuevo.
        handedOff = true
        val result = try {
            withContext(NonCancellable) { executor.apply(outcome.items, outcome.reply) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(VoiceCopy.SAVE_FAILED, retry = true, text = full, back = back, full = full)
        }
        if (creatable && result.created.isEmpty()) {
            return fail(result.messages.lastOrNull() ?: VoiceCopy.SAVE_FAILED, retry = true, text = full, back = back, full = full)
        }
        base = null
        lastText = null
        undoToken = result.undo
        _state.value = VoiceState.Result(
            created = result.created.map { it.label },
            reply = outcome.reply,
            messages = result.messages,
            undoAvailable = result.undo != null,
            proposal = plan?.let { it.copy(text = it.text.ifBlank { full }) },
        )
    }

    private suspend fun queue(full: String) {
        handedOff = true
        try {
            withContext(NonCancellable) { pending.add(full, now()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(VoiceCopy.QUEUE_FAILED, retry = true, text = full, back = full, full = full)
        }
        base = null
        lastText = null
        _state.value = VoiceState.SavedOffline
    }

    private suspend fun queueNeedsKey(full: String) {
        handedOff = true
        try {
            withContext(NonCancellable) { pending.add(full, now()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(VoiceCopy.QUEUE_FAILED, retry = true, text = full, back = full, full = full)
        }
        base = null
        lastText = null
        _state.value = VoiceState.SavedNeedsKey
    }

    private fun fail(message: String, retry: Boolean, text: String?, back: String, full: String) {
        lastText = text
        if (floating) {
            // Sin campo de texto: el dictado queda aparte, nunca en el borrador.
            if (retry) unsent = full else carry = full
            base = null
        } else if (retry) {
            _draft.value = back
        } else {
            // Error definitivo: la pregunta pendiente ya no aplica; el campo conserva el dictado completo.
            base = null
            _draft.value = full
        }
        _state.value = VoiceState.Error(message, retry)
    }

    /** Encola (una sola vez) el dictado del error definitivo o pendiente; la ventanita lo llama antes de abrir la app. */
    fun queueForApp() = queueDetached(takeLeftover())

    /** El dictado que quedó sin procesar, calculado una sola vez: quien llegue primero se lo lleva y borra todo. */
    private fun takeLeftover(): String? {
        val answering = base?.takeIf { lastPartial.isNotBlank() }?.let { "$it. $lastPartial" }
        val text = answering ?: unsent ?: carry
        base = null
        lastPartial = ""
        unsent = null
        carry = null
        return text
    }

    private fun queueDetached(text: String?) {
        if (text.isNullOrBlank()) return
        detached.launch {
            withContext(NonCancellable) { runCatching { pending.add(text, now()) }.onSuccess { requestDrain() } }
        }
    }

    override fun onCleared() {
        job?.cancel()
        speech.destroy()
        // Con un envío en vuelo o ya entregado a apply/cola, manda la captura de cancelación de `interpret`.
        if (floating && !inFlight && !handedOff) queueDetached(takeLeftover())
    }
}
