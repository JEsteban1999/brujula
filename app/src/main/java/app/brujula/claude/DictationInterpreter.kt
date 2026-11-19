package app.brujula.claude

import kotlinx.coroutines.CancellationException

sealed interface InterpretOutcome {
    data class Ok(val items: List<Validated>, val reply: String) : InterpretOutcome
    data object NoKey : InterpretOutcome
    /** Hay clave guardada pero el API la rechaza (401/403). */
    data object InvalidKey : InterpretOutcome
    data object Offline : InterpretOutcome
    data class Refused(val message: String) : InterpretOutcome
    data class Failed(val message: String, val retryable: Boolean) : InterpretOutcome
}

/** Costura para la pantalla de dictado y la cola sin conexión; las pruebas la sustituyen. */
fun interface TextInterpreter {
    suspend fun interpret(text: String): InterpretOutcome
}

/**
 * Convierte un dictado en acciones validadas: arma el contexto, hace una sola llamada a Claude y valida cada acción.
 * No aplica nada. [isOnline] permite responder [InterpretOutcome.Offline] sin intentar la llamada.
 */
class DictationInterpreter(
    private val gateway: ClaudeGateway,
    private val contextProvider: suspend () -> DictationContext,
    private val isOnline: suspend () -> Boolean = { true },
) : TextInterpreter {
    override suspend fun interpret(text: String): InterpretOutcome {
        val clean = text.trim()
        if (clean.isEmpty()) return InterpretOutcome.Failed(NOTHING_HEARD, retryable = false)
        if (!isOnline()) return InterpretOutcome.Offline
        val ctx = try {
            contextProvider()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return InterpretOutcome.Failed(UNAVAILABLE, retryable = true)
        }
        return when (val r = gateway.interpret(PromptBuilder.system(), PromptBuilder.user(ctx, clean))) {
            is GatewayResult.Ok -> InterpretOutcome.Ok(r.value.actions.map { ActionValidator.validate(it, ctx) }, r.value.reply.trim())
            GatewayResult.Refused -> InterpretOutcome.Refused(REFUSED)
            GatewayResult.NoKey -> InterpretOutcome.NoKey
            GatewayResult.InvalidKey -> InterpretOutcome.InvalidKey
            GatewayResult.Network -> InterpretOutcome.Failed(OFFLINE, retryable = true)
            GatewayResult.Timeout -> InterpretOutcome.Failed(TOO_SLOW, retryable = true)
            is GatewayResult.Error ->
                if (r.retryable) InterpretOutcome.Failed(UNAVAILABLE, retryable = true) else InterpretOutcome.Failed(REFUSED, retryable = false)
        }
    }

    companion object {
        const val REFUSED = "No pude interpretar eso. Prueba diciéndolo de otra forma."
        const val OFFLINE = "Sin conexión. Lo interpreto en cuanto vuelva internet."
        const val UNAVAILABLE = "Claude no está disponible ahora. Inténtalo de nuevo en un momento."
        const val NOTHING_HEARD = "No te escuché. Inténtalo de nuevo."
        const val TOO_SLOW = "Claude tardó demasiado. Inténtalo de nuevo."
        /** Copia para la UI (Task 4) de [InterpretOutcome.NoKey] e [InterpretOutcome.InvalidKey]. */
        const val NO_KEY = "Para dictar, agrega tu clave de Claude en Tu rutina."
        const val INVALID_KEY = "Tu clave de Claude no funciona. Revísala en Tu rutina."
    }
}
