package app.brujula.planning

import app.brujula.data.db.ProposalDao
import app.brujula.data.db.ProposalEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime

/** Propuestas guardadas (D1): sobreviven a la muerte del proceso; una sola PENDING por día. */
class ProposalRepository(private val dao: ProposalDao) {
    /** Guarda [p] como la pendiente de su día; las otras pendientes de ese día pasan a SUPERSEDED (una transacción). */
    suspend fun savePending(p: Proposal): Long = dao.insertPending(
        ProposalEntity(
            day = p.day, createdAt = p.createdAt, status = ProposalStatus.PENDING.name, request = p.request, summary = p.summary,
            payload = ProposalCodec.encode(p),
        ),
    )

    /** Un payload ilegible no se puede mostrar ni aplicar: se marca DISCARDED y se devuelve `null`. */
    suspend fun get(id: Long): Proposal? = dao.get(id)?.let { toModel(it) }

    suspend fun pendingFor(day: LocalDate): Proposal? = dao.latestByStatus(day, ProposalStatus.PENDING.name)?.let { toModel(it) }

    suspend fun discard(id: Long) = dao.setStatus(id, ProposalStatus.DISCARDED.name)

    fun observeAcceptedDays(): Flow<Set<LocalDate>> = dao.observeAcceptedDays().map { it.toSet() }

    suspend fun acceptedDays(): Set<LocalDate> = dao.acceptedDays().toSet()

    fun observeAcceptedIds(): Flow<Set<Long>> = dao.observeAcceptedIds().map { it.toSet() }

    /** Datos para deshacer de una propuesta aceptada (JSON de [ProposalCodec.encodeUndo]). */
    internal suspend fun undoOf(id: Long): String? = dao.get(id)?.undo

    internal suspend fun markAccepted(id: Long, undo: String, at: LocalDateTime) = dao.markAccepted(id, undo, at)

    /** Tras deshacer: vuelve a PENDING (y es la pendiente de su día) para poder aceptarla otra vez (D16). */
    internal suspend fun reopen(id: Long, day: LocalDate) = dao.reopenAsOnlyPending(id, day)

    private suspend fun toModel(e: ProposalEntity): Proposal? {
        val body = ProposalCodec.decode(e.payload)
        val status = runCatching { ProposalStatus.valueOf(e.status) }.getOrNull()
        if (body == null || status == null) {
            dao.setStatus(e.id, ProposalStatus.DISCARDED.name)
            return null
        }
        return Proposal(
            id = e.id, day = e.day, createdAt = e.createdAt, request = e.request, summary = e.summary, ops = body.ops,
            rejected = body.rejected, preview = body.preview, totals = body.totals, status = status,
        )
    }
}
