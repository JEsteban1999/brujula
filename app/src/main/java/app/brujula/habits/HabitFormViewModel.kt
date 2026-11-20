package app.brujula.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.brujula.reminders.ReminderResync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.brujula.routine.Routine
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

enum class HabitMode { EVERY, AT_TIMES, BEFORE_BED }

const val MIN_INTERVAL_MIN = 15
const val MAX_INTERVAL_MIN = 480
const val MAX_DAILY_TARGET = 99
const val MIN_BEFORE_BED_MIN = 15
const val MAX_BEFORE_BED_MIN = 180
private val REFERENCE_DAY: LocalDate = LocalDate.of(2000, 1, 3)

/** Meta de un hábito "cada N minutos": tantos avisos como caben en la vigilia de un día laboral de referencia. */
fun autoDailyTarget(intervalMin: Int, window: HabitWindow, routine: Routine): Int =
    Habit(name = "", icon = HabitIcon.GENERIC, schedule = HabitSchedule.Every(intervalMin), dailyTarget = 1, window = window)
        .timesFor(REFERENCE_DAY, routine).size.coerceIn(1, MAX_DAILY_TARGET)

data class HabitFormState(
    val name: String = "",
    val icon: HabitIcon = HabitIcon.GENERIC,
    val mode: HabitMode = HabitMode.EVERY,
    val intervalMin: Int = 90,
    /** Tramo "más seguido desde" (lo agrega una sugerencia aceptada): al editar se conserva tal cual; se puede quitar. */
    val faster: FasterStretch? = null,
    val times: List<LocalTime> = emptyList(),
    val dailyTarget: Int = 10,
    /** Falso mientras la meta sea la calculada; al tocarla el usuario deja de recalcularse. */
    val targetTouched: Boolean = false,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    /** Minutos antes de dormir para el modo "Antes de dormir". */
    val beforeBedMin: Int = 45,
    /** "Solo en horario de trabajo" (ventana WORK); no aplica a "Antes de dormir". */
    val workOnly: Boolean = false,
    val group: HabitGroup = HabitGroup.ESSENTIAL,
    val nameError: String? = null,
    val intervalError: String? = null,
    val timesError: String? = null,
    val daysError: String? = null,
    val isEdit: Boolean = false,
    val saved: Boolean = false,
    /** Un guardado en curso: ignora toques repetidos. */
    val saving: Boolean = false,
    /** En edición, falso hasta que el hábito termina de cargar. */
    val ready: Boolean = true,
    /** En edición, el hábito ya no existe: la pantalla se cierra sin guardar. */
    val notFound: Boolean = false,
)

class HabitFormViewModel(
    habitId: Long?,
    group: HabitGroup = HabitGroup.ESSENTIAL,
    private val habitRepository: HabitRepository,
    private val resync: ReminderResync = ReminderResync { },
    /** Rutina para calcular la meta por defecto de los habitos "cada cierto tiempo". */
    private val routineProvider: suspend () -> Routine = { Routine() },
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private var editing: Habit? = null
    private var routine = Routine()

    private val _state = MutableStateFlow(autoTarget(HabitFormState(ready = habitId == null, group = group)))
    val state: StateFlow<HabitFormState> = _state.asStateFlow()

    init {
        if (habitId == null) {
            scope.launch {
                routine = runCatching { routineProvider() }.getOrDefault(routine)
                _state.update { autoTarget(it) }
            }
        }
        if (habitId != null) {
            scope.launch {
                val h = habitRepository.observeHabits().first().firstOrNull { it.id == habitId }
                if (h == null) {
                    _state.update { it.copy(notFound = true) }
                    return@launch
                }
                editing = h
                _state.update {
                    it.copy(
                        name = h.name, icon = h.icon, dailyTarget = h.dailyTarget, targetTouched = true, days = h.days, isEdit = true, ready = true,
                        group = h.group, workOnly = h.window == HabitWindow.WORK,
                        mode = when (h.schedule) {
                            is HabitSchedule.AtTimes -> HabitMode.AT_TIMES
                            is HabitSchedule.BeforeBed -> HabitMode.BEFORE_BED
                            is HabitSchedule.Every -> HabitMode.EVERY
                        },
                        beforeBedMin = (h.schedule as? HabitSchedule.BeforeBed)?.minutes ?: it.beforeBedMin,
                        intervalMin = (h.schedule as? HabitSchedule.Every)?.intervalMin ?: it.intervalMin,
                        faster = (h.schedule as? HabitSchedule.Every)?.faster,
                        times = (h.schedule as? HabitSchedule.AtTimes)?.times ?: it.times,
                    )
                }
            }
        }
    }

    private fun edit(block: (HabitFormState) -> HabitFormState) {
        if (!_state.value.ready || _state.value.notFound) return
        _state.update { block(it).copy(saved = false) }
    }

    fun setName(v: String) = edit { it.copy(name = v, nameError = null) }
    fun setIcon(v: HabitIcon) = edit { it.copy(icon = v) }
    fun setMode(v: HabitMode) = edit {
        autoTarget(
            it.copy(
                mode = v, intervalError = null, timesError = null, workOnly = it.workOnly && v != HabitMode.BEFORE_BED,
                faster = it.faster.takeIf { _ -> v == HabitMode.EVERY },
            ),
        )
    }
    fun removeFaster() = edit { it.copy(faster = null) }
    fun setBeforeBedMin(v: Int) = edit { it.copy(beforeBedMin = v.coerceIn(MIN_BEFORE_BED_MIN, MAX_BEFORE_BED_MIN)) }
    fun setWorkOnly(v: Boolean) = edit { autoTarget(it.copy(workOnly = v && it.mode != HabitMode.BEFORE_BED)) }
    fun setIntervalMin(v: Int) = edit { autoTarget(it.copy(intervalMin = v, intervalError = null)) }
    fun setTimes(v: List<LocalTime>) = edit { it.copy(times = v, timesError = null) }
    fun addTime(t: LocalTime) = edit { it.copy(times = (it.times + t).distinct().sorted(), timesError = null) }
    fun removeTime(t: LocalTime) = edit { it.copy(times = it.times - t) }
    fun setDailyTarget(v: Int) = edit { it.copy(dailyTarget = v.coerceIn(1, MAX_DAILY_TARGET), targetTouched = true) }

    /** Meta calculada: tantos avisos como caben en la vigilia mientras el usuario no la toque. */
    private fun autoTarget(s: HabitFormState): HabitFormState {
        if (s.targetTouched || s.mode != HabitMode.EVERY || s.intervalMin !in MIN_INTERVAL_MIN..MAX_INTERVAL_MIN) return s
        return s.copy(dailyTarget = autoDailyTarget(s.intervalMin, if (s.workOnly) HabitWindow.WORK else HabitWindow.AWAKE, routine))
    }
    fun setDays(v: Set<DayOfWeek>) = edit { it.copy(days = v, daysError = null) }

    fun toggleDay(d: DayOfWeek) = edit {
        it.copy(days = if (d in it.days) it.days - d else it.days + d, daysError = null)
    }

    fun save() {
        val s = _state.value
        if (s.saving || !s.ready || s.notFound) return
        val name = s.name.trim()
        val nameError = if (name.isEmpty()) "Ponle un nombre al hábito" else null
        val intervalError = if (s.mode == HabitMode.EVERY && s.intervalMin !in MIN_INTERVAL_MIN..MAX_INTERVAL_MIN) {
            "El intervalo va de $MIN_INTERVAL_MIN a $MAX_INTERVAL_MIN minutos"
        } else {
            null
        }
        val timesError = if (s.mode == HabitMode.AT_TIMES && s.times.isEmpty()) "Agrega al menos una hora" else null
        val daysError = if (s.days.isEmpty()) "Elige al menos un día" else null
        if (listOf(nameError, intervalError, timesError, daysError).any { it != null }) {
            _state.update {
                it.copy(nameError = nameError, intervalError = intervalError, timesError = timesError, daysError = daysError)
            }
            return
        }
        _state.update {
            it.copy(saving = true, nameError = null, intervalError = null, timesError = null, daysError = null)
        }
        val beforeBed = s.mode == HabitMode.BEFORE_BED
        val schedule = when (s.mode) {
            // El tramo "más seguido desde" tiene que ser más rápido que la base; si ya no lo es, se quita.
            HabitMode.EVERY -> HabitSchedule.Every(s.intervalMin, s.faster?.takeIf { s.intervalMin > it.intervalMin })
            HabitMode.AT_TIMES -> HabitSchedule.AtTimes(s.times.distinct().sorted())
            HabitMode.BEFORE_BED -> HabitSchedule.BeforeBed(s.beforeBedMin)
        }
        // A horas fijas la meta es una por cada hora; antes de dormir, una vez al día.
        val target = when (s.mode) {
            HabitMode.AT_TIMES -> s.times.distinct().size
            HabitMode.BEFORE_BED -> 1
            HabitMode.EVERY -> s.dailyTarget
        }
        scope.launch {
            habitRepository.upsert(
                Habit(
                    id = editing?.id ?: 0L, name = name, icon = s.icon, schedule = schedule,
                    dailyTarget = target, days = s.days, active = editing?.active ?: true,
                    group = s.group, window = if (s.workOnly && !beforeBed) HabitWindow.WORK else HabitWindow.AWAKE,
                ),
            )
            runCatching { resync.resync() }
            _state.update { it.copy(saved = true) }
        }
    }

    fun delete() {
        val id = editing?.id ?: return
        if (_state.value.saving || _state.value.notFound) return
        _state.update { it.copy(saving = true) }
        scope.launch {
            habitRepository.delete(id)
            runCatching { resync.resync() }
            _state.update { it.copy(saved = true) }
        }
    }
}
