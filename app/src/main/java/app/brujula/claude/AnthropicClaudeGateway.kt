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
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.beta.messages.MessageCreateParams as BetaMessageCreateParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.time.Duration

/**
 * Gateway con el SDK oficial; el cliente se crea al usarlo y se reutiliza mientras la clave no cambie.
 *
 * Los constructores de parámetros se inyectan solo para las pruebas (p. ej. simular un NoSuchMethodError del SDK en ART).
 */
class AnthropicClaudeGateway internal constructor(
    private val readKey: suspend () -> String?,
    private val dictationParams: (String, String) -> BetaMessageCreateParams = DictationCall::params,
    private val classificationParams: (String, String) -> MessageCreateParams = ClassificationCall::params,
    private val planParams: (String, String) -> BetaMessageCreateParams = PlanCall::params,
    private val keyTestParams: () -> MessageCreateParams = ::keyTestParams,
) : ClaudeGateway {
    constructor(keyStore: ClaudeKeyStore) : this(keyStore::read)

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

    // LinkageError (NoSuchMethodError, NoClassDefFoundError, ExceptionInInitializerError, ...) = el SDK o una dependencia no
    // encaja con el runtime de Android. Se captura para no tumbar la app; OutOfMemoryError y demás VirtualMachineError no.

    override suspend fun interpret(system: String, user: String): GatewayResult<DictationResult> =
        callBeta("Dictado", { dictationParams(system, user) }, DictationCall::requestOptions, DictationCall::read)

    override suspend fun plan(system: String, user: String): GatewayResult<PlanResult> =
        callBeta("Plan", { planParams(system, user) }, PlanCall::requestOptions, PlanCall::read)

    /**
     * Llamada al endpoint beta con su propio tiempo por petición (el cliente queda en 30 s para testKey). Sin clave no arma
     * la petición; los fallos se clasifican por tipo con la etiqueta [what] en el log.
     */
    private suspend fun <T> callBeta(
        what: String,
        params: () -> BetaMessageCreateParams,
        options: () -> RequestOptions,
        read: (BetaMessage) -> GatewayResult<T>,
    ): GatewayResult<T> = withContext(Dispatchers.IO) {
        try {
            val key = readKey()
            if (key == null) {
                dropClient()
                return@withContext GatewayResult.NoKey
            }
            val p = params()
            read(clientFor(key).beta().messages().create(p, options()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DictationCall.failureOf(e, what)
        } catch (e: LinkageError) {
            DictationCall.incompatible(e, what)
        }
    }

    override suspend fun classify(system: String, user: String): GatewayResult<ClassificationResult> = withContext(Dispatchers.IO) {
        try {
            val key = readKey()
            if (key == null) {
                dropClient()
                return@withContext GatewayResult.NoKey
            }
            val params = classificationParams(system, user)
            ClassificationCall.read(clientFor(key).messages().create(params))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DictationCall.failureOf(e, "Clasificación")
        } catch (e: LinkageError) {
            DictationCall.incompatible(e, "Clasificación")
        }
    }

    override suspend fun testKey(): KeyTestResult = withContext(Dispatchers.IO) {
        try {
            val key = readKey()
            if (key == null) {
                dropClient()
                return@withContext KeyTestResult.INVALID
            }
            val params = keyTestParams()
            clientFor(key).messages().create(params)
            KeyTestResult.OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnauthorizedException) {
            KeyTestResult.INVALID
        } catch (e: AnthropicIoException) {
            KeyTestResult.NETWORK
        } catch (e: Exception) {
            KeyTestResult.ERROR
        } catch (e: LinkageError) {
            DictationCall.incompatible(e, "Prueba de clave")
            KeyTestResult.ERROR
        }
    }
}

/** Petición mínima para comprobar la clave. */
internal fun keyTestParams(): MessageCreateParams =
    MessageCreateParams.builder()
        .model(ClaudeModels.DICTATION)
        .maxTokens(16L)
        .addUserMessage("ok")
        .build()

/**
 * Petición de dictado y lectura de su respuesta; separado para probarlo sin red.
 *
 * Va por el endpoint beta porque el SDK 3.9.0 expone ahí `fallbacks` ("default", beta server-side-fallback-2026-07-01):
 * si Claude Opus 5.5 rechaza, el servidor reintenta con el modelo de respaldo dentro de la misma llamada. Si toda la cadena
 * rechaza, llega stopReason == REFUSAL y se trata como [GatewayResult.Refused].
 */
internal object DictationCall {
    private const val MAX_TOKENS = 4096L

    /** Esfuerzo bajo y el schema escrito a mano [Schemas.DICTATION] (ver [betaStructuredParams]). */
    fun params(system: String, user: String): BetaMessageCreateParams =
        betaStructuredParams(ClaudeModels.DICTATION, MAX_TOKENS, BetaOutputConfig.Effort.LOW, Schemas.DICTATION, system, user)

    /** Opus 5.5 siempre piensa y un respaldo puede correr dos modelos en la misma llamada: más que los 30 s del cliente. */
    val TIMEOUT: Duration = Duration.ofSeconds(60)

    fun requestOptions(): RequestOptions = RequestOptions.builder().timeout(TIMEOUT).build()

    /** Aviso en logcat: solo la clase de la excepción y el código HTTP; nunca mensaje, cuerpo, cabeceras ni clave. */
    @Volatile var warn: (String) -> Unit = { Log.w("ClaudeGateway", it) }

    /** Revisa stopReason antes de leer el contenido. */
    fun read(response: BetaMessage): GatewayResult<DictationResult> = readStructured(response, DictationResult::class.java, "dictado")

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

    /**
     * El SDK no encaja con el runtime (p. ej. NoSuchMethodError en ART): es de configuración, no del dictado; no se arregla
     * repitiendo y un dictado en cola se conserva. Solo se registra la clase.
     */
    fun incompatible(e: LinkageError, what: String = "Dictado"): GatewayResult<Nothing> {
        warn("$what falló: ${e.javaClass.simpleName}")
        return GatewayResult.Error(retryable = false, config = true)
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(cause) { it.cause?.takeIf { c -> c !== it } }
}

/**
 * Petición beta con salida estructurada: respaldo en el servidor (`fallbacks` "default", beta server-side-fallback-2026-07-01),
 * system fijo y cacheable, un solo mensaje de usuario (sin prefill), sin `thinking` ni temperatura. El schema es siempre uno
 * escrito a mano de [Schemas], nunca el derivado de la clase (revienta en ART: Method.getAnnotatedReturnType no existe).
 */
internal fun betaStructuredParams(
    model: String,
    maxTokens: Long,
    effort: BetaOutputConfig.Effort,
    schema: Map<String, Any>,
    system: String,
    user: String,
): BetaMessageCreateParams =
    BetaMessageCreateParams.builder()
        .model(model)
        .maxTokens(maxTokens)
        .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
        .fallbacksDefault()
        .systemOfBetaTextBlockParams(
            listOf(
                // El system es fijo: se marca cacheable (si no alcanza el mínimo de tokens, el API simplemente no cachea).
                BetaTextBlockParam.builder().text(system).cacheControl(BetaCacheControlEphemeral.builder().build()).build(),
            ),
        )
        .addUserMessage(user)
        .outputConfig(BetaOutputConfig.builder().effort(effort).format(Schemas.betaFormat(schema)).build())
        .build()

/**
 * Lectura común de una respuesta beta con salida estructurada: revisa stopReason antes del contenido y lee el último bloque
 * de texto con Jackson ([StructuredJson]). [what] solo aparece en el log, que lleva la clase de la excepción y nunca el texto.
 */
internal fun <T : Any> readStructured(response: BetaMessage, type: Class<T>, what: String): GatewayResult<T> {
    val stop = response.stopReason().orElse(null)
    if (stop == BetaStopReason.REFUSAL) return GatewayResult.Refused
    // Truncada por max_tokens: puede salir bien al repetir.
    if (stop == BetaStopReason.MAX_TOKENS) return GatewayResult.Error(retryable = true)
    if (stop != BetaStopReason.END_TURN) return GatewayResult.Error(retryable = false)
    return try {
        // Tras un respaldo puede haber texto parcial del primer modelo antes del bloque fallback: vale el último texto.
        val block = response.content().lastOrNull { it.isText() } ?: return GatewayResult.Error(retryable = false)
        GatewayResult.Ok(StructuredJson.parse(block.asText().text(), type))
    } catch (e: Exception) {
        // JSON que no encaja en la clase (excepción de Jackson) u otra respuesta ilegible.
        DictationCall.warn("Respuesta de $what ilegible: ${e.javaClass.simpleName}")
        GatewayResult.Error(retryable = false)
    }
}

/**
 * Petición de planificación de un día (D8): Claude Opus 5.5, esfuerzo MEDIUM (equilibra meta de trabajo, huecos, ejercicio,
 * almuerzo y sueño sin la espera de HIGH), 16000 tokens y 90 s por petición. Mismo endpoint beta y respaldo que el dictado;
 * schema escrito a mano [Schemas.PLAN]. Los fallos se clasifican con [DictationCall.failureOf] / [DictationCall.incompatible].
 */
internal object PlanCall {
    private const val MAX_TOKENS = 16_000L

    fun params(system: String, user: String): BetaMessageCreateParams =
        betaStructuredParams(ClaudeModels.PLANNING, MAX_TOKENS, BetaOutputConfig.Effort.MEDIUM, Schemas.PLAN, system, user)

    /** Más pensamiento que el dictado y un posible respaldo con dos modelos en la misma llamada. */
    val TIMEOUT: Duration = Duration.ofSeconds(90)

    fun requestOptions(): RequestOptions = RequestOptions.builder().timeout(TIMEOUT).build()

    fun read(response: BetaMessage): GatewayResult<PlanResult> = readStructured(response, PlanResult::class.java, "plan")
}

/**
 * Petición de clasificación (Haiku 5.5) y lectura de su respuesta. Va por el endpoint NO beta: sin respaldo en el servidor,
 * un rechazo (stopReason == REFUSAL) significa simplemente "sin clasificar". Esfuerzo bajo, sin `thinking`, sin temperatura ni prefill.
 * Schema escrito a mano ([Schemas.CLASSIFICATION]).
 */
internal object ClassificationCall {
    private const val MAX_TOKENS = 4096L

    fun params(system: String, user: String): MessageCreateParams =
        MessageCreateParams.builder()
            .model(ClaudeModels.CLASSIFICATION)
            .maxTokens(MAX_TOKENS)
            .system(system)
            .addUserMessage(user)
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.LOW)
                    .format(Schemas.format(Schemas.CLASSIFICATION))
                    .build(),
            )
            .build()

    /** Revisa stopReason antes de leer el contenido. */
    fun read(response: Message): GatewayResult<ClassificationResult> {
        val stop = response.stopReason().orElse(null)
        if (stop == StopReason.REFUSAL) return GatewayResult.Refused
        if (stop == StopReason.MAX_TOKENS) return GatewayResult.Error(retryable = true)
        if (stop != StopReason.END_TURN) return GatewayResult.Error(retryable = false)
        return try {
            val block = response.content().lastOrNull { it.isText() } ?: return GatewayResult.Error(retryable = false)
            GatewayResult.Ok(StructuredJson.parse(block.asText().text(), ClassificationResult::class.java))
        } catch (e: Exception) {
            DictationCall.warn("Respuesta de clasificación ilegible: ${e.javaClass.simpleName}")
            GatewayResult.Error(retryable = false)
        }
    }
}
