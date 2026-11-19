package app.brujula

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import app.brujula.activities.ActivityRepository
import app.brujula.calendar.CalendarSource
import app.brujula.calendar.WorkClassificationRepository
import app.brujula.calendar.DeviceCalendarSource
import app.brujula.calendar.DeviceGoogleCalendarWriter
import app.brujula.calendar.GoogleCalendarWriter
import app.brujula.calendar.GoogleCopyJournal
import app.brujula.calendar.GoogleCopySync
import app.brujula.claude.AnthropicClaudeGateway
import app.brujula.claude.AutoClassifier
import app.brujula.claude.DelegatingGateway
import app.brujula.claude.EventClassifier
import app.brujula.claude.ClaudeGateway
import app.brujula.claude.ClaudeKeyStore
import app.brujula.claude.DictationContext
import app.brujula.claude.DictationContextProvider
import app.brujula.claude.ActionApplier
import app.brujula.claude.ActionExecutor
import app.brujula.claude.DictationInterpreter
import app.brujula.claude.DayPlanner
import app.brujula.claude.PlanContext
import app.brujula.claude.PlanContextProvider
import app.brujula.planning.DaySnapshotProvider
import app.brujula.planning.ProposalExecutor
import app.brujula.planning.ProposalLock
import app.brujula.planning.ProposalRepository
import app.brujula.claude.ClaudeNotes
import app.brujula.claude.PendingDictations
import app.brujula.claude.PendingCopy
import app.brujula.claude.PendingNotifier
import app.brujula.claude.ValidatedTransition
import app.brujula.claude.TextInterpreter
import app.brujula.reminders.Notifications
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.brujula.claude.KeystoreSecretCipher
import app.brujula.core.time.Clock
import app.brujula.core.time.SystemClock
import app.brujula.reminders.AlarmReminderScheduler
import app.brujula.reminders.ReminderResync
import app.brujula.reminders.ReminderScheduler
import app.brujula.reminders.ReminderSync
import android.util.Log
import app.brujula.reminders.reminderChanges
import app.brujula.reminders.startReminderResync
import kotlinx.coroutines.CoroutineExceptionHandler
import app.brujula.data.db.BrujulaDatabase
import app.brujula.data.db.Migrations
import app.brujula.data.db.Seed
import app.brujula.habits.HabitRepository
import app.brujula.routine.RoutineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import app.brujula.routine.logicalDay
import app.brujula.ui.DeepLinks
import app.brujula.ui.Routes
import java.time.LocalDate

private val Context.routineDataStore by preferencesDataStore(name = "routine")

/** Contenedor de dependencias; se ira llenando en tareas siguientes. */
class AppContainer(val context: Context) {
    val database: BrujulaDatabase by lazy { BrujulaDatabase.create(context) }
    val activityRepository: ActivityRepository by lazy { ActivityRepository(database.activityDao()) }
    val habitRepository: HabitRepository by lazy { HabitRepository(database.habitDao()) }
    val workClassificationRepository: WorkClassificationRepository by lazy {
        WorkClassificationRepository(database.workClassificationDao())
    }
    val routineRepository: RoutineRepository by lazy { RoutineRepository(context.applicationContext.routineDataStore) }

    /** Calendarios del telefono (solo lectura); las pruebas lo sustituyen por uno falso. */
    var calendarSource: CalendarSource = DeviceCalendarSource(context.applicationContext)

    /** Escritura en el Google Calendar principal; las pruebas lo sustituyen por uno falso. */
    var googleCalendarWriter: GoogleCalendarWriter = DeviceGoogleCalendarWriter(context.applicationContext, DeviceCalendarSource(context.applicationContext))

    /** Clave de Claude cifrada con el Keystore; las pruebas la sustituyen. */
    var claudeKeyStore: ClaudeKeyStore = ClaudeKeyStore(context.applicationContext.routineDataStore, KeystoreSecretCipher())

    private var claudeGatewayOverride: ClaudeGateway? = null
    private val defaultClaudeGateway: ClaudeGateway by lazy { AnthropicClaudeGateway(claudeKeyStore) }

    /** Acceso a Claude; sustituible en pruebas. */
    var claudeGateway: ClaudeGateway
        get() = claudeGatewayOverride ?: defaultClaudeGateway
        set(value) { claudeGatewayOverride = value }

    /** Lo que se envía a Claude con cada dictado; usa las dependencias vigentes (reloj y calendarios sustituibles). */
    suspend fun dictationContext(dictatedAt: Long? = null): DictationContext =
        DictationContextProvider(clock, routineRepository, activityRepository, habitRepository, calendarSource).current(dictatedAt)

    /** Convierte un dictado en acciones validadas (no aplica nada); usa el gateway vigente. */
    val dictationInterpreter: DictationInterpreter
        get() = DictationInterpreter(claudeGateway, { at -> dictationContext(at) }, isOnline = { isOnline(context) })

    /** Foto de un día con las mismas reglas que Hoy; usa las dependencias vigentes (calendarios sustituibles). */
    val daySnapshots: DaySnapshotProvider
        get() = DaySnapshotProvider(routineRepository, activityRepository, habitRepository, calendarSource, workClassificationRepository)

    /** Contexto para planificar un día; usa el reloj y los calendarios vigentes. */
    val planContextProvider: PlanContextProvider
        get() = PlanContextProvider(clock, daySnapshots, habitRepository, routineRepository)

    suspend fun planContext(day: LocalDate): PlanContext = planContextProvider.forDay(day)

    private var dayPlannerOverride: DayPlanner? = null

    /** Arma propuestas de Claude para un día (no guarda ni aplica); por defecto con el gateway vigente. Sustituible en pruebas. */
    var dayPlanner: DayPlanner
        get() = dayPlannerOverride ?: DayPlanner(claudeGateway, { day -> planContext(day) }, isOnline = { isOnline(context) }, clock = clock)
        set(value) { dayPlannerOverride = value }

    /** Propuestas de Claude guardadas (D1). */
    val proposalRepository: ProposalRepository by lazy { ProposalRepository(database.proposalDao()) }

    /** Nota de la tarjeta de Claude en Hoy (D15). */
    val claudeNotes: ClaudeNotes by lazy { ClaudeNotes(context.applicationContext.routineDataStore) }

    /** Un solo vuelo para aceptar y deshacer, compartido por todos los ejecutores que se arman aquí. */
    private val proposalLock = ProposalLock()
    private var proposalExecutorOverride: ProposalExecutor? = null

    /**
     * Acepta y deshace propuestas con las dependencias vigentes (reloj, Google, avisos sustituibles) y el cerrojo
     * compartido; las copias de Google corren en [syncScope]. Sustituible en pruebas.
     */
    var proposalExecutor: ProposalExecutor
        get() = proposalExecutorOverride ?: ProposalExecutor(
            database, activityRepository, proposalRepository, googleCopySync, reminderSync, claudeNotes, clock, syncScope,
            reminderScheduler = reminderScheduler, journal = googleCopyJournal, lock = proposalLock,
        )
        set(value) { proposalExecutorOverride = value }

    private var interpreterOverride: TextInterpreter? = null
    private var executorOverride: ActionApplier? = null

    /** Intérprete de dictados; sustituible en pruebas. */
    var interpreter: TextInterpreter
        get() = interpreterOverride ?: dictationInterpreter
        set(value) { interpreterOverride = value }

    /** Aplica y deshace lo dictado, con las dependencias vigentes; sustituible en pruebas. */
    var executor: ActionApplier
        get() = executorOverride ?: ActionExecutor(database, activityRepository, habitRepository, googleCopySync, reminderSync, syncScope = syncScope, notes = claudeNotes, today = { logicalDay(clock.now()) })
        set(value) { executorOverride = value }

    /** Dictados guardados sin conexión; al terminar una pasada avisa con una notificación en `actividades`. */
    val pendingDictations: PendingDictations by lazy {
        val app = context.applicationContext
        PendingDictations(
            app.routineDataStore,
            object : PendingNotifier {
                private fun id() = (System.nanoTime() / 1000).toInt()
                override fun scheduled(labels: List<String>, problems: List<String>) {
                    val text = labels.joinToString(NEWLINE) + problems.joinToString("") { NEWLINE + it }
                    Notifications.showText(app, id(), PendingCopy.successTitle(labels), text)
                }
                override fun configStalled(count: Int) {
                    Notifications.showText(app, CONFIG_NOTICE_ID, "Dictados pendientes", PendingCopy.configNotice(count))
                }
                override fun needsKey(count: Int) {
                    Notifications.showText(app, KEY_NOTICE_ID, "Dictados pendientes", PendingCopy.keyNotice(count))
                }
                override fun planRequested(day: LocalDate, text: String) {
                    val request = text.take(DeepLinks.MAX_REQUEST)
                    Notifications.showText(
                        app, id(), PendingCopy.PLAN_TITLE, PendingCopy.planText(request),
                        DeepLinks.intent(app, Routes.proposal(day, request)),
                    )
                }
                override fun dropped(text: String, reason: String) {
                    Notifications.showText(app, id(), "Dictado sin agendar", "No pude agendar «$text»: $reason")
                }
            },
        )
    }

    /** Procesa los dictados pendientes (idempotente; nunca dos a la vez). */
    suspend fun drainPendingDictations() = pendingDictations.drain(interpreter, executor)

    /** Clasificacion automatica de eventos importados; el conjunto de claves ya intentadas vive aqui (una vez por proceso). */
    val autoClassifier: AutoClassifier by lazy {
        AutoClassifier(EventClassifier(DelegatingGateway { claudeGateway }), workClassificationRepository, syncScope) { isOnline(context) }
    }

    /** Ambito de la app para la copia a Google: sobrevive al formulario (SupervisorJob + IO). */
    val syncScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e("AppContainer", "Fallo en la copia a Google", e) },
    )

    private var googleSyncOverride: GoogleCopySync? = null
    private val defaultGoogleSync: GoogleCopySync by lazy {
        GoogleCopySync(googleCalendarWriter, activityRepository) { routineRepository.calendarPrefs.copyToGoogleEnabled.first() }
    }

    /** Copias a Google pendientes de aceptar/deshacer un plan o de borrar una actividad (sobreviven a la muerte del proceso). */
    val googleCopyJournal: GoogleCopyJournal by lazy {
        GoogleCopyJournal(context.applicationContext.getSharedPreferences(GoogleCopyJournal.PREFS_NAME, Context.MODE_PRIVATE))
    }

    /**
     * Termina lo que quedó en el diario y luego crea las copias a Google que falten (idempotente); se lanza en
     * [syncScope] al arrancar y al conceder el permiso.
     */
    suspend fun reconcileGoogleCopies() {
        googleCopyJournal.drain(googleCopySync, activityRepository)
        googleCopySync.reconcile(logicalDay(clock.now()))
    }

    /** Un solo GoogleCopySync (su Mutex serializa); sustituible en pruebas. */
    var googleCopySync: GoogleCopySync
        get() = googleSyncOverride ?: defaultGoogleSync
        set(value) { googleSyncOverride = value }

    /** Reloj de los receptores; las pruebas lo sustituyen. */
    var clock: Clock = SystemClock

    private var schedulerOverride: ReminderScheduler? = null
    private var syncOverride: ReminderResync? = null
    private val defaultScheduler: ReminderScheduler by lazy { AlarmReminderScheduler(context.applicationContext) }
    private val defaultSync: ReminderResync by lazy {
        ReminderSync(clock, routineRepository, activityRepository, habitRepository, reminderScheduler)
    }

    /** Sustituibles en pruebas; por defecto, alarmas reales. */
    var reminderScheduler: ReminderScheduler
        get() = schedulerOverride ?: defaultScheduler
        set(value) { schedulerOverride = value }
    var reminderSync: ReminderResync
        get() = syncOverride ?: defaultSync
        set(value) { syncOverride = value }
}

private const val NEWLINE = "\n"
private const val KEY_NOTICE_ID = 0x4B4559
private const val CONFIG_NOTICE_ID = 0x434647

/** Hay internet utilizable ahora mismo (validado, no solo conectado). */
fun isOnline(context: Context): Boolean = try {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
} catch (e: Exception) {
    false
}

open class BrujulaApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    /** Un fallo no atrapado en segundo plano se registra en vez de tumbar el proceso. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e("BrujulaApp", "Fallo en segundo plano", e) },
    )

    override fun onCreate() {
        super.onCreate()
        startBackgroundWork()
    }

    /** Trabajo de arranque en segundo plano; las pruebas lo anulan. */
    protected open fun startBackgroundWork() {
        appScope.launch {
            runCatching { Seed.ensureDefaults(container.database, container.routineRepository, LocalDate.now()) }
                .onFailure { Log.e("BrujulaApp", "Fallo al sembrar valores por defecto", it) }
            runCatching { Migrations.seedWellness(container.database, container.routineRepository) }
                .onFailure { Log.e("BrujulaApp", "Fallo al sembrar bienestar", it) }
            runCatching { Migrations.exerciseToWorkdays(container.database, container.routineRepository) }
                .onFailure { Log.e("BrujulaApp", "Fallo al migrar el ejercicio", it) }
        }
        // Copias a Google pendientes (citas guardadas sin permiso, o con la copia apagada); idempotente.
        container.syncScope.launch { container.reconcileGoogleCopies() }
        launchReminderResync()
        launchPendingDrain()
    }

    /** Procesa los dictados pendientes al abrir la app y cada vez que la red pasa a validada. */
    private fun launchPendingDrain() {
        val c = container
        val drain = { c.syncScope.launch { runCatching { c.drainPendingDictations() }.onFailure { Log.e("BrujulaApp", "Fallo al procesar pendientes", it) } }; Unit }
        drain()
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            // Solo al pasar a red validada (no en cada cambio de señal) y no al perderla.
            // Android entrega enseguida el estado actual al registrar: sembrarlo evita un segundo drenado (el de arriba ya corre).
            val transition = ValidatedTransition(initiallyValidated = isOnline(this))
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (transition.onChange(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))) drain()
                }
                override fun onLost(network: Network) { transition.onChange(false) }
            })
        }.onFailure { Log.e("BrujulaApp", "No pude vigilar la red", it) }
    }

    /** Reprograma al abrir la app y cada vez que cambian rutina, actividades, habitos o registros. */
    private fun launchReminderResync() {
        val c = container
        startReminderResync(
            appScope,
            reminderChanges(c.routineRepository, c.activityRepository, c.habitRepository, c.clock),
            ReminderResync { c.reminderSync.resync() },
        )
    }
}

val Context.container: AppContainer
    get() = (applicationContext as BrujulaApp).container
