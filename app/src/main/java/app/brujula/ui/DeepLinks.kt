package app.brujula.ui

import android.content.Context
import android.content.Intent
import java.net.URLDecoder
import java.time.LocalDate

/**
 * Enlaces desde notificaciones (D13). El extra del intent viaja como texto de otra app o de una alarma guardada: nunca se
 * navega a él tal cual. [parse] solo deja pasar una lista blanca (y devuelve `null` con todo lo demás), y [toNavRoute] la
 * traduce a una ruta de la app armada aquí, no copiada del extra.
 *
 * Rutas: `today`, `voice`, `plan_tomorrow`, `proposal?day=AAAA-MM-DD[&request=texto]` (el texto va codificado como URL y
 * mide hasta 500 caracteres ya decodificado).
 */
object DeepLinks {
    const val EXTRA_ROUTE = "app.brujula.route"

    const val TODAY = "today"
    const val VOICE = "voice"
    const val PLAN_TOMORROW = "plan_tomorrow"
    private const val PROPOSAL = "proposal?"
    const val MAX_REQUEST = 500

    /** Largo máximo del texto crudo (el decodificado manda en [MAX_REQUEST]); evita procesar basura enorme. */
    private const val MAX_RAW = 4_000

    private class Proposal(val day: LocalDate, val request: String?)

    /** La ruta si está en la lista blanca; si no, `null`. */
    fun parse(raw: String?): String? {
        if (raw == null || raw.length > MAX_RAW) return null
        return when (raw) {
            TODAY, VOICE, PLAN_TOMORROW -> raw
            else -> raw.takeIf { parseProposal(it) != null }
        }
    }

    /** Ruta de navegación de Compose para una ruta válida; [tomorrow] es el día lógico de mañana. `null` si no es válida. */
    fun toNavRoute(route: String, tomorrow: LocalDate): String? {
        val valid = parse(route) ?: return null
        return when (valid) {
            TODAY -> Routes.TODAY
            VOICE -> Routes.VOICE
            PLAN_TOMORROW -> Routes.proposal(tomorrow)
            else -> parseProposal(valid)?.let { Routes.proposal(it.day, it.request) }
        }
    }

    fun intent(context: Context, route: String): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun parseProposal(raw: String): Proposal? {
        if (!raw.startsWith(PROPOSAL)) return null
        val parts = raw.removePrefix(PROPOSAL).split('&')
        var day: LocalDate? = null
        var request: String? = null
        var seenRequest = false
        for ((i, part) in parts.withIndex()) {
            val eq = part.indexOf('=')
            if (eq <= 0) return null
            val key = part.substring(0, eq)
            val value = part.substring(eq + 1)
            when {
                key == "day" && i == 0 -> day = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
                key == "request" && i == 1 && !seenRequest -> {
                    seenRequest = true
                    request = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: return null
                    if (request.length > MAX_REQUEST) return null
                }
                else -> return null
            }
        }
        return day?.let { Proposal(it, request?.takeIf { r -> r.isNotBlank() }) }
    }
}
