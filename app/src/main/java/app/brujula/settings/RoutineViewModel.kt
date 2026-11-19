package app.brujula.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.brujula.calendar.CalendarSource
import app.brujula.calendar.DeviceCalendar
import app.brujula.calendar.sourceLabel
import app.brujula.core.theme.ThemeMode
import app.brujula.reminders.ReminderResync
import app.brujula.routine.Routine
import app.brujula.routine.RoutineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch

data class CalendarItem(val calendar: DeviceCalendar, val selected: Boolean)

data class CalendarGroup(val source: String, val accountName: String, val items: List<CalendarItem>)

data class CalendarSectionState(val hasPermission: Boolean, val groups: List<CalendarGroup>)

private object NoCalendars : CalendarSource {
    override fun hasReadPermission() = false
    override suspend fun calendars() = emptyList<DeviceCalendar>()
    override suspend fun events(calendarIds: Set<Long>, from: java.time.LocalDateTime, to: java.time.LocalDateTime) =
        emptyList<app.brujula.calendar.ExternalEvent>()
}

class RoutineViewModel(
    private val repository: RoutineRepository,
    private val resync: ReminderResync = ReminderResync { },
    scopeOverride: CoroutineScope? = null,
    private val calendarSource: CalendarSource = NoCalendars,
) : ViewModel() {
    private val scope: CoroutineScope = scopeOverride ?: viewModelScope

    val routine: StateFlow<Routine> =
        repository.routine.stateIn(scope, SharingStarted.WhileSubscribed(5_000), Routine())

    fun shiftWake(min: Int): Job = change(resyncAfter = true) { it.copy(wake = it.wake.plusMinutes(min.toLong())) }

    fun shiftBed(min: Int): Job = change(resyncAfter = true) { it.copy(bed = it.bed.plusMinutes(min.toLong())) }

    fun shiftRestWake(min: Int): Job = change(resyncAfter = true) { it.copy(restWake = it.restWake.plusMinutes(min.toLong())) }

    fun shiftRestBed(min: Int): Job = change(resyncAfter = true) { it.copy(restBed = it.restBed.plusMinutes(min.toLong())) }

    fun setFlexible(on: Boolean): Job = change { it.copy(flexibleWork = on) }

    fun setGoal(range: IntRange): Job? =
        if (range in GOAL_OPTIONS) change { it.copy(workGoalMin = range) } else null

    fun setThemeMode(m: ThemeMode): Job = change { it.copy(themeMode = m) }

    private val calendarState = MutableStateFlow(CalendarSectionState(hasPermission = false, groups = emptyList()))
    val calendars: StateFlow<CalendarSectionState> = calendarState.asStateFlow()
    private val calendarLock = Mutex()

    init { refreshCalendars() }

    /** Relee permiso y calendarios; la primera vez con permiso, elige todos. Nunca lanza. */
    fun refreshCalendars(): Job = scope.launch {
        calendarLock.withLock { calendarState.value = loadCalendars() }
    }

    private suspend fun loadCalendars(): CalendarSectionState {
        val prefs = repository.calendarPrefs
        val granted = runCatching { calendarSource.hasReadPermission() }.getOrDefault(false)
        if (!granted) return CalendarSectionState(false, emptyList())
        val all = runCatching { calendarSource.calendars() }.getOrDefault(emptyList())
        var stored = runCatching { prefs.selectedCalendarIds.first() }.getOrNull()
        if (stored == null) {
            stored = all.map { it.id }.toSet()
            runCatching { prefs.setSelectedCalendarIds(stored) }
        }
        val groups = all.groupBy { it.sourceLabel to it.accountName }.map { (k, list) ->
            CalendarGroup(k.first, k.second, list.sortedBy { it.name.lowercase() }.map { CalendarItem(it, it.id in stored) })
        }.sortedWith(compareBy({ it.source }, { it.accountName }))
        return CalendarSectionState(true, groups)
    }

    fun toggleCalendar(id: Long): Job = scope.launch {
        calendarLock.withLock {
            val current = calendarState.value
            if (!current.hasPermission) return@withLock
            val selected = current.groups.flatMap { g -> g.items }.filter { it.selected }.map { it.calendar.id }.toSet()
            val next = if (id in selected) selected - id else selected + id
            runCatching { repository.calendarPrefs.setSelectedCalendarIds(next) }
            calendarState.value = loadCalendars()
        }
    }

    fun onPermissionResult(granted: Boolean): Job = refreshCalendars()

    private fun change(resyncAfter: Boolean = false, transform: (Routine) -> Routine): Job =
        scope.launch {
            repository.update(transform)
            if (resyncAfter) runCatching { resync.resync() }
        }

    companion object {
        val GOAL_OPTIONS = listOf(300..360, 360..420, 420..480)
    }
}
