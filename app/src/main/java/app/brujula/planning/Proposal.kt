package app.brujula.planning

import app.brujula.activities.Activity
import app.brujula.core.time.TimeSpan
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Un cambio ya validado de una propuesta. Lleva todo lo que necesita `Aceptar plan` (Task 4) y el "antes" para deshacer:
 * - [Create]: actividad nueva de una sola vez, origen PLAN, sin copia a Google (D11).
 * - [Move]: [oneOff] → se edita la fila con la fecha y hora de [to]; recurrente → override de la ocurrencia [occurrenceDay]
 *   (fecha de la serie) con el inicio y la duración de [to]. [from] es la ocurrencia tal como estaba al proponer.
 * - [Skip]: [oneOff] → se borra la fila; recurrente → override `skipped` de [occurrenceDay].
 */
sealed interface PlanOp {
    data class Create(val activity: Activity) : PlanOp

    data class Move(
        val ref: String,
        val activityId: Long,
        val occurrenceDay: LocalDate,
        val title: String,
        val from: TimeSpan,
        val to: TimeSpan,
        val oneOff: Boolean,
    ) : PlanOp

    data class Skip(
        val ref: String,
        val activityId: Long,
        val occurrenceDay: LocalDate,
        val title: String,
        val from: TimeSpan,
        val oneOff: Boolean,
    ) : PlanOp
}

/** Etiquetas de la vista previa (D6). */
enum class BlockTag { NUEVO, MOVIDO, QUITADO, FIJO, LO_DICTASTE, NONE }

/** Una fila de la vista previa: [sub] es el texto de la segunda línea ("8:15 – 12:00 · antes 18:30"). */
data class PreviewBlock(val title: String, val span: TimeSpan, val tag: BlockTag, val sub: String)

data class PlanTotals(val workMinutes: Int, val exerciseMinutes: Int, val sleepMinutes: Int, val bedtime: LocalDateTime)

enum class ProposalStatus { PENDING, ACCEPTED, SUPERSEDED, DISCARDED }

/** Lo que Claude propuso para [day], ya validado en la app. Nada se aplica sin `Aceptar plan` (regla C). */
data class Proposal(
    val id: Long = 0,
    val day: LocalDate,
    val createdAt: LocalDateTime,
    val request: String?,
    val summary: String,
    val ops: List<PlanOp>,
    /** Frases para `No pude aplicar:`, una por cambio descartado. */
    val rejected: List<String>,
    val preview: List<PreviewBlock>,
    val totals: PlanTotals,
    val status: ProposalStatus = ProposalStatus.PENDING,
)
