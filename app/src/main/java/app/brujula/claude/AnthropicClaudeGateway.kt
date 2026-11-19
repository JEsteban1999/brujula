package app.brujula.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gateway con el SDK oficial; el cliente se crea al usarlo y se reutiliza mientras la clave no cambie. */
class AnthropicClaudeGateway(private val keyStore: ClaudeKeyStore) : ClaudeGateway {
    private var cached: Pair<String, AnthropicClient>? = null

    @Synchronized
    private fun clientFor(key: String): AnthropicClient {
        cached?.takeIf { it.first == key }?.let { return it.second }
        val client = AnthropicOkHttpClient.builder().apiKey(key).build()
        cached = key to client
        return client
    }

    override suspend fun testKey(): KeyTestResult = withContext(Dispatchers.IO) {
        val key = keyStore.read() ?: return@withContext KeyTestResult.INVALID
        try {
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
