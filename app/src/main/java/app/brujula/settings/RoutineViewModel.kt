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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch

data class CalendarItem(val calendar: DeviceCalendar, val selected: Boolean)

data class CalendarGroup(val source: String, val accountName: String, val items: List<CalendarItem>)

data class CalendarSectionState(
    val hasPermission: Boolean,
    val groups: List<CalendarGroup>,
    val showSettingsHint: Boolean = false,
)

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
    /** Ambito de la app: la reconciliacion de copias a Google sigue aunque se cierre la pantalla. */
    private val syncScope: CoroutineScope? = null,
    private val reconcileGoogle: suspend () -> Unit = { },
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

    private var permissionDenied = false

    private suspend fun loadCalendars(): CalendarSectionState {
        val prefs = repository.calendarPrefs
        val granted = runCatching { calendarSource.hasReadPermission() }.getOrDefault(false)
        if (!granted) return CalendarSectionState(false, emptyList(), showSettingsHint = permissionDenied)
        permissionDenied = false
        val query = runCatching { calendarSource.calendars() }
        val all = query.getOrDefault(emptyList())
        val read = runCatching { prefs.selectedCalendarIds.first() }
        var stored = read.getOrNull()
        // Solo se guarda el valor inicial si la lectura de preferencias y la consulta salieron bien y hay calendarios.
        if (read.isSuccess && stored == null && query.isSuccess && all.isNotEmpty()) {
            val firstRun = all.map { it.id }.toSet()
            runCatching { prefs.setSelectedCalendarIds(firstRun) }
            stored = firstRun
        }
        val chosen = stored ?: if (read.isSuccess && query.isSuccess) emptySet() else all.map { it.id }.toSet()
        val groups = all.groupBy { it.sourceLabel to it.accountName }.map { (k, list) ->
            CalendarGroup(k.first, k.second, list.sortedBy { it.name.lowercase() }.map { CalendarItem(it, it.id in chosen) })
        }.sortedWith(compareBy({ it.source }, { it.accountName }))
        return CalendarSectionState(true, groups)
    }

    fun toggleCalendar(id: Long): Job = scope.launch {
        calendarLock.withLock {
            val current = calendarState.value
            if (!current.hasPermission) return@withLock
            // Se parte de lo guardado (no de lo visible) para no perder ids de cuentas ausentes por un momento.
            val visible = current.groups.flatMap { g -> g.items }.filter { it.selected }.map { it.calendar.id }.toSet()
            val stored = runCatching { repository.calendarPrefs.selectedCalendarIds.first() }.getOrNull() ?: visible
            val next = if (id in stored) stored - id else stored + id
            runCatching { repository.calendarPrefs.setSelectedCalendarIds(next) }
            calendarState.value = loadCalendars()
        }
    }

    /** Interruptor global "Copiar citas a Google Calendar" (por defecto activo). */
    val copyToGoogleEnabled: StateFlow<Boolean> =
        repository.calendarPrefs.copyToGoogleEnabled.catch { emit(true) }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), true)

    /** Al encenderlo se crean las copias pendientes; al apagarlo no se borra nada. */
    fun setCopyToGoogleEnabled(on: Boolean): Job = scope.launch {
        try {
            repository.calendarPrefs.setCopyToGoogleEnabled(on)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("RoutineViewModel", "No se pudo guardar el interruptor de copia a Google", e)
            return@launch
        }
        if (on) launchReconcile().join()
    }

    private fun launchReconcile(): Job = (syncScope ?: scope).launch { reconcileGoogle() }

    fun onPermissionResult(granted: Boolean): Job {
        permissionDenied = !granted
        return scope.launch {
            refreshCalendars().join()
            if (granted) launchReconcile().join()
        }
    }

    private fun change(resyncAfter: Boolean = false, transform: (Routine) -> Routine): Job =
        scope.launch {
            repository.update(transform)
            if (resyncAfter) runCatching { resync.resync() }
        }

    companion object {
        val GOAL_OPTIONS = listOf(300..360, 360..420, 420..480)
    }
}
