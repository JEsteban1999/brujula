package app.brujula.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.brujula.container
import app.brujula.core.theme.BrujulaTheme
import app.brujula.reminders.readPermissionStatus
import app.brujula.today.TodayScreen
import app.brujula.today.TodayViewModel
import java.time.LocalDateTime

object Routes {
    const val TODAY = "today"
    const val HABITS = "habits"
    const val ROUTINE = "routine"
    const val ACTIVITY_FORM = "activity?id={id}&start={start}"
    const val HABIT_FORM = "habit?id={id}"

    /** `start` es un LocalDateTime ISO; ambos son opcionales. */
    fun activityForm(id: Long? = null, start: LocalDateTime? = null): String {
        val args = listOfNotNull(
            id?.let { "id=$it" },
            start?.let { "start=${Uri.encode(it.toString())}" },
        )
        return if (args.isEmpty()) "activity" else "activity?" + args.joinToString("&")
    }

    fun habitForm(id: Long? = null): String = if (id == null) "habit" else "habit?id=$id"
}

private val TopLevel = setOf(Routes.TODAY, Routes.HABITS, Routes.ROUTINE)

@Composable
fun BrujulaNavHost(navController: NavHostController = rememberNavController()) {
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route in TopLevel

    Scaffold(
        containerColor = BrujulaTheme.colors.bg,
        bottomBar = {
            if (showBar) {
                BottomBar(
                    currentRoute = route,
                    onToday = { navController.navigateTop(Routes.TODAY) },
                    onHabits = { navController.navigateTop(Routes.HABITS) },
                    onSettings = { navController.navigateTop(Routes.ROUTINE) },
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
                            )
                        }
                    },
                )
                TodayScreen(
                    viewModel = vm,
                    onEditActivity = { navController.navigate(Routes.activityForm(id = it)) },
                    onAddAt = { navController.navigate(Routes.activityForm(start = it)) },
                )
            }
            composable(Routes.HABITS) { PlaceholderScreen() }
            composable(Routes.ROUTINE) { PlaceholderScreen() }
            composable(
                Routes.ACTIVITY_FORM,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("start") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { PlaceholderScreen() }
            composable(
                Routes.HABIT_FORM,
                arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L }),
            ) { PlaceholderScreen() }
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
