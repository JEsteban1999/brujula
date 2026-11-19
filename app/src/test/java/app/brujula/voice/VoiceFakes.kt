package app.brujula.voice

import app.brujula.activities.Activity
import app.brujula.activities.ActivityType
import app.brujula.claude.ActionApplier
import app.brujula.claude.ApplyResult
import app.brujula.claude.CreatedItem
import app.brujula.claude.InterpretOutcome
import app.brujula.claude.TextInterpreter
import app.brujula.claude.UndoToken
import app.brujula.claude.Validated
import kotlinx.coroutines.CompletableDeferred
import java.time.LocalDate
import java.time.LocalTime

class FakeSpeechInput(override var isAvailable: Boolean = true) : SpeechInput {
    var starts = 0
    var stops = 0
    var cancels = 0
    var destroyed = false
    private var listener: ((SpeechEvent) -> Unit)? = null
    override fun start(onEvent: (SpeechEvent) -> Unit) { starts++; listener = onEvent }
    override fun stop() { stops++ }
    override fun cancel() { cancels++ }
    override fun destroy() { destroyed = true; listener = null }
    fun emit(e: SpeechEvent) = listener!!.invoke(e)
}

fun activity(title: String) = Validated.Activity(
    Activity(title = title, type = ActivityType.PERSONAL, date = LocalDate.of(2026, 11, 20), start = LocalTime.of(9, 0), durationMin = 30),
)

fun ok(vararg titles: String, reply: String = "Listo") = InterpretOutcome.Ok(titles.map { activity(it) }, reply)

/** Responde con la cola de resultados; si [gate] existe, espera a que se complete antes de responder. */
class FakeInterpreter(vararg outcomes: InterpretOutcome) : TextInterpreter {
    val queue = ArrayDeque(outcomes.toList())
    val texts = mutableListOf<String>()
    val dictatedAts = mutableListOf<Long?>()
    var gate: CompletableDeferred<Unit>? = null
    var running = 0
    var maxRunning = 0
    override suspend fun interpret(text: String, dictatedAt: Long?): InterpretOutcome {
        texts += text
        dictatedAts += dictatedAt
        running++
        maxRunning = maxOf(maxRunning, running)
        try {
            gate?.await()
            return queue.removeFirst()
        } finally {
            running--
        }
    }
}

class FakeExecutor(var failApply: Boolean = false, var failUndo: Boolean = false) : ActionApplier {
    var applyGate: CompletableDeferred<Unit>? = null
    val applied = mutableListOf<List<Validated>>()
    val undone = mutableListOf<UndoToken>()
    private var nextId = 1L
    override suspend fun apply(items: List<Validated>): ApplyResult {
        applyGate?.await()
        applied += items
        val messages = items.mapNotNull {
            when (it) {
                is Validated.Question -> it.text
                is Validated.Unsupported -> it.text
                is Validated.Invalid -> it.reason
                else -> null
            }
        }
        val acts = items.filterIsInstance<Validated.Activity>()
        if (acts.isEmpty()) return ApplyResult(emptyList(), messages, null)
        if (failApply) return ApplyResult(emptyList(), messages + "No pude guardar lo que pediste. No se agendó nada.", null)
        val created = acts.map { CreatedItem(it.activity.title, nextId++, null) }
        return ApplyResult(created, messages, UndoToken(created.map { it.activityId!! }, emptyList()))
    }
    override suspend fun undo(token: UndoToken) { if (failUndo) throw java.io.IOException("boom"); undone += token }
}

class RecordingNotifier : app.brujula.claude.PendingNotifier {
    val scheduled = mutableListOf<Pair<List<String>, List<String>>>()
    val dropped = mutableListOf<Pair<String, String>>()
    val needsKey = mutableListOf<Int>()
    val configStalled = mutableListOf<Int>()
    override fun configStalled(count: Int) { configStalled += count }
    override fun needsKey(count: Int) { needsKey += count }
    override fun scheduled(labels: List<String>, problems: List<String>) { scheduled += labels to problems }
    override fun dropped(text: String, reason: String) { dropped += text to reason }
}

/** DataStore que siempre falla (disco lleno, archivo dañado). */
class ThrowingDataStore : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
    override val data: kotlinx.coroutines.flow.Flow<androidx.datastore.preferences.core.Preferences> =
        kotlinx.coroutines.flow.flow { throw java.io.IOException("boom") }
    override suspend fun updateData(
        transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
    ): androidx.datastore.preferences.core.Preferences = throw java.io.IOException("boom")
}

/** Retrasa cada escritura hasta abrir [gate]; la escritura llega al almacen aunque quien llamo se cancele. */
class GatedDataStore(
    private val inner: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    val gate: CompletableDeferred<Unit>,
) : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
    override val data = inner.data
    override suspend fun updateData(
        transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
    ) = inner.updateData { gate.await(); transform(it) }
}
