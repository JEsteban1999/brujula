package app.brujula.week

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.brujula.activities.ActivityRepository
import app.brujula.activities.OccurrenceOverride
import app.brujula.activities.Repeat
import app.brujula.activities.freeSlots
import app.brujula.calendar.CalendarSource
import app.brujula.calendar.ExternalEvent
import app.brujula.calendar.WorkClassificationRepository
import app.brujula.calendar.classificationKey
import app.brujula.core.time.Clock
import app.brujula.planning.SnapshotInputs
import app.brujula.planning.buildDaySnapshot
import app.brujula.reminders.ReminderResync
import app.brujula.routine.Routine
import app.brujula.routine.RoutineRepository
import app.brujula.routine.awakeWindow
import app.brujula.routine.logicalDay
import app.brujula.sleep.SleepRecord
import app.brujula.sleep.SleepRepository
import app.brujula.today.LOGICAL_DAY_START
import app.brujula.today.selectedEventsBetweenOrEmpty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Cuántas semanas hacia atrás y hacia adelante se puede navegar (D4). */
const val MIN_WEEK_OFFSET = -26
const val MAX_WEEK_OFFSET = 4

private const val TICK_MS = 30_000L

/** Lo que `Deshacer` necesita para volver atrás: el override que había antes de quitar [day] (o nada). */
data class SkipUndo(val activityId: Long, val day: LocalDate, val before: OccurrenceOverride?)

data class WeekMessage(val text: String, val undo: SkipUndo?)

data class WeekUiState(
    /** Nulo hasta que llega la primera lectura completa. */
    val data: WeekData?,
    val selected: LocalDate,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val message: WeekMessage?,
)

private data class ExternalLoad(val events: List<ExternalEvent>)

private data class Loaded(
    val monday: LocalDate,
    val routine: Routine,
    val inputs: SnapshotInputs,
    val sleep: Map<LocalDate, SleepRecord>,
)

class WeekViewModel(
    private val clock: Clock,
    routineRepository: RoutineRepository,
    private val activityRepository: ActivityRepository,
    private val calendarSource: CalendarSource,
    private val classifications: WorkClassificationRepository,
    private val sleepRepository: SleepRepository,
    private val resync: ReminderResync,
    scopeOverride: CoroutineScope? = null,
    private val tickMs: Long = TICK_MS,
) : ViewModel() {
    private val scope: CoroutineScope = scopeOverride ?: viewModelScope

    private val ticker = flow {
        while (true) {
            emit(clock.now())
            if (tickMs <= 0) awaitCancellation()
            delay(tickMs)
        }
    }

    /** Semanas respecto a la semana lógica actual; seguir "la actual" es offset 0, aunque el día lógico cambie. */
    private val offset = MutableStateFlow(0)
    private val picked = MutableStateFlow<LocalDate?>(null)
    private val message = MutableStateFlow<WeekMessage?>(null)
    private val reloadExternal = MutableStateFlow(0)
    private val writeLock = Mutex()

    @Volatile private var latest: Loaded? = null

    private val weekMonday: Flow<LocalDate> = combine(
        ticker.map { logicalDay(it) }.distinctUntilChanged(),
        offset,
    ) { today, off -> mondayOf(today).plusWeeks(off.toLong()) }.distinctUntilChanged()

    private val classificationMap: Flow<Map<String, Boolean>> = classifications.observe().catch { emit(emptyMap()) }

    private val base = combine(
        routineRepository.routine,
        activityRepository.observeAll(),
        activityRepository.observeDoneKeys(),
        activityRepository.observeOverrides(),
    ) { routine, activities, done, overrides -> SnapshotInputs(routine, activities, done, overrides, emptyList()) { false } }

    private val selectedIds = routineRepository.calendarPrefs.selectedCalendarIds.catch { emit(null) }

    /** Calendarios de la semana en IO: `[lunes 04:00 − 1 día, lunes + 7 días 04:00)`; un fallo da lo propio sin externos. */
    private fun externalFor(monday: LocalDate): Flow<ExternalLoad> =
        combine(selectedIds, reloadExternal) { ids, _ -> ids }
            .mapLatest { ids ->
                val from = monday.atTime(LOGICAL_DAY_START).minusDays(1)
                val to = monday.plusDays(7).atTime(LOGICAL_DAY_START)
                ExternalLoad(calendarSource.selectedEventsBetweenOrEmpty(flowOf(ids), from, to))
            }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val loaded: Flow<Loaded> = weekMonday.flatMapLatest { monday ->
        combine(
            base,
            externalFor(monday),
            activityRepository.observeGoogleEventIds(),
            classificationMap,
            sleepRepository.observeRange(monday.minusDays(1), monday.plusDays(5)).catch { emit(emptyMap()) },
        ) { b, external, linked, marks, sleep ->
            // Segunda defensa, como en Hoy: una copia de Brújula en Google nunca es un evento externo.
            val events = external.events.filter { it.eventId !in linked }
            val inputs = b.copy(external = events, isWork = { classifications.isWork(it, marks) })
            Loaded(monday, b.routine, inputs, sleep)
        }
    }

    val state: StateFlow<WeekUiState> = combine(ticker, picked, message, loaded) { now, pick, msg, l ->
        latest = l
        val today = logicalDay(now)
        val data = buildWeek(l.monday, now, l.inputs, l.sleep)
        val weeks = ChronoUnit.WEEKS.between(mondayOf(today), l.monday).toInt()
        WeekUiState(
            data = data,
            selected = selectedFor(l.monday, today, pick),
            canGoBack = weeks > MIN_WEEK_OFFSET,
            canGoForward = weeks < MAX_WEEK_OFFSET,
            message = msg,
        )
    }.stateIn(scope, SharingStarted.Eagerly, initial())

    private fun initial(): WeekUiState {
        val today = logicalDay(clock.now())
        return WeekUiState(null, today, canGoBack = true, canGoForward = true, message = null)
    }

    /** El día elegido si cae en la semana; si no, hoy (semana actual) o el lunes. */
    private fun selectedFor(monday: LocalDate, today: LocalDate, pick: LocalDate?): LocalDate {
        val sunday = monday.plusDays(6)
        return pick?.takeIf { it in monday..sunday } ?: if (today in monday..sunday) today else monday
    }

    private fun currentMonday(): LocalDate = mondayOf(logicalDay(clock.now()))

    fun previousWeek() = moveWeek(-1)

    fun nextWeek() = moveWeek(1)

    private fun moveWeek(delta: Int) {
        offset.update { (it + delta).coerceIn(MIN_WEEK_OFFSET, MAX_WEEK_OFFSET) }
        picked.value = null
    }

    /** Elige [day]; si es de otra semana dentro del rango, también cambia de semana. */
    fun select(day: LocalDate) {
        val weeks = ChronoUnit.WEEKS.between(currentMonday(), mondayOf(day)).toInt()
        if (weeks !in MIN_WEEK_OFFSET..MAX_WEEK_OFFSET) return
        offset.value = weeks
        picked.value = day
    }

    /** "Que Claude arme este día" solo de hoy a hoy + 6 (D10). */
    fun canAskClaude(day: LocalDate): Boolean {
        val today = logicalDay(clock.now())
        return !day.isBefore(today) && !day.isAfter(today.plusDays(6))
    }

    /**
     * Inicio para `Agregar a este día`: `null` en hoy (el formulario pone la hora actual); si no, el primer hueco de 30 min
     * o más de la vigilia, o la hora de levantarse.
     */
    fun addStartFor(day: LocalDate): LocalDateTime? {
        if (day == logicalDay(clock.now())) return null
        val l = latest ?: return null
        val snap = buildDaySnapshot(day, l.inputs)
        val window = l.routine.awakeWindow(day)
        val busy = snap.own.map { it.span } + snap.external.map { it.event.span }
        return freeSlots(window, busy).firstOrNull()?.start ?: window.start
    }

    /**
     * Quita solo la ocurrencia [e] de su serie (override `skipped`). Se valida contra lo guardado: solo series sin copia en
     * Google y de hoy en adelante. Devuelve el trabajo para poder esperarlo.
     */
    fun skipOccurrence(e: WeekEvent.Own): Job {
        if (!e.canSkip) return completed()
        return scope.launch {
            writeLock.withLock {
                val occ = e.occurrence
                val stored = activityRepository.get(occ.activity.id) ?: return@withLock
                val allowed = stored.repeat != Repeat.NONE && !stored.copyToGoogle && stored.googleEventId == null &&
                    logicalDay(occ.span.start) >= logicalDay(clock.now())
                if (!allowed) return@withLock
                val before = activityRepository.getOverride(stored.id, occ.day)
                activityRepository.setOverride(OccurrenceOverride(stored.id, occ.day, null, null, skipped = true))
                resyncSafely()
                message.value = WeekMessage(
                    "Quité «${stored.title}» del ${weekdayName(occ.day)} ${occ.day.dayOfMonth}.",
                    SkipUndo(stored.id, occ.day, before),
                )
            }
        }
    }

    /** Restaura el override anterior (o lo borra) solo si sigue siendo el que escribió `Quitar solo este día`. */
    fun undoSkip(): Job {
        val undo = message.value?.undo ?: return completed()
        message.value = null
        return scope.launch {
            writeLock.withLock {
                val current = activityRepository.getOverride(undo.activityId, undo.day)
                if (current?.skipped != true) return@withLock
                val before = undo.before
                if (before == null) activityRepository.clearOverride(undo.activityId, undo.day) else activityRepository.setOverride(before)
                resyncSafely()
            }
        }
    }

    fun dismissMessage() { message.value = null }

    /** Guarda si los eventos con el título de [event] cuentan como trabajo (los sin título nunca cuentan). */
    fun setCountsAsWork(event: ExternalEvent, isWork: Boolean) {
        val key = classificationKey(event)
        if (key.isEmpty()) return
        scope.launch { classifications.set(key, isWork) }
    }

    /** Vuelve a leer los calendarios del teléfono (al volver a la pantalla). */
    fun refreshExternal() {
        reloadExternal.update { it + 1 }
    }

    private suspend fun resyncSafely() {
        withContext(NonCancellable) {
            try {
                resync.resync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Los avisos se recalculan solos en el siguiente cambio; no se pierde el override ya guardado.
            }
        }
    }

    private fun completed(): Job = (Job() as CompletableJob).also { it.complete() }
}
