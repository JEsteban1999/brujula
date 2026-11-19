package app.brujula.calendar

import android.content.SharedPreferences
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * Diario de copias a Google pendientes. Se escribe (con `commit()`, síncrono) antes de que se confirme el cambio en la
 * base y cada entrada se borra cuando la tanda en `syncScope` la termina. Si el proceso muere en medio, al arrancar
 * [drain] completa lo que faltó. Cada paso relee el estado actual, así que una entrada de una transacción revertida, o
 * repetida, no hace nada:
 * - [Entry.Save]: `afterSave` de la fila actual, solo si existe;
 * - [Entry.Delete]: `afterDelete` de la foto guardada, solo si la fila ya no existe.
 *
 * La revisión de la fila se hace con el cerrojo de [GoogleCopySync] tomado, así nunca se cruza con otra copia en curso.
 *
 * Una entrada que no se pudo hacer (sin permiso, o con error) se queda para el siguiente [drain]. Sin permiso de escritura
 * se sueltan las que no tienen id de evento: no hay copia conocida que borrar o actualizar, y una copia que falta la crea
 * `reconcile`. Sin [prefs], vive en memoria (pruebas y valores por defecto).
 */
open class GoogleCopyJournal(private val prefs: SharedPreferences? = null) {
    sealed interface Entry {
        val token: String
        val activityId: Long

        data class Save(override val activityId: Long, override val token: String = newToken()) : Entry

        data class Delete(override val activityId: Long, val googleEventId: Long?, override val token: String = newToken()) : Entry
    }

    private val memory = mutableSetOf<String>()

    fun save(activityId: Long): Entry.Save = Entry.Save(activityId)

    fun delete(before: Activity): Entry.Delete = Entry.Delete(before.id, before.googleEventId)

    /** Persiste [entries] antes de confirmar el cambio; si no se puede escribir, lanza (y la transacción se revierte). */
    @Synchronized
    open fun record(entries: List<Entry>) {
        if (entries.isEmpty()) return
        val next = load() + entries.map { encode(it) }
        if (prefs == null) {
            memory.clear(); memory += next
        } else {
            check(prefs.edit().putStringSet(KEY, next).commit()) { "No se pudo guardar el diario de copias a Google" }
        }
    }

    @Synchronized
    fun remove(entry: Entry) {
        val next = load() - encode(entry)
        if (prefs == null) {
            memory.clear(); memory += next
        } else {
            // Perder un borrado solo repite un paso idempotente al arrancar: basta apply().
            prefs.edit().putStringSet(KEY, next).apply()
        }
    }

    @Synchronized
    fun pending(): List<Entry> = load().mapNotNull { decode(it) }

    /** Hace [entry] contra el estado actual y la borra del diario si quedó terminada. */
    suspend fun run(entry: Entry, sync: GoogleCopySync, repo: ActivityRepository) {
        if (perform(entry, sync, repo)) remove(entry)
    }

    /** Al arrancar (y al conceder el permiso): termina lo que quedó pendiente. */
    suspend fun drain(sync: GoogleCopySync, repo: ActivityRepository) {
        pending().forEach { run(it, sync, repo) }
    }

    private suspend fun perform(entry: Entry, sync: GoogleCopySync, repo: ActivityRepository): Boolean = when (entry) {
        is Entry.Save -> when (sync.afterSaveIfExists(entry.activityId)) {
            GoogleCopyStatus.FAILED -> false
            // Sin vínculo no hay copia que actualizar: reconcile la creará cuando haya permiso.
            GoogleCopyStatus.NO_PERMISSION -> unlinked(entry.activityId, repo)
            else -> true
        }
        is Entry.Delete -> (entry.googleEventId == null && !sync.canWrite()) || sync.afterDeleteIfGone(entry.activityId, entry.googleEventId)
    }

    private suspend fun unlinked(id: Long, repo: ActivityRepository): Boolean = try {
        repo.get(id)?.googleEventId == null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private fun load(): Set<String> = if (prefs == null) memory.toSet() else prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    private fun encode(e: Entry): String = when (e) {
        is Entry.Save -> "save|${e.token}|${e.activityId}"
        is Entry.Delete -> "delete|${e.token}|${e.activityId}|${e.googleEventId ?: ""}"
    }

    private fun decode(s: String): Entry? = runCatching {
        val p = s.split('|')
        when (p[0]) {
            "save" -> Entry.Save(p[2].toLong(), p[1])
            "delete" -> Entry.Delete(p[2].toLong(), p[3].takeIf { it.isNotEmpty() }?.toLong(), p[1])
            else -> null
        }
    }.getOrNull()

    companion object {
        private const val KEY = "entries"
        const val PREFS_NAME = "google_copy_journal"
        private fun newToken() = UUID.randomUUID().toString()
    }
}
