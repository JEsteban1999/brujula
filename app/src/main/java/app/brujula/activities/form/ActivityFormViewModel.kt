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
import app.brujula.calendar.CalendarSource
import app.brujula.calendar.ExternalEvent
import app.brujula.calendar.NoCalendarSource
import app.brujula.core.time.Clock
import app.brujula.core.time.TimeSpan
import app.brujula.reminders.ReminderResync
import app.brujula.routine.logicalDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
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
    /** Evento importado (solo lectura) con el que choca; solo si no choca con una actividad propia. */
    val externalConflict: ExternalEvent? = null,
    val titleError: String? = null,
    val isEdit: Boolean = false,
    val saved: Boolean = false,
    /** Un guardado en curso: ignora toques repetidos. */
    val saving: Boolean = false,
    /** En edición, falso hasta que la actividad termina de cargar. */
    val ready: Boolean = true,
) {
    val end: LocalTime get() = start.plusMinutes(durationMin.toLong())

    /** Texto del choque, o null si la hora esta libre. */
    val conflictMessage: String?
        get() = conflict?.let { "Choca con ${it.activity.title}." }
            ?: externalConflict?.let { "Choca con ${it.title.ifBlank { "un evento sin título" }} (${it.source})." }
}

const val REMIND_BEFORE_MIN = 15
const val MIN_DURATION = 1
const val MAX_DURATION = 720
private val LOGICAL_DAY_START: LocalTime = LocalTime.of(4, 0)

class ActivityFormViewModel(
    activityId: Long?,
    prefillStart: LocalDateTime?,
    private val clock: Clock,
    private val activityRepository: ActivityRepository,
    private val resync: ReminderResync = ReminderResync { },
    private val calendarSource: CalendarSource = NoCalendarSource,
    selectedCalendarIds: Flow<Set<Long>?> = flowOf(null),
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    /** "Hoy" del usuario: el día lógico (cambia a las 04:00). */
    val today: LocalDate get() = logicalDay(clock.now())

    private val scope = scopeOverride ?: viewModelScope
    private var editingId: Long? = null
    private var all: List<Activity> = emptyList()
    private var external: List<ExternalEvent> = emptyList()
    private var externalDate: LocalDate? = null
    private var selectedIds: Set<Long>? = null
    private var externalJob: Job? = null

    private val _state = MutableStateFlow(initialState(prefillStart).copy(ready = activityId == null))
    val state: StateFlow<ActivityFormState> = _state.asStateFlow()

    init {
        scope.launch {
            activityRepository.observeAll().collect { list ->
                all = list
                _state.update(::withConflict)
            }
        }
        scope.launch {
            selectedCalendarIds.collect { ids ->
                selectedIds = ids
                reloadExternal(_state.value.date)
            }
        }
        if (activityId != null) {
            scope.launch {
                val a = activityRepository.get(activityId)
                if (a == null) {
                    _state.update { it.copy(ready = true) }
                    return@launch
                }
                editingId = a.id
                _state.update {
                    withConflict(
                        it.copy(
                            title = a.title, type = a.type, date = a.date, dateChoice = choiceFor(a.date, a.start),
                            start = a.start, ready = true, durationMin = a.durationMin.coerceIn(MIN_DURATION, MAX_DURATION),
                            repeat = a.repeat, remind = a.remindBeforeMin != null,
                            copyToGoogle = a.copyToGoogle, copyToGoogleTouched = true, isEdit = true,
                        ),
                    )
                }
                if (a.date != externalDate) reloadExternal(a.date)
            }
        }
    }

    private fun initialState(prefill: LocalDateTime?): ActivityFormState {
        val base = prefill ?: ceilToQuarter(clock.now())
        return ActivityFormState(
            date = base.toLocalDate(), dateChoice = choiceFor(base.toLocalDate(), base.toLocalTime()),
            start = base.toLocalTime(),
        )
    }

    private fun ceilToQuarter(t: LocalDateTime): LocalDateTime {
        val trimmed = t.withSecond(0).withNano(0)
        val extra = (15 - trimmed.minute % 15) % 15
        val rounded = trimmed.plusMinutes(extra.toLong())
        return if (rounded < t) rounded.plusMinutes(15) else rounded
    }

    /** Etiqueta según el día lógico del instante (a las 00:30 todavía es el "hoy" de ayer). */
    private fun choiceFor(date: LocalDate, start: LocalTime): DateChoice {
        val logical = logicalDay(date.atTime(start))
        return when (logical) {
            today -> DateChoice.TODAY
            today.plusDays(1) -> DateChoice.TOMORROW
            else -> DateChoice.OTHER
        }
    }

    /** Fecha de calendario real de un día lógico: de 00:00 a 03:59 cae al día natural siguiente. */
    private fun calendarDate(logical: LocalDate, start: LocalTime): LocalDate =
        if (start < LOGICAL_DAY_START) logical.plusDays(1) else logical

    /** Recalcula el choque con lo de ayer, hoy y mañana del día candidato (por lo que cruza la medianoche). */
    private fun withConflict(s: ActivityFormState): ActivityFormState {
        val begin = s.date.atTime(s.start)
        val candidate = TimeSpan(begin, begin.plusMinutes(s.durationMin.toLong()))
        val busy = (-1L..1L).flatMap { occurrencesOn(s.date.plusDays(it), all, emptySet()) }
        val own = findConflict(candidate, busy, editingId)
        val outside = if (own == null) external.firstOrNull { !it.allDay && it.span.overlaps(candidate) } else null
        return s.copy(conflict = own, externalConflict = outside)
    }

    /** Eventos importados de día−1/día/día+1 de [date]; la consulta al proveedor va fuera del hilo principal. */
    private fun reloadExternal(date: LocalDate) {
        externalJob?.cancel()
        externalJob = scope.launch {
            val ids = selectedIds
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    if (ids.isNullOrEmpty() || !calendarSource.hasReadPermission()) emptyList()
                    else calendarSource.events(ids, date.minusDays(1).atStartOfDay(), date.plusDays(2).atStartOfDay())
                }.getOrDefault(emptyList())
            }
            external = loaded
            externalDate = date
            _state.update(::withConflict)
        }
    }

    private fun edit(block: (ActivityFormState) -> ActivityFormState) {
        _state.update { withConflict(block(it).copy(saved = false)) }
        val date = _state.value.date
        if (date != externalDate) reloadExternal(date)
    }

    fun setTitle(s: String) = edit { it.copy(title = s, titleError = null) }

    fun setType(t: ActivityType) = edit {
        it.copy(
            type = t,
            copyToGoogle = if (it.copyToGoogleTouched) it.copyToGoogle else t == ActivityType.APPOINTMENT,
        )
    }

    fun setDateChoice(c: DateChoice) = edit {
        withChoice(it, c)
    }

    private fun withChoice(s: ActivityFormState, c: DateChoice): ActivityFormState = when (c) {
        DateChoice.TODAY -> s.copy(dateChoice = c, date = calendarDate(today, s.start))
        DateChoice.TOMORROW -> s.copy(dateChoice = c, date = calendarDate(today.plusDays(1), s.start))
        DateChoice.OTHER -> s.copy(dateChoice = c)
    }

    fun setDate(d: LocalDate) = edit { it.copy(date = d, dateChoice = choiceFor(d, it.start)) }

    fun shiftStart(minutes: Int) = edit {
        val moved = it.copy(start = it.start.plusMinutes(minutes.toLong()))
        // Al cruzar las 04:00 el día lógico cambia: se mantiene el elegido y se ajusta la fecha real.
        if (it.dateChoice == DateChoice.OTHER) moved else withChoice(moved, it.dateChoice)
    }

    fun setDuration(min: Int) = edit { it.copy(durationMin = min.coerceIn(MIN_DURATION, MAX_DURATION)) }

    fun setRepeat(r: Repeat) = edit { it.copy(repeat = r) }

    fun setRemind(on: Boolean) = edit { it.copy(remind = on) }

    fun setCopyToGoogle(on: Boolean) = edit { it.copy(copyToGoogle = on, copyToGoogleTouched = true) }

    fun save() {
        val s = _state.value
        if (s.saving || !s.ready) return
        val title = s.title.trim()
        if (title.isEmpty()) {
            _state.update { it.copy(titleError = "Escribe qué vas a hacer") }
            return
        }
        _state.update { it.copy(saving = true) }
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
