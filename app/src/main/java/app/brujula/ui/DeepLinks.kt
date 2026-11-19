package app.brujula.ui

import android.content.Context
import android.content.Intent
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Base64

/**
 * Enlaces desde notificaciones (D13). El extra del intent viaja como texto de otra app o de una alarma guardada: nunca se
 * navega a él tal cual. [parse] solo deja pasar una lista blanca (y devuelve `null` con todo lo demás), y [toNavRoute] la
 * traduce a una ruta de la app armada aquí, no copiada del extra.
 *
 * Rutas: `today`, `voice`, `plan_tomorrow`, `proposal?day=AAAA-MM-DD[&request=texto]` (el texto va codificado como URL y
 * mide hasta 500 caracteres ya decodificado).
 *
 * MainActivity está exportada, así que cualquier app puede mandar el extra. Un `request` dispara enseguida una llamada a
 * Claude, por eso solo se respeta si el intent trae además [EXTRA_TOKEN] con el secreto de la app ([token]), que solo
 * ponen nuestras notificaciones ([intent]); sin él se abre el mismo día sin pedido ([fromIntent]).
 */
object DeepLinks {
    const val EXTRA_ROUTE = "app.brujula.route"
    const val EXTRA_TOKEN = "app.brujula.link_token"
    private const val PREFS = "brujula_links"
    private const val KEY_TOKEN = "token"

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

    /** Ruta de navegación de Compose para una ruta válida; [tomorrow] es el día lógico de mañana (para las rutas con día). `null` si no es válida. */
    fun toNavRoute(route: String, tomorrow: LocalDate): String? {
        val valid = parse(route) ?: return null
        return when (valid) {
            TODAY -> Routes.TODAY
            VOICE -> Routes.VOICE
            PLAN_TOMORROW -> Routes.PLAN_TOMORROW
            else -> parseProposal(valid)?.let { Routes.proposal(it.day, it.request) }
        }
    }

    /**
     * La ruta de un intent que llegó a MainActivity: la de la lista blanca, pero sin `request` si [token] no es el secreto
     * de la app ([expected]); `null` si no es válida.
     */
    fun fromIntent(raw: String?, token: String?, expected: String): String? {
        val valid = parse(raw) ?: return null
        val proposal = parseProposal(valid) ?: return valid
        if (proposal.request == null) return valid
        val trusted = token != null && MessageDigest.isEqual(token.toByteArray(), expected.toByteArray())
        return if (trusted) valid else Routes.proposal(proposal.day)
    }

    /** Secreto aleatorio de la app, creado una vez y guardado en sus preferencias. */
    @Synchronized
    fun token(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TOKEN, null)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        prefs.edit().putString(KEY_TOKEN, token).commit()
        return token
    }

    fun intent(context: Context, route: String): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_ROUTE, route)
            .putExtra(EXTRA_TOKEN, token(context))
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
