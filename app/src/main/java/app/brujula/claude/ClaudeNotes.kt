package app.brujula.claude

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.time.LocalDate

/** La nota de la tarjeta de Claude en Hoy (D15). [proposalId] presente → el plan de ese día se puede deshacer. */
data class ClaudeNote(
    val text: String,
    val day: LocalDate,
    val proposalId: Long?,
    /** Pedido de plan en cola: el botón de la tarjeta abre la Propuesta de [day] con este texto. */
    val request: String? = null,
)

/**
 * Última nota de Claude, en DataStore. La escriben el dictado y aceptar o deshacer un plan. Se guarda en una sola clave
 * (JSON) para que texto, día y propuesta cambien juntos; un valor dañado se lee como "sin nota".
 */
class ClaudeNotes(private val dataStore: DataStore<Preferences>) {
    private val key = stringPreferencesKey(KEY)

    val note: Flow<ClaudeNote?> = dataStore.data.map { decode(it[key]) }.distinctUntilChanged()

    suspend fun set(n: ClaudeNote) {
        val json = JSONObject().put("text", n.text).put("day", n.day.toString()).put("proposalId", n.proposalId ?: JSONObject.NULL)
            .put("request", n.request ?: JSONObject.NULL)
        dataStore.edit { it[key] = json.toString() }
    }

    private fun decode(s: String?): ClaudeNote? = s?.let {
        runCatching {
            val j = JSONObject(it)
            ClaudeNote(
                j.getString("text"), LocalDate.parse(j.getString("day")),
                if (j.isNull("proposalId")) null else j.getLong("proposalId"),
                // Las notas guardadas antes no traen `request` (isNull también es true si falta).
                if (j.isNull("request")) null else j.getString("request"),
            )
        }.getOrNull()
    }

    companion object {
        const val KEY = "claude_note"
    }
}
