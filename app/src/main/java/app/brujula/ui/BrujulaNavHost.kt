package app.brujula.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.brujula.activities.form.ActivityFormScreen
import app.brujula.activities.form.ActivityFormViewModel
import app.brujula.container
import app.brujula.core.theme.BrujulaTheme
import app.brujula.habits.HabitFormScreen
import app.brujula.planning.ProposalScreen
import app.brujula.planning.ProposalViewModel
import app.brujula.routine.logicalDay
import app.brujula.routine.wakeFor
import app.brujula.habits.HabitGroup
import app.brujula.habits.HabitFormViewModel
import app.brujula.habits.HabitsScreen
import app.brujula.habits.parseHabitGroup
import app.brujula.habits.HabitsViewModel
import app.brujula.reminders.readPermissionStatus
import app.brujula.settings.RoutineScreen
import app.brujula.settings.RoutineViewModel
import app.brujula.voice.AndroidSpeechInput
import app.brujula.voice.VoiceScreen
import app.brujula.voice.VoiceViewModel
import app.brujula.today.TodayScreen
import app.brujula.today.TodayViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime

object Routes {
    const val TODAY = "today"
    const val HABITS = "habits"
    const val ROUTINE = "routine"
    const val VOICE = "voice"
    const val ACTIVITY_FORM = "activity?id={id}&start={start}"
    const val HABIT_FORM = "habit?id={id}&group={group}"
    const val PROPOSAL = "proposal?day={day}&request={request}"

    /** `day` es ISO; `request` (opcional) se codifica como URL y se decodifica al abrir la pantalla. */
    fun proposal(day: LocalDate, request: String? = null): String {
        val q = request?.takeIf { it.isNotBlank() }?.let { "&request=" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") }.orEmpty()
        return "proposal?day=$day$q"
    }

    /** `start` es un LocalDateTime ISO; ambos son opcionales. */
    fun activityForm(id: Long? = null, start: LocalDateTime? = null): String {
        val args = listOfNotNull(
            id?.let { "id=$it" },
            start?.let { "start=${Uri.encode(it.toString())}" },
        )
        return if (args.isEmpty()) "activity" else "activity?" + args.joinToString("&")
    }

    fun habitForm(id: Long? = null, group: HabitGroup = HabitGroup.ESSENTIAL): String {
        val args = listOfNotNull(
            id?.let { "id=$it" },
            "group=${group.name}".takeIf { group != HabitGroup.ESSENTIAL },
        )
        return if (args.isEmpty()) "habit" else "habit?" + args.joinToString("&")
    }
}

private val TopLevel = setOf(Routes.TODAY, Routes.HABITS, Routes.ROUTINE)

/**
 * [pendingRoute]: ruta de un enlace de notificación (ya filtrada por la lista blanca de [DeepLinks]); se navega a ella al
 * componer y se avisa con [onRouteConsumed] para no repetirla al rotar.
 */
@Composable
fun BrujulaNavHost(
    navController: NavHostController = rememberNavController(),
    pendingRoute: StateFlow<String?> = remember { MutableStateFlow<String?>(null) },
    onRouteConsumed: () -> Unit = {},
) {
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route in TopLevel
    val linkContainer = LocalContext.current.container
    val pending by pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        val raw = pending ?: return@LaunchedEffect
        val nav = DeepLinks.toNavRoute(raw, logicalDay(linkContainer.clock.now()).plusDays(1))
        onRouteConsumed()
        when (nav) {
            null -> Unit
            Routes.TODAY -> navController.navigateTop(Routes.TODAY)
            else -> navController.navigate(nav) {
                launchSingleTop = true
                // Una Propuesta abierta de otro día no se reutiliza: sus argumentos no cambiarían.
                if (nav.startsWith("proposal")) popUpTo(Routes.PROPOSAL) { inclusive = true }
            }
        }
    }

    Scaffold(
        containerColor = BrujulaTheme.colors.bg,
        bottomBar = {
            if (showBar) {
                BottomBar(
                    currentRoute = route,
                    onToday = { navController.navigateTop(Routes.TODAY) },
                    onHabits = { navController.navigateTop(Routes.HABITS) },
                    onSettings = { navController.navigateTop(Routes.ROUTINE) },
                    onVoice = { navController.navigate(Routes.VOICE) { launchSingleTop = true } },
                    onAdd = { navController.navigate(Routes.activityForm()) },
                )
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Routes.TODAY, modifier = Modifier.padding(padding)) {
            composable(Routes.TODAY) {
                val context = LocalContext.current
                val container = context.container
                val appContext = context.applicationContext
                val vm: TodayViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            TodayViewModel(
                                clock = container.clock,
                                routineRepository = container.routineRepository,
                                activityRepository = container.activityRepository,
                                habitRepository = container.habitRepository,
                                readPermissions = { readPermissionStatus(appContext) },
                                calendarSource = container.calendarSource,
                                classifications = container.workClassificationRepository,
                                autoClassifier = container.autoClassifier,
                                claudeNotes = container.claudeNotes.note,
                                acceptedPlans = container.proposalRepository.observeAcceptedPlans(),
                                undoPlan = { container.proposalExecutor.undo(it) },
                            )
                        }
                    },
                )
                TodayScreen(
                    viewModel = vm,
                    onEditActivity = { navController.navigate(Routes.activityForm(id = it)) },
                    onAddAt = { navController.navigate(Routes.activityForm(start = it)) },
                    onAskClaude = { navController.navigate(Routes.proposal(it)) },
                )
            }
            composable(
                Routes.PROPOSAL,
                arguments = listOf(
                    navArgument("day") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("request") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                val context = LocalContext.current
                val container = context.container
                val day = entry.arguments?.getString("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?: logicalDay(container.clock.now())
                val request = entry.arguments?.getString("request")?.takeIf { it.isNotBlank() && it.length <= DeepLinks.MAX_REQUEST }
                val vm: ProposalViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            ProposalViewModel(
                                day = day,
                                request = request,
                                planner = container.dayPlanner,
                                proposals = container.proposalRepository,
                                executor = container.proposalExecutor,
                                speech = AndroidSpeechInput(context.applicationContext),
                                clock = container.clock,
                                wakeFor = { d -> container.routineRepository.routine.first().wakeFor(d) },
                                saved = createSavedStateHandle(),
                            )
                        }
                    },
                )
                ProposalScreen(
                    viewModel = vm,
                    onClose = { if (navController.currentDestination?.route == Routes.PROPOSAL) navController.popBackStack() },
                )
            }
            composable(Routes.HABITS) {
                val container = LocalContext.current.container
                val vm: HabitsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            HabitsViewModel(
                                clock = container.clock,
                                routineRepository = container.routineRepository,
                                activityRepository = container.activityRepository,
                                habitRepository = container.habitRepository,
                                resync = container.reminderSync,
                            )
                        }
                    },
                )
                HabitsScreen(
                    viewModel = vm,
                    onNewHabit = { navController.navigate(Routes.habitForm()) },
                    onNewWellness = { navController.navigate(Routes.habitForm(group = HabitGroup.WELLNESS)) },
                    onEditHabit = { navController.navigate(Routes.habitForm(it)) },
                )
            }
            composable(Routes.VOICE) {
                val context = LocalContext.current
                val container = context.container
                val vm: VoiceViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            VoiceViewModel(
                                speech = AndroidSpeechInput(context.applicationContext),
                                interpreter = container.interpreter,
                                executor = container.executor,
                                pending = container.pendingDictations,
                                requestDrain = { container.syncScope.launch { runCatching { container.drainPendingDictations() } } },
                            )
                        }
                    },
                )
                VoiceScreen(
                    viewModel = vm,
                    // Dos toques rápidos en ✕/Listo no sacan también la pantalla de abajo.
                    onClose = { if (navController.currentDestination?.route == Routes.VOICE) navController.popBackStack() },
                    onWriteInstead = { navController.navigate(Routes.activityForm()) },
                )
            }
            composable(Routes.ROUTINE) {
                val container = LocalContext.current.container
                val vm: RoutineViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            RoutineViewModel(
                                container.routineRepository, container.reminderSync,
                                calendarSource = container.calendarSource,
                                syncScope = container.syncScope,
                                reconcileGoogle = { container.reconcileGoogleCopies() },
                                claudeKeyStore = container.claudeKeyStore,
                                claudeGateway = container.claudeGateway,
                                onKeySaved = { container.syncScope.launch { runCatching { container.drainPendingDictations() } } },
                            )
                        }
                    },
                )
                RoutineScreen(viewModel = vm)
            }
            composable(
                Routes.ACTIVITY_FORM,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("start") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                val container = LocalContext.current.container
                val id = entry.arguments?.getLong("id")?.takeIf { it >= 0 }
                val start = entry.arguments?.getString("start")?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                val vm: ActivityFormViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            ActivityFormViewModel(
                                activityId = id,
                                prefillStart = start,
                                clock = container.clock,
                                activityRepository = container.activityRepository,
                                resync = container.reminderSync,
                                calendarSource = container.calendarSource,
                                selectedCalendarIds = container.routineRepository.calendarPrefs.selectedCalendarIds,
                                googleCopy = container.googleCopySync,
                                googleScope = container.syncScope,
                                googleJournal = container.googleCopyJournal,
                            )
                        }
                    },
                )
                ActivityFormScreen(
                    viewModel = vm,
                    onClose = { navController.popBackStack() },
                    onHabit = {
                        navController.navigate(Routes.habitForm()) { popUpTo(entry.destination.id) { inclusive = true } }
                    },
                )
            }
            composable(
                Routes.HABIT_FORM,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("group") { type = NavType.StringType; defaultValue = HabitGroup.ESSENTIAL.name },
                ),
            ) { entry ->
                val container = LocalContext.current.container
                val id = entry.arguments?.getLong("id")?.takeIf { it >= 0 }
                val group = parseHabitGroup(entry.arguments?.getString("group"))
                val vm: HabitFormViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            HabitFormViewModel(
                                habitId = id,
                                group = group,
                                habitRepository = container.habitRepository,
                                resync = container.reminderSync,
                                routineProvider = { container.routineRepository.routine.first() },
                            )
                        }
                    },
                )
                HabitFormScreen(viewModel = vm, onClose = { navController.popBackStack() })
            }
        }
    }
}

private fun NavHostController.navigateTop(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
