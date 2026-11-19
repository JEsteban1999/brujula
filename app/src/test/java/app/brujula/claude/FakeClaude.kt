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

class FakeClaudeGateway(var result: KeyTestResult = KeyTestResult.OK) : ClaudeGateway {
    var calls = 0
    override suspend fun testKey(): KeyTestResult { calls++; return result }
}
