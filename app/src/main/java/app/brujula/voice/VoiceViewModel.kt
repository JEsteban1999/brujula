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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object VoiceCopy {
    const val LISTENING = "Escuchando…"
    const val THINKING = "Claude está entendiendo…"
    const val SCHEDULED = "Agendado"
    const val NOTHING_SCHEDULED = "No agendé nada"
    const val UNDONE = "Deshecho"
    const val UNDO = "Deshacer"
    const val DONE = "Listo"
    const val RETRY = "Reintentar"
    const val STOP_TALKING = "Terminar de hablar"
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
    ) : VoiceState
    data object Undone : VoiceState
    data class NeedsMore(val question: String) : VoiceState
    data class Error(val message: String, val retry: Boolean) : VoiceState
    /** Guardado para interpretarlo cuando vuelva internet. */
    data object SavedOffline : VoiceState
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
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** Contenido del campo "Escribir en vez de hablar"; vive aquí para sobrevivir a la rotación. */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

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

    fun setDraft(text: String) { _draft.value = text }

    fun onMicDenied() { _hint.value = VoiceCopy.MIC_DENIED; _textOnly.value = true }

    /** El permiso ya está concedido (p. ej. al volver de Ajustes). */
    fun onMicGranted() { if (speech.isAvailable && _textOnly.value) { _textOnly.value = false; _hint.value = null } }

    fun start() {
        if (_state.value == VoiceState.Thinking) return
        if (!speech.isAvailable) { _hint.value = VoiceCopy.NO_VOICE; _textOnly.value = true; return }
        _hint.value = null
        lastText = null
        lastPartial = ""
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
            is SpeechEvent.Final -> submitText(e.text)
            is SpeechEvent.Failure -> {
                // Lo ya entendido no se pierde: queda en el campo para enviarlo o editarlo.
                if (lastPartial.isNotBlank()) _draft.value = lastPartial
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
        val full = base?.let { "$it. $clean" } ?: clean
        _draft.value = ""
        _state.value = VoiceState.Thinking
        job = scope.launch { interpret(full, answer = clean) }
    }

    fun retry() {
        val text = lastText
        if (text == null) { start(); return }
        if (_state.value == VoiceState.Thinking) return
        _state.value = VoiceState.Thinking
        job = scope.launch { interpret(text, answer = _draft.value) }
    }

    fun undo() {
        val token = undoToken ?: return
        undoToken = null
        scope.launch {
            try {
                executor.undo(token)
                _state.value = VoiceState.Undone
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                undoToken = token
                val s = _state.value
                if (s is VoiceState.Result) _state.value = s.copy(messages = s.messages + VoiceCopy.UNDO_FAILED)
            }
        }
    }

    private suspend fun interpret(full: String, answer: String) {
        var settled = false
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
                InterpretOutcome.NoKey -> fail(DictationInterpreter.NO_KEY, retry = false, text = null, back = back, full = full)
                InterpretOutcome.InvalidKey -> fail(DictationInterpreter.INVALID_KEY, retry = false, text = null, back = back, full = full)
                InterpretOutcome.Offline -> queue(full)
                is InterpretOutcome.Refused -> fail(outcome.message, retry = false, text = null, back = back, full = full)
                is InterpretOutcome.Failed ->
                    if (outcome.network) queue(full)
                    else fail(outcome.message, outcome.retryable, if (outcome.retryable) full else null, back, full)
            }
            settled = true
        } catch (e: CancellationException) {
            // Salir de la pantalla en pleno "entendiendo": el dictado pasa a la cola y se procesa enseguida.
            if (!settled) {
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
        if (!creatable && question != null) {
            base = full
            _state.value = VoiceState.NeedsMore(question.text)
            return
        }
        val result = try {
            executor.apply(outcome.items)
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
        )
    }

    private suspend fun queue(full: String) {
        try {
            pending.add(full, now())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(VoiceCopy.QUEUE_FAILED, retry = true, text = full, back = full, full = full)
        }
        base = null
        lastText = null
        _state.value = VoiceState.SavedOffline
    }

    private fun fail(message: String, retry: Boolean, text: String?, back: String, full: String) {
        lastText = text
        if (retry) {
            _draft.value = back
        } else {
            // Error definitivo: la pregunta pendiente ya no aplica; el campo conserva el dictado completo.
            base = null
            _draft.value = full
        }
        _state.value = VoiceState.Error(message, retry)
    }

    override fun onCleared() {
        job?.cancel()
        speech.destroy()
    }
}
