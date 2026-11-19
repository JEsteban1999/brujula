package app.brujula.claude

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class PendingDictation(val text: String, val at: Long)

/**
 * Dictados que no se pudieron interpretar por falta de internet. Se guardan en DataStore (texto + instante) y se
 * procesan en orden al volver la red. Un elemento solo sale de la cola después de aplicarse.
 */
class PendingDictations(
    private val dataStore: DataStore<Preferences>,
    /** Recibe las etiquetas de lo agendado al terminar una pasada (producción: notificación en `actividades`). */
    private val notifier: (List<String>) -> Unit = {},
) {
    private val key = stringPreferencesKey("pending_dictations")
    private val drainMutex = Mutex()

    suspend fun all(): List<PendingDictation> = parse(dataStore.data.first()[key])

    suspend fun add(text: String, at: Long) {
        dataStore.edit { prefs -> prefs[key] = encode(parse(prefs[key]) + PendingDictation(text, at)) }
    }

    private suspend fun removeFirst() {
        dataStore.edit { prefs -> prefs[key] = encode(parse(prefs[key]).drop(1)) }
    }

    /**
     * Procesa la cola en orden. Se detiene en el primer fallo recuperable (sin conexión, sin clave, error del servicio)
     * y conserva el resto. Un rechazo definitivo de Claude se descarta para no bloquear la cola. Nunca corren dos a la vez.
     */
    suspend fun drain(interpreter: TextInterpreter, executor: ActionApplier) = drainMutex.withLock {
        val labels = mutableListOf<String>()
        try {
            while (true) {
                val item = all().firstOrNull() ?: break
                val outcome = try {
                    interpreter.interpret(item.text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    break
                }
                // Aplicar y sacar de la cola van juntos: si no, una cancelación a medias duplicaría el dictado.
                val proceed = withContext(NonCancellable) {
                    when (outcome) {
                        is InterpretOutcome.Ok -> {
                            val work = outcome.items.any { it is Validated.Activity || it is Validated.Habit }
                            if (work) {
                                val result = executor.apply(outcome.items)
                                if (result.created.isEmpty()) return@withContext false
                                labels += result.created.map { it.label }
                            }
                            removeFirst()
                            true
                        }
                        is InterpretOutcome.Refused -> { removeFirst(); true }
                        is InterpretOutcome.Failed -> if (outcome.retryable) false else { removeFirst(); true }
                        else -> false
                    }
                }
                if (!proceed) break
            }
        } finally {
            if (labels.isNotEmpty()) notifier(labels.toList())
        }
    }

    private fun encode(items: List<PendingDictation>): String {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("t", it.text).put("a", it.at)) }
        return arr.toString()
    }

    private fun parse(raw: String?): List<PendingDictation> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> arr.getJSONObject(i).let { PendingDictation(it.getString("t"), it.getLong("a")) } }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
