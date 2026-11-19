package app.brujula.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.brujula.core.theme.ThemeMode
import app.brujula.reminders.ReminderResync
import app.brujula.routine.Routine
import app.brujula.routine.RoutineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RoutineViewModel(
    private val repository: RoutineRepository,
    private val resync: ReminderResync = ReminderResync { },
    scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope = scopeOverride ?: viewModelScope

    val routine: StateFlow<Routine> =
        repository.routine.stateIn(scope, SharingStarted.WhileSubscribed(5_000), Routine())

    fun shiftWake(min: Int): Job = change(resyncAfter = true) { it.copy(wake = it.wake.plusMinutes(min.toLong())) }

    fun shiftBed(min: Int): Job = change(resyncAfter = true) { it.copy(bed = it.bed.plusMinutes(min.toLong())) }

    fun setFlexible(on: Boolean): Job = change { it.copy(flexibleWork = on) }

    fun setGoal(range: IntRange): Job? =
        if (range in GOAL_OPTIONS) change { it.copy(workGoalMin = range) } else null

    fun setThemeMode(m: ThemeMode): Job = change { it.copy(themeMode = m) }

    private fun change(resyncAfter: Boolean = false, transform: (Routine) -> Routine): Job =
        scope.launch {
            repository.update(transform)
            if (resyncAfter) runCatching { resync.resync() }
        }

    companion object {
        val GOAL_OPTIONS = listOf(300..360, 360..420, 420..480)
    }
}
