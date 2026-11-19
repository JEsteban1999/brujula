package app.brujula.claude

enum class KeyTestResult { OK, INVALID, NETWORK, ERROR }

/** Resultado de una llamada a Claude, ya clasificado por tipo de fallo (nunca por el texto del error). */
sealed interface GatewayResult<out T> {
    data class Ok<T>(val value: T) : GatewayResult<T>
    /** stopReason == refusal (también después del modelo de respaldo). */
    data object Refused : GatewayResult<Nothing>
    data object NoKey : GatewayResult<Nothing>
    /** 401/403: la clave guardada no sirve. */
    data object InvalidKey : GatewayResult<Nothing>
    /** Sin conexión, DNS o tiempo agotado. */
    data object Network : GatewayResult<Nothing>
    /** 429/5xx son reintentables; 400, respuesta ilegible o truncada no. */
    data class Error(val retryable: Boolean) : GatewayResult<Nothing>
}

interface ClaudeGateway {
    /** Peticion minima para comprobar la clave guardada. Nunca lanza. */
    suspend fun testKey(): KeyTestResult

    /** Interpreta un dictado con salida estructurada. Nunca lanza (salvo cancelación). */
    suspend fun interpret(system: String, user: String): GatewayResult<DictationResult>
}
