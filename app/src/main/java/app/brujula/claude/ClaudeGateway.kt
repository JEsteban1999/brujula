package app.brujula.claude

enum class KeyTestResult { OK, INVALID, NETWORK, ERROR }

interface ClaudeGateway {
    /** Peticion minima para comprobar la clave guardada. Nunca lanza. */
    suspend fun testKey(): KeyTestResult
}
