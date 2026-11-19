package app.brujula.claude

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Guarda la clave de Claude solo cifrada. Si no se puede descifrar, se descarta. El cifrado nunca corre en Main. */
class ClaudeKeyStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val key = stringPreferencesKey("claude_api_key_enc")

    val hasKey: Flow<Boolean> = dataStore.data.map { it[key] != null }

    suspend fun save(key: String) {
        val enc = withContext(cryptoDispatcher) { cipher.encrypt(key) }
        dataStore.edit { it[this.key] = enc }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(key) }
    }

    suspend fun read(): String? {
        val stored = dataStore.data.first()[key] ?: return null
        val plain = withContext(cryptoDispatcher) { try { cipher.decrypt(stored) } catch (e: Exception) { null } }
        if (plain == null) clear()
        return plain
    }
}
