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
import java.time.DayOfWeek
import java.time.LocalTime

enum class HabitMode { EVERY, AT_TIMES }

const val MIN_INTERVAL_MIN = 15
const val MAX_INTERVAL_MIN = 480
const val MAX_DAILY_TARGET = 99

data class HabitFormState(
    val name: String = "",
    val icon: HabitIcon = HabitIcon.GENERIC,
    val mode: HabitMode = HabitMode.EVERY,
    val intervalMin: Int = 90,
    val times: List<LocalTime> = emptyList(),
    val dailyTarget: Int = 1,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
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
)

class HabitFormViewModel(
    habitId: Long?,
    private val habitRepository: HabitRepository,
    private val resync: ReminderResync = ReminderResync { },
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scopeOverride ?: viewModelScope
    private var editing: Habit? = null

    private val _state = MutableStateFlow(HabitFormState(ready = habitId == null))
    val state: StateFlow<HabitFormState> = _state.asStateFlow()

    init {
        if (habitId != null) {
            scope.launch {
                val h = habitRepository.observeHabits().first().firstOrNull { it.id == habitId }
                if (h == null) {
                    _state.update { it.copy(ready = true) }
                    return@launch
                }
                editing = h
                _state.update {
                    it.copy(
                        name = h.name, icon = h.icon, dailyTarget = h.dailyTarget, days = h.days, isEdit = true, ready = true,
                        mode = if (h.schedule is HabitSchedule.AtTimes) HabitMode.AT_TIMES else HabitMode.EVERY,
                        intervalMin = (h.schedule as? HabitSchedule.Every)?.intervalMin ?: it.intervalMin,
                        times = (h.schedule as? HabitSchedule.AtTimes)?.times ?: it.times,
                    )
                }
            }
        }
    }

    private fun edit(block: (HabitFormState) -> HabitFormState) {
        _state.update { block(it).copy(saved = false) }
    }

    fun setName(v: String) = edit { it.copy(name = v, nameError = null) }
    fun setIcon(v: HabitIcon) = edit { it.copy(icon = v) }
    fun setMode(v: HabitMode) = edit { it.copy(mode = v, intervalError = null, timesError = null) }
    fun setIntervalMin(v: Int) = edit { it.copy(intervalMin = v, intervalError = null) }
    fun setTimes(v: List<LocalTime>) = edit { it.copy(times = v, timesError = null) }
    fun addTime(t: LocalTime) = edit { it.copy(times = (it.times + t).distinct().sorted(), timesError = null) }
    fun removeTime(t: LocalTime) = edit { it.copy(times = it.times - t) }
    fun setDailyTarget(v: Int) = edit { it.copy(dailyTarget = v.coerceIn(1, MAX_DAILY_TARGET)) }
    fun setDays(v: Set<DayOfWeek>) = edit { it.copy(days = v, daysError = null) }

    fun toggleDay(d: DayOfWeek) = edit {
        it.copy(days = if (d in it.days) it.days - d else it.days + d, daysError = null)
    }

    fun save() {
        val s = _state.value
        if (s.saving || !s.ready) return
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
        val schedule = when (s.mode) {
            HabitMode.EVERY -> HabitSchedule.Every(s.intervalMin)
            HabitMode.AT_TIMES -> HabitSchedule.AtTimes(s.times.distinct().sorted())
        }
        scope.launch {
            habitRepository.upsert(
                Habit(
                    id = editing?.id ?: 0L, name = name, icon = s.icon, schedule = schedule,
                    dailyTarget = s.dailyTarget, days = s.days, active = editing?.active ?: true,
                ),
            )
            runCatching { resync.resync() }
            _state.update { it.copy(saved = true) }
        }
    }

    fun delete() {
        val id = editing?.id ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        scope.launch {
            habitRepository.delete(id)
            runCatching { resync.resync() }
            _state.update { it.copy(saved = true) }
        }
    }
}
