package app.brujula.claude

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class PendingDictation(val text: String, val at: Long, val attempts: Int = 0)

/** Avisos de la cola; producción: notificaciones en el canal `actividades`. */
interface PendingNotifier {
    /** Se agendó algo; [problems] son avisos de lo que no se pudo (motivos de acciones inválidas, preguntas). */
    fun scheduled(labels: List<String>, problems: List<String>)
    /** Un dictado salió de la cola sin agendarse del todo: nada se pierde sin aviso. */
    fun dropped(text: String, reason: String)
    /** La cola espera pero no hay clave utilizable (falta o la rechazan); [count] dictados pendientes. */
    fun needsKey(count: Int) = Unit
    /** La cola se detuvo porque Claude rechazó la solicitud (HTTP 400/404, p. ej. saldo agotado); [count] pendientes. */
    fun configStalled(count: Int) = Unit
    /** Un dictado de la cola pidió cambiar o armar un día: no se aplica nada, solo se avisa con un enlace a la Propuesta. */
    fun planRequested(day: LocalDate, text: String) = Unit

    object None : PendingNotifier {
        override fun scheduled(labels: List<String>, problems: List<String>) = Unit
        override fun dropped(text: String, reason: String) = Unit
    }
}

/** Textos de las notificaciones de la cola. */
object PendingCopy {
    fun configNotice(count: Int): String =
        (if (count == 1) "No pude procesar 1 dictado pendiente" else "No pude procesar $count dictados pendientes") +
            ": Claude rechazó la solicitud. Revisa tu saldo y tu clave en platform.claude.com."

    fun successTitle(labels: List<String>): String = "Agendé tu dictado pendiente: " + labels.joinToString("; ")

    const val PLAN_TITLE = "Claude puede reorganizar tu día"

    fun planText(text: String): String = "Toca para ver la propuesta: «$text»"

    fun keyNotice(count: Int): String =
        (if (count == 1) "Tienes 1 dictado pendiente." else "Tienes $count dictados pendientes.") +
            " Revisa tu clave de Claude en Tu rutina."
}

/**
 * Detecta el paso a "red validada": varias llamadas seguidas con red validada cuentan una sola vez. [initiallyValidated]:
 * el estado al registrarse; Android entrega enseguida el estado actual y eso no es un cambio.
 */
class ValidatedTransition(initiallyValidated: Boolean = false) {
    private var last = initiallyValidated

    @Synchronized
    fun onChange(validated: Boolean): Boolean {
        val rose = validated && !last
        last = validated
        return rose
    }
}

/**
 * Dictados que no se pudieron interpretar por falta de internet. Se guardan en DataStore (texto + instante + intentos) y se
 * procesan en orden al volver la red. Un elemento solo sale de la cola después de aplicarse o de avisar al usuario.
 */
class PendingDictations(
    private val dataStore: DataStore<Preferences>,
    private val notifier: PendingNotifier = PendingNotifier.None,
    /** La tarjeta de Claude en Hoy: un pedido de plan deja ahí su nota por si la notificación está bloqueada. */
    private val notes: ClaudeNotes? = null,
) {
    private val key = stringPreferencesKey("pending_dictations")
    private val backupKey = stringPreferencesKey("pending_dictations_backup")
    private val drainMutex = Mutex()

    suspend fun all(): List<PendingDictation> = parse(dataStore.data.first()[key]).items

    suspend fun add(text: String, at: Long) = update { it + PendingDictation(text, at) }

    private suspend fun update(change: (List<PendingDictation>) -> List<PendingDictation>) {
        dataStore.edit { prefs ->
            val parsed = parse(prefs[key])
            // Algo ilegible se respalda antes de escribir: nunca se pisa sin copia.
            if (parsed.damaged) prefs[backupKey] = prefs[key].orEmpty()
            prefs[key] = encode(change(parsed.items))
        }
    }

    /**
     * Procesa la cola en orden; si ya hay una pasada en curso, vuelve de inmediato. Se detiene en el primer fallo
     * recuperable (sin conexión, sin clave, error del servicio) y conserva el resto. Tras [MAX_ATTEMPTS] fallos de un
     * elemento, o ante un rechazo definitivo / una pregunta sin acciones, el elemento sale con un aviso.
     */
    suspend fun drain(interpreter: TextInterpreter, executor: ActionApplier) {
        var again: Boolean
        do {
            // Una peticion que llega con otra pasada en curso no se pierde: la pasada vigente da una vuelta mas.
            if (!drainMutex.tryLock()) { rerunRequested = true; return }
            var clean = true
            try {
                do {
                    rerunRequested = false
                    clean = pass(interpreter, executor)
                    // Tras un fallo no se repite: otra vuelta solo gastaría otro intento del mismo elemento.
                } while (clean && rerunRequested)
            } finally {
                drainMutex.unlock()
            }
            again = clean && rerunRequested
        } while (again)
    }

    @Volatile private var rerunRequested = false
    private val keyNoticeSent = AtomicBoolean(false)
    private val configNoticeSent = AtomicBoolean(false)

    /** Devuelve true si la pasada terminó porque la cola quedó vacía; false si se detuvo en un fallo recuperable. */
    private suspend fun pass(interpreter: TextInterpreter, executor: ActionApplier): Boolean {
        val labels = mutableListOf<String>()
        val problems = mutableListOf<String>()
        try {
            while (true) {
                val item = all().firstOrNull() ?: return true
                val outcome = try {
                    interpreter.interpret(item.text, item.at)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    InterpretOutcome.Failed(DictationInterpreter.UNAVAILABLE, retryable = true)
                }
                // Aplicar y sacar de la cola van juntos: si no, una cancelación a medias duplicaría el dictado.
                val proceed = withContext(NonCancellable) {
                    when (outcome) {
                        is InterpretOutcome.Ok -> handleOk(item, outcome, executor, labels, problems)
                        is InterpretOutcome.Refused -> { drop(item, outcome.message); true }
                        is InterpretOutcome.Failed -> when {
                            // Red o configuración: no es culpa del dictado; se conserva y no cuenta como intento.
                            outcome.config -> { noticeConfigStall(); false }
                            outcome.network -> false
                            outcome.retryable -> failedAttempt(item, outcome.message)
                            else -> { drop(item, outcome.message); true }
                        }
                        InterpretOutcome.NoKey, InterpretOutcome.InvalidKey -> { noticeMissingKey(); false }
                        InterpretOutcome.Offline -> false // sin conexión: no cuenta como intento
                    }
                }
                if (!proceed) return false
            }
        } finally {
            if (labels.isNotEmpty()) notifier.scheduled(labels.toList(), problems.toList())
        }
    }

    private suspend fun noticeConfigStall() {
        val count = all().size
        if (count > 0 && configNoticeSent.compareAndSet(false, true)) notifier.configStalled(count)
    }

    /** Una sola vez por proceso: avisa que los pendientes esperan una clave que sirva. */
    private suspend fun noticeMissingKey() {
        val count = all().size
        if (count > 0 && keyNoticeSent.compareAndSet(false, true)) notifier.needsKey(count)
    }

    private suspend fun handleOk(
        item: PendingDictation, outcome: InterpretOutcome.Ok, executor: ActionApplier,
        labels: MutableList<String>, problems: MutableList<String>,
    ): Boolean {
        val work = outcome.items.any { it is Validated.Activity || it is Validated.Habit }
        // Un pedido de plan es trabajo atendido: nunca se aplica solo; el usuario lo ve en la Propuesta (por el aviso).
        val plans = outcome.items.filterIsInstance<Validated.PlanRequest>().distinctBy { it.day }
        if (!work && plans.isNotEmpty()) {
            update { it.drop(1) }
            plans.forEach { notifier.planRequested(it.day, it.text.ifBlank { item.text }) }
            plans.firstOrNull()?.let { writePlanNote(it.day, it.text.ifBlank { item.text }) }
            return true
        }
        if (!work) {
            val reasons = outcome.items.mapNotNull {
                when (it) {
                    is Validated.Question -> it.text
                    is Validated.Unsupported -> it.text
                    is Validated.Invalid -> it.reason
                    else -> null
                }
            }
            drop(item, reasons.joinToString(" ").ifBlank { outcome.reply.ifBlank { NOTHING_SCHEDULED } })
            return true
        }
        val result = executor.apply(outcome.items, outcome.reply)
        if (result.created.isEmpty()) return failedAttempt(item, result.messages.lastOrNull() ?: NOTHING_SCHEDULED)
        labels += result.created.map { it.label }
        problems += result.messages
        update { it.drop(1) }
        plans.forEach { notifier.planRequested(it.day, it.text.ifBlank { item.text }) }
        return true
    }

    private suspend fun writePlanNote(day: LocalDate, text: String) {
        try {
            notes?.set(ClaudeNote("Pediste: «$text». Toca para que Claude lo arme.", day, null, request = text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // La nota es un extra: el pedido ya salió de la cola con su aviso.
        }
    }

    /** Cuenta un intento fallido; devuelve true (seguir) solo si el elemento se descartó tras el último intento. */
    private suspend fun failedAttempt(item: PendingDictation, reason: String): Boolean {
        if (item.attempts + 1 >= MAX_ATTEMPTS) {
            drop(item, reason)
            return true
        }
        update { list -> list.mapIndexed { i, e -> if (i == 0) e.copy(attempts = e.attempts + 1) else e } }
        return false
    }

    private suspend fun drop(item: PendingDictation, reason: String) {
        update { it.drop(1) }
        notifier.dropped(item.text, reason)
    }

    private fun encode(items: List<PendingDictation>): String {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("t", it.text).put("a", it.at).put("n", it.attempts)) }
        return arr.toString()
    }

    private class Parsed(val items: List<PendingDictation>, val damaged: Boolean)

    /** Entrada por entrada: una dañada se salta sin tumbar las demás. */
    private fun parse(raw: String?): Parsed {
        if (raw.isNullOrEmpty()) return Parsed(emptyList(), false)
        val arr = try { JSONArray(raw) } catch (e: Exception) { return Parsed(emptyList(), true) }
        var damaged = false
        val items = (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.getJSONObject(i)
                PendingDictation(o.getString("t"), o.getLong("a"), o.optInt("n", 0))
            } catch (e: Exception) {
                damaged = true
                null
            }
        }
        return Parsed(items, damaged)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        private const val NOTHING_SCHEDULED = "No se agendó nada."
    }
}
