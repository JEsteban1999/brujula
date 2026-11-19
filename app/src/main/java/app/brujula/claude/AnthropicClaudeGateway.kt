package app.brujula.claude

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.RequestOptions
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.RequestTimeoutException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.StructuredMessage
import com.anthropic.models.beta.messages.StructuredMessageCreateParams
import com.anthropic.models.beta.messages.StructuredOutputConfig
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.StructuredMessage as NonBetaStructuredMessage
import com.anthropic.models.messages.StructuredMessageCreateParams as NonBetaStructuredParams
import com.anthropic.models.messages.StructuredOutputConfig as NonBetaStructuredOutputConfig
import com.anthropic.models.beta.messages.MessageCreateParams as BetaMessageCreateParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException
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

    override suspend fun interpret(system: String, user: String): GatewayResult<DictationResult> = withContext(Dispatchers.IO) {
        try {
            val key = keyStore.read()
            if (key == null) {
                dropClient()
                return@withContext GatewayResult.NoKey
            }
            // Tiempo propio del dictado (el cliente queda en 30 s para testKey).
            DictationCall.read(clientFor(key).beta().messages().create(DictationCall.params(system, user), DictationCall.requestOptions()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DictationCall.failureOf(e)
        }
    }

    override suspend fun classify(system: String, user: String): GatewayResult<ClassificationResult> = withContext(Dispatchers.IO) {
        try {
            val key = keyStore.read()
            if (key == null) {
                dropClient()
                return@withContext GatewayResult.NoKey
            }
            ClassificationCall.read(clientFor(key).messages().create(ClassificationCall.params(system, user)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DictationCall.failureOf(e, "Clasificación")
        }
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

/**
 * Petición de dictado y lectura de su respuesta; separado para probarlo sin red.
 *
 * Va por el endpoint beta porque el SDK 3.9.0 expone ahí `fallbacks` ("default", beta server-side-fallback-2026-07-01):
 * si Claude Opus 5.5 rechaza, el servidor reintenta con el modelo de respaldo dentro de la misma llamada. Si toda la cadena
 * rechaza, llega stopReason == REFUSAL y se trata como [GatewayResult.Refused].
 */
internal object DictationCall {
    private const val MAX_TOKENS = 4096L

    /** Salida estructurada derivada de [DictationResult]; esfuerzo bajo, sin `thinking`, sin temperatura ni prefill. */
    fun params(system: String, user: String): StructuredMessageCreateParams<DictationResult> =
        BetaMessageCreateParams.builder()
            .model(ClaudeModels.DICTATION)
            .maxTokens(MAX_TOKENS)
            .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
            .fallbacksDefault()
            .systemOfBetaTextBlockParams(
                listOf(
                    // El system es fijo: se marca cacheable (si no alcanza el mínimo de tokens, el API simplemente no cachea).
                    BetaTextBlockParam.builder().text(system).cacheControl(BetaCacheControlEphemeral.builder().build()).build(),
                ),
            )
            .addUserMessage(user)
            .outputConfig(
                StructuredOutputConfig.builder<DictationResult>()
                    .effort(BetaOutputConfig.Effort.LOW)
                    .format(DictationResult::class.java)
                    .build(),
            )
            .build()

    /** Opus 5.5 siempre piensa y un respaldo puede correr dos modelos en la misma llamada: más que los 30 s del cliente. */
    val TIMEOUT: Duration = Duration.ofSeconds(60)

    fun requestOptions(): RequestOptions = RequestOptions.builder().timeout(TIMEOUT).build()

    /** Aviso en logcat: solo la clase de la excepción y el código HTTP; nunca mensaje, cuerpo, cabeceras ni clave. */
    @Volatile var warn: (String) -> Unit = { Log.w("ClaudeGateway", it) }

    /** Revisa stopReason antes de leer el contenido. */
    fun read(response: StructuredMessage<DictationResult>): GatewayResult<DictationResult> {
        val stop = response.stopReason().orElse(null)
        if (stop == BetaStopReason.REFUSAL) return GatewayResult.Refused
        // Truncada por max_tokens: puede salir bien al repetir.
        if (stop == BetaStopReason.MAX_TOKENS) return GatewayResult.Error(retryable = true)
        if (stop != BetaStopReason.END_TURN) return GatewayResult.Error(retryable = false)
        return try {
            // Tras un respaldo puede haber texto parcial del primer modelo antes del bloque fallback: vale el último texto.
            val block = response.content().lastOrNull { it.isText() } ?: return GatewayResult.Error(retryable = false)
            GatewayResult.Ok(block.asText().text())
        } catch (e: RuntimeException) {
            // JSON que no encaja en DictationResult (AnthropicInvalidDataException) u otra respuesta ilegible.
            warn("Respuesta de dictado ilegible: ${e.javaClass.simpleName}")
            GatewayResult.Error(retryable = false)
        }
    }

    /** Clasifica por tipo de excepción, nunca por el texto del mensaje. */
    fun failureOf(e: Exception, what: String = "Dictado"): GatewayResult<Nothing> {
        val status = (e as? AnthropicServiceException)?.statusCode()
        warn("$what falló: ${e.javaClass.simpleName}" + (status?.let { " (HTTP $it)" } ?: ""))
        return when (e) {
            is UnauthorizedException, is PermissionDeniedException -> GatewayResult.InvalidKey
            // OkHttp señala el tiempo agotado con InterruptedIOException (SocketTimeoutException es una subclase).
            is AnthropicIoException -> if (e.causes().any { it is InterruptedIOException }) GatewayResult.Timeout else GatewayResult.Network
            is RateLimitException, is InternalServerException, is RequestTimeoutException, is AnthropicRetryableException ->
                GatewayResult.Error(retryable = true)
            is AnthropicServiceException -> when (val code = e.statusCode()) {
                400, 404 -> GatewayResult.Error(retryable = false, config = true)
                else -> GatewayResult.Error(retryable = code >= 500)
            }
            else -> GatewayResult.Error(retryable = false)
        }
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(cause) { it.cause?.takeIf { c -> c !== it } }
}

/**
 * Petición de clasificación (Haiku 5.5) y lectura de su respuesta. Va por el endpoint NO beta: sin respaldo en el servidor,
 * un rechazo (stopReason == REFUSAL) significa simplemente "sin clasificar". Esfuerzo bajo, sin `thinking`, sin temperatura ni prefill.
 */
internal object ClassificationCall {
    private const val MAX_TOKENS = 4096L

    fun params(system: String, user: String): NonBetaStructuredParams<ClassificationResult> =
        MessageCreateParams.builder()
            .model(ClaudeModels.CLASSIFICATION)
            .maxTokens(MAX_TOKENS)
            .system(system)
            .addUserMessage(user)
            .outputConfig(
                NonBetaStructuredOutputConfig.builder<ClassificationResult>()
                    .effort(OutputConfig.Effort.LOW)
                    .format(ClassificationResult::class.java)
                    .build(),
            )
            .build()

    /** Revisa stopReason antes de leer el contenido. */
    fun read(response: NonBetaStructuredMessage<ClassificationResult>): GatewayResult<ClassificationResult> {
        val stop = response.stopReason().orElse(null)
        if (stop == StopReason.REFUSAL) return GatewayResult.Refused
        if (stop == StopReason.MAX_TOKENS) return GatewayResult.Error(retryable = true)
        if (stop != StopReason.END_TURN) return GatewayResult.Error(retryable = false)
        return try {
            val block = response.content().lastOrNull { it.isText() } ?: return GatewayResult.Error(retryable = false)
            GatewayResult.Ok(block.asText().text())
        } catch (e: RuntimeException) {
            DictationCall.warn("Respuesta de clasificación ilegible: ${e.javaClass.simpleName}")
            GatewayResult.Error(retryable = false)
        }
    }
}
