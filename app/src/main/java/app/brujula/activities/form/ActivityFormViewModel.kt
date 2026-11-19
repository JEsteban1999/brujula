package app.brujula.activities.form

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.ActivityType
import app.brujula.activities.Occurrence
import app.brujula.activities.Repeat
import app.brujula.activities.findConflict
import app.brujula.activities.occurrencesOn
import app.brujula.core.time.Clock
import app.brujula.core.time.TimeSpan
import app.brujula.reminders.ReminderResync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class DateChoice { TODAY, TOMORROW, OTHER }

data class ActivityFormState(
    val title: String = "",
    val type: ActivityType = ActivityType.PERSONAL,
    val dateChoice: DateChoice = DateChoice.TODAY,
    val date: LocalDate,
    val start: LocalTime,
    val durationMin: Int = 60,
    val repeat: Repeat = Repeat.NONE,
    val remind: Boolean = true,
    val copyToGoogle: Boolean = false,
    val copyToGoogleTouched: Boolean = false,
    val conflict: Occurrence? = null,
    val titleError: String? = null,
    val isEdit: Boolean = false,
    val saved: Boolean = false,
) {
    val end: LocalTime get() = start.plusMinutes(durationMin.toLong())
}

const val REMIND_BEFORE_MIN = 15
const val MIN_DURATION = 1
const val MAX_DURATION = 720

class ActivityFormViewModel(
    activityId: Long?,
    prefillStart: LocalDateTime?,
    private val clock: Clock,
    private val activityRepository: ActivityRepository,
    private val resync: ReminderResync = ReminderResync { },
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    val today: LocalDate get() = clock.today()

    private val scope = scopeOverride ?: viewModelScope
    private var editingId: Long? = null
    private var all: List<Activity> = emptyList()

    private val _state = MutableStateFlow(initialState(prefillStart))
    val state: StateFlow<ActivityFormState> = _state.asStateFlow()

    init {
        scope.launch {
            activityRepository.observeAll().collect { list ->
                all = list
                _state.update(::withConflict)
            }
        }
        if (activityId != null) {
            scope.launch {
                val a = activityRepository.get(activityId) ?: return@launch
                editingId = a.id
                _state.update {
                    withConflict(
                        it.copy(
                            title = a.title, type = a.type, date = a.date, dateChoice = choiceFor(a.date),
                            start = a.start, durationMin = a.durationMin.coerceIn(MIN_DURATION, MAX_DURATION),
                            repeat = a.repeat, remind = a.remindBeforeMin != null,
                            copyToGoogle = a.copyToGoogle, copyToGoogleTouched = true, isEdit = true,
                        ),
                    )
                }
            }
        }
    }

    private fun initialState(prefill: LocalDateTime?): ActivityFormState {
        val base = prefill ?: ceilToQuarter(clock.now())
        return ActivityFormState(
            date = base.toLocalDate(), dateChoice = choiceFor(base.toLocalDate()), start = base.toLocalTime(),
        )
    }

    private fun ceilToQuarter(t: LocalDateTime): LocalDateTime {
        val trimmed = t.withSecond(0).withNano(0)
        val extra = (15 - trimmed.minute % 15) % 15
        val rounded = trimmed.plusMinutes(extra.toLong())
        return if (rounded < t) rounded.plusMinutes(15) else rounded
    }

    private fun choiceFor(date: LocalDate): DateChoice {
        val today = clock.today()
        return when (date) {
            today -> DateChoice.TODAY
            today.plusDays(1) -> DateChoice.TOMORROW
            else -> DateChoice.OTHER
        }
    }

    /** Recalcula el choque con lo de ayer, hoy y mañana del día candidato (por lo que cruza la medianoche). */
    private fun withConflict(s: ActivityFormState): ActivityFormState {
        val begin = s.date.atTime(s.start)
        val candidate = TimeSpan(begin, begin.plusMinutes(s.durationMin.toLong()))
        val busy = (-1L..1L).flatMap { occurrencesOn(s.date.plusDays(it), all, emptySet()) }
        return s.copy(conflict = findConflict(candidate, busy, editingId))
    }

    private fun edit(block: (ActivityFormState) -> ActivityFormState) {
        _state.update { withConflict(block(it).copy(saved = false)) }
    }

    fun setTitle(s: String) = edit { it.copy(title = s, titleError = null) }

    fun setType(t: ActivityType) = edit {
        it.copy(
            type = t,
            copyToGoogle = if (it.copyToGoogleTouched) it.copyToGoogle else t == ActivityType.APPOINTMENT,
        )
    }

    fun setDateChoice(c: DateChoice) = edit {
        val today = clock.today()
        when (c) {
            DateChoice.TODAY -> it.copy(dateChoice = c, date = today)
            DateChoice.TOMORROW -> it.copy(dateChoice = c, date = today.plusDays(1))
            DateChoice.OTHER -> it.copy(dateChoice = c)
        }
    }

    fun setDate(d: LocalDate) = edit { it.copy(date = d, dateChoice = choiceFor(d)) }

    fun shiftStart(minutes: Int) = edit { it.copy(start = it.start.plusMinutes(minutes.toLong())) }

    fun setDuration(min: Int) = edit { it.copy(durationMin = min.coerceIn(MIN_DURATION, MAX_DURATION)) }

    fun setRepeat(r: Repeat) = edit { it.copy(repeat = r) }

    fun setRemind(on: Boolean) = edit { it.copy(remind = on) }

    fun setCopyToGoogle(on: Boolean) = edit { it.copy(copyToGoogle = on, copyToGoogleTouched = true) }

    fun save() {
        val s = _state.value
        val title = s.title.trim()
        if (title.isEmpty()) {
            _state.update { it.copy(titleError = "Escribe qué vas a hacer") }
            return
        }
        scope.launch {
            activityRepository.upsert(
                Activity(
                    id = editingId ?: 0L, title = title, type = s.type, date = s.date, start = s.start,
                    durationMin = s.durationMin.coerceIn(MIN_DURATION, MAX_DURATION), repeat = s.repeat,
                    remindBeforeMin = if (s.remind) REMIND_BEFORE_MIN else null, copyToGoogle = s.copyToGoogle,
                ),
            )
            runCatching { resync.resync() }
            _state.update { it.copy(saved = true) }
        }
    }

    fun delete() {
        val id = editingId ?: return
        scope.launch {
            activityRepository.delete(id)
            runCatching { resync.resync() }
            _state.update { it.copy(saved = true) }
        }
    }
}
