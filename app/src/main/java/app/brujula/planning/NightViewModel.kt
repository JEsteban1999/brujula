package app.brujula.planning

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.brujula.activities.ActivityOrigin
import app.brujula.core.time.Clock
import app.brujula.core.time.formatMinutes
import app.brujula.core.time.formatTime
import app.brujula.reminders.ReminderResync
import app.brujula.routine.RoutineRepository
import app.brujula.routine.logicalDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** Una fila de "Mañana ya tienes": [timeLabel] es la hora ("15:00") y [sourceLabel] el origen (Outlook, "lo dictaste", tipo). */
data class NightItem(val title: String, val timeLabel: String, val sourceLabel: String)

data class NightUiState(
    /** "10:45 pm · Buenas noches". */
    val timeLabel: String,
    val today: DaySummary,
    val tomorrow: List<NightItem>,
    val bedtime: BedtimeSuggestion,
    val reminderOn: Boolean,
    /** Falso si la hora del aviso ya pasó: no hay nada que encender. */
    val reminderVisible: Boolean,
) {
    /** "Para dormir 8 h, acuéstate a las 0:15". */
    val bedtimeText: String
        get() = "Para dormir ${formatMinutes(bedtime.sleepMinutes)}, acuéstate a las ${formatTime(bedtime.bedtime.toLocalTime())}"

    /** "Avisarme a las 23:45". */
    val reminderText: String get() = "Avisarme a las ${formatTime(bedtime.remindAt.toLocalTime())}"
}

/** Saludo según la hora: noches de 19:00 a 03:59, tardes de 12:00 a 18:59, el resto "Buenos días". */
internal fun greeting(t: LocalTime): String = when {
    t >= LocalTime.of(19, 0) || t < LocalTime.of(4, 0) -> "Buenas noches"
    t >= LocalTime.NOON -> "Buenas tardes"
    else -> "Buenos días"
}

/** "10:45 pm". */
internal fun formatClock12(t: LocalTime): String {
    val h = if (t.hour % 12 == 0) 12 else t.hour % 12
    return "%d:%02d %s".format(h, t.minute, if (t.hour < 12) "am" else "pm")
}

/**
 * "¿Armamos mañana?": cómo fue hoy, qué hay mañana y la hora de dormir (D12) con su aviso. Todo cuenta por día lógico:
 * a las 00:30 "hoy" sigue siendo el día que empezó ayer.
 */
class NightViewModel(
    private val clock: Clock,
    private val snapshots: DaySnapshotProvider,
    private val routineRepository: RoutineRepository,
    private val resync: ReminderResync,
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow<NightUiState?>(null)

    /** `null` mientras carga. */
    val state: StateFlow<NightUiState?> = _state.asStateFlow()

    /** Día lógico cuya noche se muestra; fijo al abrir la pantalla. */
    val today: LocalDate = logicalDay(clock.now())
    val tomorrow: LocalDate = today.plusDays(1)

    private val _failed = MutableStateFlow(false)

    /** Verdadero si no se pudo armar la pantalla (agenda ilegible); la pantalla ofrece reintentar. */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private var loading: Job = startLoading()

    private fun startLoading(): Job = scope.launch {
        _failed.value = false
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("NightViewModel", "No se pudo armar la pantalla de la noche", e)
            _failed.value = true
        }
    }

    /** Vuelve a armar la pantalla tras un fallo. */
    fun retry(): Job {
        if (loading.isActive) return loading
        loading = startLoading()
        return loading
    }

    private suspend fun load() {
        val now = clock.now()
        val routine = routineRepository.routine.first()
        val todaySnap = snapshots.snapshot(today)
        val tomorrowSnap = snapshots.snapshot(tomorrow)
        // La misma función que usan los avisos y la confirmación del plan: lo que se ve es lo que va a sonar.
        val bedtime = bedtimeFor(today, routine, tomorrowSnap)
        _state.value = NightUiState(
            timeLabel = "${formatClock12(now.toLocalTime())} · ${greeting(now.toLocalTime())}",
            today = todaySnap.summary,
            tomorrow = items(tomorrowSnap),
            bedtime = bedtime,
            reminderOn = routineRepository.bedtimeReminderOn.first(),
            reminderVisible = bedtime.remindAt > now,
        )
    }

    private fun items(s: DaySnapshot): List<NightItem> {
        val own = s.own.map { o ->
            val source = if (o.activity.origin == ActivityOrigin.DICTATION) "lo dictaste" else o.activity.type.labelEs.lowercase()
            Triple(o.span.start, o.activity.title, source)
        }
        val external = s.external.map { Triple(it.event.span.start, it.event.title, it.event.source) }
        return (own + external)
            .filter { logicalDay(it.first) == s.day }
            .sortedBy { it.first }
            .map { (start, title, source) -> NightItem(title, formatTime(start.toLocalTime()), source) }
    }

    /** Enciende o apaga "Aviso para dormir" (la misma preferencia de Tu rutina): se guarda y se reprograman los avisos. */
    fun setReminder(on: Boolean): Job = scope.launch {
        loading.join()
        _state.value = _state.value?.copy(reminderOn = on)
        // Guardar y reprogramar terminan aunque se cierre la pantalla.
        withContext(NonCancellable) {
            routineRepository.setBedtimeReminder(on)
            runCatching { resync.resync() }
        }
    }
}
