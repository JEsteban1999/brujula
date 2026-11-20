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
    /** Sin conexión o sin DNS. */
    data object Network : GatewayResult<Nothing>
    /** Claude no respondió a tiempo (la conexión sí funcionaba). */
    data object Timeout : GatewayResult<Nothing>
    /**
     * 429/5xx y respuesta truncada son reintentables; respuesta ilegible no. [config]: HTTP 400/404 (petición o modelo mal
     * configurados): no es culpa de lo que dijo el usuario ni se arregla repitiendo.
     */
    data class Error(val retryable: Boolean, val config: Boolean = false) : GatewayResult<Nothing>
}

interface ClaudeGateway {
    /** Peticion minima para comprobar la clave guardada. Nunca lanza. */
    suspend fun testKey(): KeyTestResult

    /** Interpreta un dictado con salida estructurada. Nunca lanza (salvo cancelación). */
    suspend fun interpret(system: String, user: String): GatewayResult<DictationResult>

    /** Clasifica títulos de eventos como trabajo o no (Haiku, salida estructurada). Nunca lanza (salvo cancelación). */
    suspend fun classify(system: String, user: String): GatewayResult<ClassificationResult>

    /** Propone cambios para un día (Opus 5.5, salida estructurada). Nunca lanza (salvo cancelación). */
    suspend fun plan(system: String, user: String): GatewayResult<PlanResult>

    /** Sugiere un cambio de hábito a partir de conteos agregados (Haiku 5.5, salida estructurada). Nunca lanza (salvo cancelación). */
    suspend fun suggest(system: String, user: String): GatewayResult<SuggestionResult>
}
