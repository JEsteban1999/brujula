package app.brujula.planning

import app.brujula.data.db.ProposalDao
import app.brujula.data.db.ProposalEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime

/** Un plan aceptado: su id y el día que arma. */
data class AcceptedPlan(val id: Long, val day: LocalDate)

/** Propuestas guardadas (D1): sobreviven a la muerte del proceso; una sola PENDING por día. */
open class ProposalRepository(private val dao: ProposalDao) {
    /** Guarda [p] como la pendiente de su día; las otras pendientes de ese día pasan a SUPERSEDED (una transacción). */
    suspend fun savePending(p: Proposal): Long = dao.insertPending(
        ProposalEntity(
            day = p.day, createdAt = p.createdAt, status = ProposalStatus.PENDING.name, request = p.request, summary = p.summary,
            payload = ProposalCodec.encode(p),
        ),
    )

    /**
     * Un payload ilegible no se puede mostrar ni aplicar: se devuelve `null`. Si estaba PENDING se marca DISCARDED; una
     * ACCEPTED nunca se descarta (sus cambios siguen en la agenda y su día cuenta como armado).
     */
    open suspend fun get(id: Long): Proposal? = dao.get(id)?.let { toModel(it) }

    open suspend fun pendingFor(day: LocalDate): Proposal? = dao.latestByStatus(day, ProposalStatus.PENDING.name)?.let { toModel(it) }

    /** Solo si sigue PENDING: una propuesta aceptada o reemplazada nunca pasa a DISCARDED por aquí. */
    open suspend fun discard(id: Long) = dao.discardIfPending(id)

    /** La aceptada más reciente de [day] (con sus datos de vista previa), o `null`. */
    open suspend fun acceptedFor(day: LocalDate): Proposal? = dao.latestByStatus(day, ProposalStatus.ACCEPTED.name)?.let { toModel(it) }

    /** Planes aceptados, el más reciente primero; la tarjeta de Hoy elige el vigente (día >= hoy). */
    fun observeAcceptedPlans(): Flow<List<AcceptedPlan>> = dao.observeAccepted().map { rows -> rows.map { AcceptedPlan(it.id, it.day) } }

    fun observeAcceptedDays(): Flow<Set<LocalDate>> = dao.observeAcceptedDays().map { it.toSet() }

    suspend fun acceptedDays(): Set<LocalDate> = dao.acceptedDays().toSet()

    fun observeAcceptedIds(): Flow<Set<Long>> = dao.observeAcceptedIds().map { it.toSet() }

    internal suspend fun statusOf(id: Long): ProposalStatus? =
        dao.get(id)?.let { e -> runCatching { ProposalStatus.valueOf(e.status) }.getOrNull() }

    /** Datos para deshacer de una propuesta aceptada (JSON de [ProposalCodec.encodeUndo]). */
    internal suspend fun undoOf(id: Long): String? = dao.get(id)?.undo

    internal suspend fun markAccepted(id: Long, undo: String, at: LocalDateTime) = dao.markAccepted(id, undo, at)

    /** Tras deshacer: vuelve a PENDING (y es la pendiente de su día) para poder aceptarla otra vez (D16). */
    internal suspend fun reopen(id: Long, day: LocalDate) = dao.reopenAsOnlyPending(id, day)

    /** Como [get] pero sin efectos: un payload ilegible no se marca DISCARDED (eso lo decide quien aplica). */
    internal suspend fun peek(id: Long): Proposal? = dao.get(id)?.let { decode(it) }

    private suspend fun toModel(e: ProposalEntity): Proposal? {
        val model = decode(e)
        if (model == null && runCatching { ProposalStatus.valueOf(e.status) }.getOrNull() == ProposalStatus.PENDING) {
            dao.setStatus(e.id, ProposalStatus.DISCARDED.name)
        }
        return model
    }

    private fun decode(e: ProposalEntity): Proposal? {
        val body = ProposalCodec.decode(e.payload) ?: return null
        val status = runCatching { ProposalStatus.valueOf(e.status) }.getOrNull() ?: return null
        return Proposal(
            id = e.id, day = e.day, createdAt = e.createdAt, request = e.request, summary = e.summary, ops = body.ops,
            rejected = body.rejected, preview = body.preview, totals = body.totals, status = status,
        )
    }
}
