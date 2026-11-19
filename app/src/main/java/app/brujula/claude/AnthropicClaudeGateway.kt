package app.brujula.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Duration

/** Gateway con el SDK oficial; el cliente se crea al usarlo y se reutiliza mientras la clave no cambie. */
class AnthropicClaudeGateway(private val keyStore: ClaudeKeyStore) : ClaudeGateway {
    // Solo se guarda el hash de la clave, nunca la clave en claro.
    private var cached: Pair<ByteArray, AnthropicClient>? = null

    @Synchronized
    private fun clientFor(key: String): AnthropicClient {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        cached?.takeIf { it.first.contentEquals(digest) }?.let { return it.second }
        cached?.second?.close()
        val client = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .timeout(Duration.ofSeconds(30))
            .maxRetries(1)
            .build()
        cached = digest to client
        return client
    }

    @Synchronized
    private fun dropClient() {
        cached?.second?.close()
        cached = null
    }

    override suspend fun testKey(): KeyTestResult = withContext(Dispatchers.IO) {
        try {
            val key = keyStore.read()
            if (key == null) {
                dropClient()
                return@withContext KeyTestResult.INVALID
            }
            clientFor(key).messages().create(
                MessageCreateParams.builder()
                    .model(ClaudeModels.DICTATION)
                    .maxTokens(16L)
                    .addUserMessage("ok")
                    .build(),
            )
            KeyTestResult.OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            KeyTestResult.INVALID
        } catch (e: AnthropicIoException) {
            KeyTestResult.NETWORK
        } catch (e: Exception) {
            KeyTestResult.ERROR
        }
    }
}
