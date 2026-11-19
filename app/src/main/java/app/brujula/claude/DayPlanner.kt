package app.brujula.claude

import app.brujula.planning.PlanOp
import app.brujula.planning.PlanValidator
import app.brujula.planning.Proposal
import app.brujula.core.time.Clock
import app.brujula.core.time.SystemClock
import app.brujula.routine.logicalDay
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

sealed interface PlanOutcome {
    data class Ok(val proposal: Proposal) : PlanOutcome
    data object NoKey : PlanOutcome
    /** Hay clave guardada pero el API la rechaza (401/403). */
    data object InvalidKey : PlanOutcome
    data object Offline : PlanOutcome
    data class Refused(val message: String) : PlanOutcome
    data class Failed(val message: String, val retryable: Boolean) : PlanOutcome
}

/**
 * Arma una propuesta para un día: contexto → una llamada a Claude → validación local → vista previa y totales. No guarda ni
 * aplica nada (eso es `Aceptar plan`). Solo días de hoy (día lógico) a hoy + 6 (D10); fuera de eso no se llama a Claude.
 *
 * Un ajuste (D9) es una llamada nueva con los cambios de [Proposal.ops] de la propuesta anterior y el texto en `<ajuste>`.
 *
 * La llamada puede tardar hasta 90 s: la respuesta se valida con la hora de [clock] al recibirla (nada en el pasado si el día
 * es hoy, y la hora de dormir nunca antes de ahora), no con la del contexto.
 */
class DayPlanner(
    private val gateway: ClaudeGateway,
    private val contextProvider: suspend (LocalDate) -> PlanContext,
    private val isOnline: suspend () -> Boolean = { true },
    private val clock: Clock = SystemClock,
) {
    suspend fun propose(day: LocalDate, request: String?, previous: Proposal? = null, adjustment: String? = null): PlanOutcome {
        // D10 antes de leer nada: fuera de la ventana no se arma el contexto ni se llama a Claude.
        PlanValidator.dayProblem(day, logicalDay(clock.now()))?.let { return PlanOutcome.Failed(it, retryable = false) }
        val ctx = try {
            contextProvider(day)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PlanOutcome.Failed(CONTEXT_FAILED, retryable = true)
        }
        if (!isOnline()) return PlanOutcome.Offline

        // Un ajuste conserva el pedido original si no llega otro; una propuesta de otro día no sirve de base.
        val base = previous?.takeIf { it.day == day }
        val cleanRequest = request?.trim()?.takeIf { it.isNotEmpty() } ?: base?.request
        val user = PlanPrompt.user(ctx, cleanRequest, base?.ops?.map(::toChange), adjustment)
        return when (val r = gateway.plan(PlanPrompt.system(), user)) {
            is GatewayResult.Ok -> {
                // Lo mismo que se cargó antes de la llamada, pero con la hora de ahora.
                val now = maxOf(clock.now(), ctx.now)
                val current = ctx.copy(now = now, logicalToday = logicalDay(now))
                PlanValidator.dayProblem(current)?.let { return PlanOutcome.Failed(it, retryable = false) }
                val validated = PlanValidator.validate(r.value, current)
                PlanOutcome.Ok(
                    Proposal(
                        day = day,
                        createdAt = current.now,
                        request = cleanRequest,
                        summary = r.value.summary.trim().take(MAX_SUMMARY),
                        ops = validated.ops,
                        rejected = validated.rejected,
                        preview = PlanValidator.preview(current, validated.ops),
                        totals = PlanValidator.totals(current, validated.ops),
                    ),
                )
            }
            GatewayResult.Refused -> PlanOutcome.Refused(REFUSED)
            GatewayResult.NoKey -> PlanOutcome.NoKey
            GatewayResult.InvalidKey -> PlanOutcome.InvalidKey
            GatewayResult.Network -> PlanOutcome.Failed(OFFLINE, retryable = true)
            GatewayResult.Timeout -> PlanOutcome.Failed(TOO_SLOW, retryable = true)
            is GatewayResult.Error ->
                if (r.retryable) PlanOutcome.Failed(UNAVAILABLE, retryable = true)
                else if (r.config) PlanOutcome.Failed(CONFIG_ERROR, retryable = false)
                else PlanOutcome.Failed(REFUSED, retryable = false)
        }
    }

    /** Un cambio ya validado, como lo vería Claude en la propuesta anterior. */
    private fun toChange(op: PlanOp): PlanChange = when (op) {
        is PlanOp.Create -> PlanChange(
            op = "create", title = op.activity.title, activityType = op.activity.type.name, date = op.activity.date.toString(),
            start = hm(op.activity.start.hour, op.activity.start.minute), durationMin = op.activity.durationMin,
        )
        is PlanOp.Move -> PlanChange(
            op = "move", ref = op.ref, date = op.to.start.toLocalDate().toString(),
            start = hm(op.to.start.hour, op.to.start.minute), durationMin = op.to.minutes.toInt(),
        )
        is PlanOp.Skip -> PlanChange(op = "skip", ref = op.ref)
    }

    private fun hm(h: Int, m: Int) = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"

    companion object {
        const val MAX_SUMMARY = 400
        const val OFFLINE = "Sin conexión. Claude necesita internet para armar tu día."
        const val NO_KEY = "Para que Claude arme tu día, agrega tu clave en Tu rutina."
        const val OUT_OF_RANGE = PlanValidator.OUT_OF_RANGE
        const val PAST_DAY = PlanValidator.PAST_DAY
        const val INVALID_KEY = DictationInterpreter.INVALID_KEY
        const val REFUSED = "Claude no pudo armar tu día con eso. Prueba pidiéndolo de otra forma."
        const val UNAVAILABLE = DictationInterpreter.UNAVAILABLE
        const val TOO_SLOW = DictationInterpreter.TOO_SLOW
        const val CONFIG_ERROR = DictationInterpreter.CONFIG_ERROR
        const val CONTEXT_FAILED = "No pude leer tu agenda. Inténtalo de nuevo."
    }
}
