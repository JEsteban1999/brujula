package app.brujula.claude

import java.util.Base64

/** Cifrado falso reversible (Robolectric no tiene AndroidKeyStore). */
class FakeSecretCipher : SecretCipher {
    override fun encrypt(plain: String): String =
        "fake:" + Base64.getEncoder().encodeToString(plain.reversed().toByteArray())

    override fun decrypt(cipherText: String): String? {
        if (!cipherText.startsWith("fake:")) return null
        return runCatching { String(Base64.getDecoder().decode(cipherText.removePrefix("fake:"))).reversed() }.getOrNull()
    }
}

class FakeClaudeGateway(
    var result: KeyTestResult = KeyTestResult.OK,
    var dictation: GatewayResult<DictationResult> = GatewayResult.Error(retryable = false),
    var classification: GatewayResult<ClassificationResult> = GatewayResult.Error(retryable = false),
) : ClaudeGateway {
    var classifyCalls = 0
    val classifyUsers = mutableListOf<String>()
    var classifySystem: String? = null
    /** Si se pone, calcula la respuesta a partir del texto enviado (en lugar de [classification]). */
    var classifyWith: ((String) -> GatewayResult<ClassificationResult>)? = null
    override suspend fun classify(system: String, user: String): GatewayResult<ClassificationResult> {
        classifyCalls++
        classifySystem = system
        classifyUsers += user
        return classifyWith?.invoke(user) ?: classification
    }
    var calls = 0
    var interpretCalls = 0
    var lastSystem: String? = null
    var lastUser: String? = null
    override suspend fun testKey(): KeyTestResult { calls++; return result }
    override suspend fun interpret(system: String, user: String): GatewayResult<DictationResult> {
        interpretCalls++
        lastSystem = system
        lastUser = user
        return dictation
    }
}
