package app.brujula.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

enum class SpeechFailure { NO_MATCH, NETWORK, OTHER }

sealed interface SpeechEvent {
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    data class Failure(val kind: SpeechFailure) : SpeechEvent
}

/** Entrada de voz; todos los métodos y callbacks van en el hilo principal. */
interface SpeechInput {
    /** Falso si el teléfono no tiene un servicio de reconocimiento: solo queda el campo de texto. */
    val isAvailable: Boolean
    fun start(onEvent: (SpeechEvent) -> Unit)
    /** Termina de escuchar y entrega el resultado final. */
    fun stop()
    /** Descarta lo escuchado sin entregar nada. */
    fun cancel()
    /** Libera el reconocedor; no se puede usar después. */
    fun destroy()
}

/** `SpeechRecognizer` en español de Colombia con respaldo `es` si el idioma no está disponible. */
class AndroidSpeechInput(context: Context) : SpeechInput {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var destroyed = false

    override val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    override fun start(onEvent: (SpeechEvent) -> Unit) {
        if (destroyed) return
        begin(onEvent, PRIMARY_LANGUAGE)
    }

    private fun begin(onEvent: (SpeechEvent) -> Unit, language: String) {
        release()
        if (destroyed) return
        val r = try {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        } catch (e: Exception) {
            onEvent(SpeechEvent.Failure(SpeechFailure.OTHER))
            return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            private fun live() = recognizer === r
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults.firstResult() ?: return
                if (live()) onEvent(SpeechEvent.Partial(t))
            }
            override fun onResults(results: Bundle?) {
                if (!live()) return
                val t = results.firstResult()
                onEvent(if (t == null) SpeechEvent.Failure(SpeechFailure.NO_MATCH) else SpeechEvent.Final(t))
            }
            override fun onError(error: Int) {
                if (!live()) return
                when (error) {
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        if (language == PRIMARY_LANGUAGE) main.post { begin(onEvent, FALLBACK_LANGUAGE) }
                        else onEvent(SpeechEvent.Failure(SpeechFailure.OTHER))
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        onEvent(SpeechEvent.Failure(SpeechFailure.NO_MATCH))
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        onEvent(SpeechEvent.Failure(SpeechFailure.NETWORK))
                    else -> onEvent(SpeechEvent.Failure(SpeechFailure.OTHER))
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            onEvent(SpeechEvent.Failure(SpeechFailure.OTHER))
        }
    }

    override fun stop() { runCatching { recognizer?.stopListening() } }

    override fun cancel() { release() }

    override fun destroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        release()
    }

    private fun release() {
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel() }
        runCatching { r.destroy() }
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        const val PRIMARY_LANGUAGE = "es-CO"
        const val FALLBACK_LANGUAGE = "es"
    }
}
