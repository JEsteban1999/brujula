package app.brujula.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.brujula.activities.Activity
import app.brujula.activities.ActivityRepository
import app.brujula.activities.Occurrence
import app.brujula.activities.Overrides
import app.brujula.activities.WorkStatus
import app.brujula.activities.freeSlots
import app.brujula.activities.workMinutes
import app.brujula.activities.workStatus
import app.brujula.calendar.CalendarSource
import app.brujula.calendar.ExternalEvent
import app.brujula.calendar.NoCalendarSource
import app.brujula.calendar.WorkClassificationRepository
import app.brujula.calendar.classificationKey
import app.brujula.calendar.eventsOnLogicalDay
import app.brujula.core.time.Clock
import app.brujula.core.time.TimeSpan
import app.brujula.core.time.formatDayEs
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitRepository
import app.brujula.planning.AcceptedPlan
import app.brujula.planning.UndoResult
import app.brujula.reminders.PermissionStatus
import app.brujula.routine.DayKind
import app.brujula.routine.DayType
import app.brujula.routine.Routine
import app.brujula.routine.RoutineRepository
import app.brujula.routine.awakeWindow
import app.brujula.routine.dayTypeOf
import app.brujula.routine.logicalDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

data class HabitChip(val habitId: Long, val name: String, val icon: HabitIcon, val count: Int, val target: Int)

/** Los hábitos que muestran Hoy y el widget: activos, esenciales, con meta, que tocan ese día; los dos primeros. */
fun todayHabitChips(habits: List<app.brujula.habits.Habit>, counts: Map<Long, Int>, day: LocalDate): List<HabitChip> = habits
    .filter { it.active && it.group == app.brujula.habits.HabitGroup.ESSENTIAL && it.dailyTarget > 0 && day.dayOfWeek in it.days }
    .take(MAX_VISIBLE_HABITS)
    .map { HabitChip(it.id, it.name, it.icon, counts[it.id] ?: 0, it.dailyTarget) }

/** Bloque de la línea de tiempo: actividad propia o evento importado (solo lectura, "Fijo"). */
sealed interface TimelineBlock {
    val span: TimeSpan
    val isNow: Boolean

    data class Own(val occurrence: Occurrence, override val isNow: Boolean) : TimelineBlock {
        override val span: TimeSpan get() = occurrence.span
    }

    data class External(val event: ExternalEvent, val countsAsWork: Boolean, override val isNow: Boolean) : TimelineBlock {
        override val span: TimeSpan get() = event.span
    }
}

/**
 * Tarjeta de Claude (D15). [text] nulo = estado vacío (`¿Te armo el día?`). [proposalId] y [canUndo] salen del plan aceptado
 * más reciente cuyo día no ha pasado (no de la nota: un dictado posterior la reemplaza pero el plan sigue deshaciéndose).
 */
data class ClaudeCardState(
    val text: String?,
    val proposalId: Long?,
    val canUndo: Boolean,
    /** La nota es un pedido de plan en cola: el botón abre la Propuesta de [askDay] con [askRequest]; null = hoy, sin pedido. */
    val askDay: LocalDate? = null,
    val askRequest: String? = null,
)

data class TodayUiState(
    val dayLabel: String,
    val workMinutes: Int,
    /** Null en días de descanso: no hay meta. */
    val workGoal: IntRange?,
    val workStatus: WorkStatus?,
    val habits: List<HabitChip>,
    val timeline: List<TimelineBlock>,
    /** Eventos importados de todo el día: fila compacta, sin ocupar huecos ni contar como trabajo. */
    val allDay: List<ExternalEvent> = emptyList(),
    val freeSlots: List<TimeSpan>,
    val permissions: PermissionStatus,
    /** Día lógico mostrado (para prellenar formularios). */
    val day: LocalDate,
    val dayType: DayType = DayType(DayKind.WORKDAY),
    /** Madrugada (00:00-03:59): el reloj ya cambió de fecha pero se sigue mostrando el día lógico anterior. */
    val isLateNight: Boolean = false,
    /** `null` hasta que la nota carga: la tarjeta no retrasa Hoy. */
    val claude: ClaudeCardState? = null,
    /** Resultado de `Deshacer` en la tarjeta (p. ej. `Dejé 1 cambio que hiciste después.`). */
    val claudeMessage: String? = null,
)

private data class DayData(
    val routine: Routine,
    val activities: List<Activity>,
    val done: Set<Pair<Long, LocalDate>>,
    val overrides: Overrides,
)

private const val MAX_VISIBLE_HABITS = 2
private const val TICK_MS = 30_000L
private const val PLAN_READY = "Tu plan está listo."
private const val UNDONE = "Deshice el plan."
private const val NOT_UNDOABLE = "Ese plan ya no se puede deshacer."

private data class ClaudeInputs(val loaded: Boolean, val note: app.brujula.claude.ClaudeNote?, val accepted: List<AcceptedPlan>)

class TodayViewModel(
    private val clock: Clock,
    routineRepository: RoutineRepository,
    private val activityRepository: ActivityRepository,
    private val habitRepository: HabitRepository,
    private val readPermissions: () -> PermissionStatus,
    private val calendarSource: CalendarSource = NoCalendarSource,
    private val classifications: WorkClassificationRepository? = null,
    /** Clasifica con Claude los títulos desconocidos de hoy (una vez por clave); nulo en pruebas que no lo usan. */
    private val autoClassifier: app.brujula.claude.AutoClassifier? = null,
    /** La última nota de Claude (D15); el valor inicial nulo evita esperar al DataStore. */
    claudeNotes: Flow<app.brujula.claude.ClaudeNote?> = flowOf(null),
    /** Planes ACCEPTED (más reciente primero), para saber si `Deshacer` aún aplica. */
    acceptedPlans: Flow<List<AcceptedPlan>> = flowOf(emptyList()),
    private val undoPlan: suspend (Long) -> UndoResult = { UndoResult.NotAccepted },
    scopeOverride: CoroutineScope? = null,
    private val tickMs: Long = TICK_MS,
) : ViewModel() {
    private val scope: CoroutineScope = scopeOverride ?: viewModelScope

    private val permissions = MutableStateFlow(readPermissions())

    private val ticker = flow {
        while (true) {
            emit(clock.now())
            if (tickMs <= 0) awaitCancellation()
            delay(tickMs)
        }
    }

    private val reloadExternal = MutableStateFlow(0)

    /** Eventos importados cercanos al día lógico (−1 día, +1 día); la consulta corre fuera del hilo principal. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val externalRaw: StateFlow<List<ExternalEvent>> = combine(
        ticker.map { logicalDay(it) }.distinctUntilChanged(),
        routineRepository.calendarPrefs.selectedCalendarIds.catch { emit(null) },
        reloadExternal,
    ) { day, ids, _ -> day to ids }
        .mapLatest { (day, ids) ->
            withContext(Dispatchers.IO) {
                runCatching {
                    calendarSource.eventsAroundLogicalDay(ids, day)
                }.getOrDefault(emptyList())
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val classificationMap: Flow<Map<String, Boolean>> =
        classifications?.observe()?.catch { emit(emptyMap()) } ?: flowOf(emptyMap())

    // Segunda defensa: un evento enlazado a una actividad (copia de Brujula) nunca cuenta como externo,
    // aunque un adaptador de sincronizacion le haya quitado la marca customAppPackage.
    private val externalInputs = combine(
        externalRaw, classificationMap, activityRepository.observeGoogleEventIds(),
    ) { events, marks, linked -> events.filter { it.eventId !in linked } to marks }

    private val data = combine(
        routineRepository.routine,
        activityRepository.observeAll(),
        activityRepository.observeDoneKeys(),
        activityRepository.observeOverrides(),
    ) { routine, activities, done, overrides -> DayData(routine, activities, done, overrides) }

    private val claudeMessage = MutableStateFlow<String?>(null)
    private var undoing = false

    /** Nota y planes aceptados; un fallo del DataStore o de la base deja la tarjeta vacía, nunca rompe Hoy. */
    private val claudeInputs: Flow<ClaudeInputs> = combine(
        claudeNotes.catch { emit(null) },
        acceptedPlans.catch { emit(emptyList()) },
    ) { note, accepted -> ClaudeInputs(true, note, accepted) }
        .onStart { emit(ClaudeInputs(false, null, emptyList())) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<TodayUiState> = ticker
        .flatMapLatest { now ->
            val day = logicalDay(now)
            combine(
                data,
                habitRepository.observeHabits(),
                habitRepository.observeCountsFor(day),
                permissions,
                externalInputs,
            ) { (routine, activities, done, overrides), habits, counts, perms, (rawExternal, marks) ->
                build(now, day, routine, activities, done, overrides, habits, counts, perms, rawExternal, marks)
            }
        }
        // Fuera del flatMapLatest: el tictac no reinicia la lectura de la nota (la tarjeta no parpadea).
        .combine(claudeInputs) { ui, claude -> ui.copy(claude = cardFor(claude, clock.now(), ui.day)) }
        .combine(claudeMessage) { ui, message -> ui.copy(claudeMessage = message) }
        .stateIn(scope, SharingStarted.Eagerly, initial())

    @Volatile private var lastMarkKeys: Set<String> = emptySet()

    private fun titlesOf(s: TodayUiState): Map<String, String> =
        (s.timeline.filterIsInstance<TimelineBlock.External>().map { it.event } + s.allDay)
            .associate { classificationKey(it) to it.title }

    init {
        val auto = autoClassifier
        if (auto != null) {
            // Solo los títulos de los eventos del día lógico; el AutoClassifier decide qué falta y corre fuera del hilo principal.
            scope.launch {
                combine(state, classificationMap) { s, marks ->
                    lastMarkKeys = marks.keys
                    titlesOf(s) to marks.keys
                }.distinctUntilChanged().collect { (titles, known) -> auto.request(titles, known) }
            }
        }
    }

    private fun initial(): TodayUiState {
        val day = logicalDay(clock.now())
        val type = dayTypeOf(day)
        val goal = if (type.isRest) null else Routine().workGoalMin
        return TodayUiState(
            dayLabel = formatDayEs(day), workMinutes = 0, workGoal = goal,
            workStatus = goal?.let { workStatus(0, it) }, habits = emptyList(), timeline = emptyList(),
            freeSlots = emptyList(), permissions = permissions.value, day = day, dayType = type,
            isLateNight = isLateNight(clock.now(), day),
        )
    }

    private fun build(
        now: LocalDateTime,
        day: LocalDate,
        routine: Routine,
        activities: List<Activity>,
        done: Set<Pair<Long, LocalDate>>,
        overrides: Overrides,
        habits: List<app.brujula.habits.Habit>,
        counts: Map<Long, Int>,
        perms: PermissionStatus,
        rawExternal: List<ExternalEvent>,
        workMarks: Map<String, Boolean>,
    ): TodayUiState {
        val window = routine.awakeWindow(day)
        // Dia logico: todo lo que empieza en el (04:00 a 03:59), ya sea por fecha de calendario de ayer, hoy o manana,
        // mas lo de ayer que aun cruza la vigilia de hoy.
        val logical = logicalOccurrences(day, activities, done, overrides)
        val spill = spillOccurrences(day, activities, done, window, overrides)
        val ownBlocks = (spill + logical).map { TimelineBlock.Own(it, now in it.span) }
        val external = eventsOnLogicalDay(day, rawExternal, window)
        val timed = external.filter { !it.allDay }
        val externalBlocks = timed.map {
            TimelineBlock.External(it, classifications?.isWork(it, workMarks) ?: false, now in it.span)
        }
        val timeline = (ownBlocks + externalBlocks).sortedBy { it.span.start }
        val logicalSpan = TimeSpan(day.atTime(LOGICAL_DAY_START), day.plusDays(1).atTime(LOGICAL_DAY_START))
        val minutes = workMinutes(logical, externalBlocks.filter { it.countsAsWork }.map { it.event }, logicalSpan)
        val type = dayTypeOf(day)
        val goal = if (type.isRest) null else routine.workGoalMin
        return TodayUiState(
            dayLabel = formatDayEs(day),
            workMinutes = minutes,
            workGoal = goal,
            workStatus = goal?.let { workStatus(minutes, it) },
            habits = todayHabitChips(habits, counts, day),
            timeline = timeline,
            allDay = external.filter { it.allDay },
            freeSlots = freeSlots(window, timeline.map { it.span }),
            permissions = perms,
            day = day,
            dayType = type,
            isLateNight = isLateNight(now, day),
        )
    }

    /** Primera lectura aún no llegó → sin tarjeta; si llegó sin nota del día, estado vacío. */
    private fun cardFor(inputs: ClaudeInputs, now: LocalDateTime, day: LocalDate): ClaudeCardState? {
        if (!inputs.loaded) return null
        // El texto es la última nota (de hoy o de un plan futuro); Deshacer sale del plan aceptado vigente, no de la nota.
        val note = inputs.note?.takeIf { it.day >= day }
        val plan = inputs.accepted.firstOrNull { it.day >= day }
        val text = note?.text ?: plan?.let { PLAN_READY }
        val ask = note?.takeIf { !it.request.isNullOrBlank() }
        return ClaudeCardState(text, plan?.id, canUndo = plan != null, askDay = ask?.day, askRequest = ask?.request)
    }

    /** `Deshacer` de la tarjeta: el resultado queda en [TodayUiState.claudeMessage]. De un solo vuelo; el ejecutor no se cancela. */
    fun undoPlan() {
        val card = state.value.claude ?: return
        val id = card.proposalId ?: return
        if (!card.canUndo || undoing) return
        undoing = true
        scope.launch {
            try {
                claudeMessage.value = when (val r = this@TodayViewModel.undoPlan.invoke(id)) {
                    is UndoResult.Undone -> UndoResult.keptMessage(r.kept) ?: UNDONE
                    UndoResult.NotAccepted -> NOT_UNDOABLE
                    is UndoResult.Failed -> r.message
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                claudeMessage.value = UndoResult.FAILED
            } finally {
                undoing = false
            }
        }
    }

    fun dismissClaudeMessage() { claudeMessage.value = null }

    /** El reloj ya está en una fecha posterior al día lógico mostrado (00:00-03:59). */
    private fun isLateNight(now: LocalDateTime, day: LocalDate): Boolean = now.toLocalDate().isAfter(day)

    fun addHabit(habitId: Long) {
        scope.launch { habitRepository.logDone(habitId, clock.now()) }

    }
    /** Alterna hecho para la ocurrencia de [day] (el dia de inicio de esa ocurrencia). */
    fun toggleDone(activityId: Long, day: LocalDate) {
        scope.launch {
            val done = activityRepository.observeDoneKeys().first().contains(activityId to day)
            activityRepository.setDone(activityId, day, !done)
        }
    }

    /** Guarda si los eventos con el título de [event] cuentan como trabajo (los sin título nunca cuentan). */
    fun setCountsAsWork(event: ExternalEvent, isWork: Boolean) {
        val key = classificationKey(event)
        if (key.isEmpty()) return
        scope.launch { classifications?.set(key, isWork) }
    }

    /** Vuelve a leer los calendarios del teléfono (al volver a la pantalla). */
    fun refreshExternal() {
        reloadExternal.value += 1
        // Al volver a la pantalla (p. ej. tras poner la clave o recuperar la red) se reintentan las claves liberadas;
        // el conjunto de intentadas del AutoClassifier acota las llamadas.
        autoClassifier?.request(titlesOf(state.value), lastMarkKeys)
    }

    fun refreshPermissions() {
        permissions.value = readPermissions()
    }
}
