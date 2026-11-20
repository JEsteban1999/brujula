package app.brujula.suggestions

import app.brujula.claude.ClaudeGateway
import app.brujula.claude.DictationInterpreter
import app.brujula.claude.GatewayResult
import app.brujula.claude.SuggestionItem
import app.brujula.habits.FasterStretch
import app.brujula.habits.Habit
import app.brujula.habits.HabitSchedule
import app.brujula.habits.HabitWindow
import kotlinx.coroutines.CancellationException
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

sealed interface SuggestOutcome {
    /** La primera sugerencia válida, o null si Claude no propuso nada válido. */
    data class Ok(val suggestion: HabitSuggestion?) : SuggestOutcome
    data object NotEnoughData : SuggestOutcome
    data object NoKey : SuggestOutcome
    data object InvalidKey : SuggestOutcome
    data object Offline : SuggestOutcome
    /** Haiku 5.5 no tiene respaldo en el servidor: un rechazo es "sin sugerencias" ([HabitSuggester.NONE]). */
    data class Refused(val message: String) : SuggestOutcome
    data class Failed(val message: String, val retryable: Boolean) : SuggestOutcome
}

/**
 * Pide a Claude Haiku una sugerencia de hábito sobre conteos agregados y la valida. No aplica nada: guardar y aceptar es de
 * la Task 5. Sin suficientes días con registros o sin red no llama.
 */
class HabitSuggester(
    private val gateway: ClaudeGateway,
    private val contextProvider: suspend () -> SuggestionContext,
    private val isOnline: suspend () -> Boolean = { true },
) {
    suspend fun suggest(minDaysWithLogs: Int): SuggestOutcome {
        val ctx = try {
            contextProvider()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SuggestOutcome.Failed(DictationInterpreter.UNAVAILABLE, retryable = true)
        }
        if (ctx.daysWithLogs < minDaysWithLogs) return SuggestOutcome.NotEnoughData
        if (!isOnline()) return SuggestOutcome.Offline
        return when (val r = gateway.suggest(SuggestionPrompt.system(), SuggestionPrompt.user(ctx))) {
            is GatewayResult.Ok -> SuggestOutcome.Ok(r.value.suggestions.firstNotNullOfOrNull { validate(it, ctx) })
            GatewayResult.Refused -> SuggestOutcome.Refused(NONE)
            GatewayResult.NoKey -> SuggestOutcome.NoKey
            GatewayResult.InvalidKey -> SuggestOutcome.InvalidKey
            GatewayResult.Network -> SuggestOutcome.Offline
            GatewayResult.Timeout -> SuggestOutcome.Failed(DictationInterpreter.UNAVAILABLE, retryable = true)
            is GatewayResult.Error -> SuggestOutcome.Failed(DictationInterpreter.UNAVAILABLE, retryable = r.retryable)
        }
    }

    companion object {
        /** Días con registros (de los últimos 14) para la corrida automática y para la pedida a mano (D12). */
        const val MIN_DAYS_AUTO = 7
        const val MIN_DAYS_ON_DEMAND = 3

        const val NOT_ENOUGH = "Necesito al menos 3 días de registros para sugerirte algo."
        const val OFFLINE = "Sin conexión. Claude necesita internet para sugerirte cambios."
        const val NO_KEY = "Para pedir sugerencias, agrega tu clave de Claude en Tu rutina."
        const val NONE = "Por ahora no tengo sugerencias. Vas bien."
    }
}

private const val MIN_SUGGESTED_INTERVAL = 15
private const val MAX_SUGGESTED_INTERVAL = 240
private const val MAX_SUGGESTED_TIMES = 12
private const val MAX_SUGGESTED_TARGET = 20
private const val MAX_MESSAGE = 300
private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm").withResolverStyle(ResolverStyle.STRICT)

/**
 * Valida una sugerencia de Claude contra el contexto (D13): hábito existente y activo, un solo tipo de cambio dentro de sus
 * rangos, distinto de lo actual y no rechazado antes. null si algo no cuadra.
 */
fun validate(item: SuggestionItem, ctx: SuggestionContext): HabitSuggestion? {
    val stat = ctx.stats.firstOrNull { it.ref == item.habitRef.trim() } ?: return null
    val habit = stat.habit
    val message = item.message.trim().take(MAX_MESSAGE).trim()
    if (message.isEmpty()) return null
    val kind = when (item.change.trim().lowercase()) {
        "interval" -> SuggestionKind.INTERVAL
        "times" -> SuggestionKind.TIMES
        "target" -> SuggestionKind.TARGET
        else -> return null
    }
    if ((habit.id to kind) in ctx.rejected) return null
    val before = HabitChange(habit.schedule, habit.dailyTarget)
    val after = when (kind) {
        SuggestionKind.INTERVAL -> {
            val current = habit.schedule as? HabitSchedule.Every ?: return null
            val min = item.intervalMin?.takeIf { it in MIN_SUGGESTED_INTERVAL..MAX_SUGGESTED_INTERVAL } ?: return null
            val fromText = item.fromTime?.trim()?.takeIf { it.isNotEmpty() }
            if (fromText == null) {
                HabitChange(HabitSchedule.Every(min, current.faster), habit.dailyTarget)
            } else {
                if (habit.window != HabitWindow.AWAKE) return null
                val from = parseHm(fromText) ?: return null
                if (!inWorkdayAwake(from, ctx)) return null
                // Un tramo "más seguido" tiene que ser de verdad más seguido que el intervalo base.
                if (min >= current.intervalMin) return null
                HabitChange(HabitSchedule.Every(current.intervalMin, FasterStretch(from, min)), habit.dailyTarget)
            }
        }
        SuggestionKind.TIMES -> {
            if (habit.schedule !is HabitSchedule.AtTimes) return null
            val raw = item.times ?: return null
            val parsed = raw.map { parseHm(it.trim()) ?: return null }.distinct().sorted()
            if (parsed.size !in 1..MAX_SUGGESTED_TIMES) return null
            if (parsed.any { !inHabitWindow(it, habit, ctx) }) return null
            // A horas fijas la meta es una por cada hora (como en el formulario).
            HabitChange(HabitSchedule.AtTimes(parsed), parsed.size)
        }
        SuggestionKind.TARGET -> {
            // En horas fijas y antes de dormir la meta la fija el horario: solo cambia en "cada N min".
            if (habit.schedule !is HabitSchedule.Every) return null
            val n = item.dailyTarget?.takeIf { it in 1..MAX_SUGGESTED_TARGET } ?: return null
            HabitChange(habit.schedule, n)
        }
    }
    if (after == before) return null
    return HabitSuggestion(
        habitId = habit.id, habitName = habit.name, kind = kind, message = message,
        before = before, after = after, createdAt = ctx.now,
    )
}

private fun parseHm(text: String): LocalTime? = runCatching { LocalTime.parse(text, HM) }.getOrNull()

/** [t] cae en la ventana del hábito de un día laboral: la vigilia (AWAKE) o los bloques de trabajo dentro de la vigilia (WORK). */
private fun inHabitWindow(t: LocalTime, habit: Habit, ctx: SuggestionContext): Boolean {
    if (!inWorkdayAwake(t, ctx)) return false
    return habit.window != HabitWindow.WORK || ctx.routine.workBlocks.any { (from, to) -> t >= from && t < to }
}

/** [t] cae dentro de la vigilia de un día laboral (de levantarse a acostarse; acostarse puede ser pasada la medianoche). */
private fun inWorkdayAwake(t: LocalTime, ctx: SuggestionContext): Boolean {
    val wake = ctx.routine.wake
    val bed = ctx.routine.bed
    return if (bed <= wake) t >= wake || t < bed else t >= wake && t < bed
}
