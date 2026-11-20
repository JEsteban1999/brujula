package app.brujula.voice

import android.content.Context
import android.content.Intent
import app.brujula.ui.DeepLinks
import app.brujula.ui.Routes
import kotlinx.coroutines.delay
import java.time.LocalDate

object QuickVoiceCopy {
    const val LISTENING = "Escuchando… di lo que vas a hacer"
    const val SAVED_OFFLINE = "Lo guardé. Lo agendo cuando vuelva internet."
    const val SAVED_NO_KEY = "Lo guardé. Agrega tu clave de Claude en Tu rutina y lo agendo."
    const val MIC_DENIED = "Sin permiso de micrófono."
    const val OPEN_APP = "Abrir Brújula"
    const val ANSWER = "Responder"
}

/** Cuándo se cierra sola la ventanita de dictado del widget. */
object QuickVoicePolicy {
    const val CLOSE_AFTER_RESULT_MS = 4_000L
    const val CLOSE_AFTER_UNDO_MS = 1_500L
    const val CLOSE_AFTER_SAVED_MS = 3_000L

    /** Milisegundos hasta cerrar en [state]; `null` si la ventana debe esperar al usuario. */
    fun autoCloseDelay(state: VoiceState): Long? = when (state) {
        is VoiceState.Result -> if (state.proposal == null) CLOSE_AFTER_RESULT_MS else null
        VoiceState.Undone -> CLOSE_AFTER_UNDO_MS
        VoiceState.SavedOffline, VoiceState.SavedNeedsKey -> CLOSE_AFTER_SAVED_MS
        else -> null
    }

    /** Espera el tiempo de [state] y llama [onClose]; sin tiempo, no hace nada. Se cancela (y reinicia) al cambiar el estado. */
    suspend fun closeWhenDue(state: VoiceState, onClose: () -> Unit) {
        val wait = autoCloseDelay(state) ?: return
        delay(wait)
        onClose()
    }

    /** Abre la app en la Propuesta de [day]: siempre por [DeepLinks.intent] (con el secreto de la app). */
    fun proposalIntent(context: Context, day: LocalDate, request: String): Intent =
        DeepLinks.intent(context, Routes.proposal(day, request.take(DeepLinks.MAX_REQUEST)))

    /** Abre la app en la pantalla de voz. */
    fun voiceIntent(context: Context): Intent = DeepLinks.intent(context, DeepLinks.VOICE)
}
