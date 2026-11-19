package app.brujula.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brujula.core.theme.BrujulaTheme
import app.brujula.core.time.TimeSpan
import app.brujula.ui.rememberReducedMotion

/** Fila de la lista: actividad o hueco libre, ordenadas por hora de inicio. */
private sealed interface Row {
    val start: java.time.LocalDateTime
    data class Block(val entry: TimelineEntry) : Row { override val start get() = entry.occurrence.span.start }
    data class Free(val slot: TimeSpan) : Row { override val start get() = slot.start }
}

@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    onEditActivity: (Long) -> Unit,
    onAddAt: (java.time.LocalDateTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val reduced = rememberReducedMotion()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermissions() }

    val rows = remember(state.timeline, state.freeSlots) {
        (state.timeline.map<TimelineEntry, Row> { Row.Block(it) } + state.freeSlots.map { Row.Free(it) })
            .sortedBy { it.start }
    }

    LazyColumn(
        modifier.fillMaxSize().background(BrujulaTheme.colors.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") { TodayHeader(state, reduced) }
        if (!state.permissions.ok) {
            item(key = "banner") { PermissionBanner(state.permissions, context) }
        }
        if (state.habits.isNotEmpty()) {
            item(key = "habits") { HabitCards(state.habits, reduced, viewModel::addHabit) }
        }
        items(rows.size, key = { i ->
            when (val r = rows[i]) {
                is Row.Block -> "b${r.entry.occurrence.activity.id}-${r.entry.occurrence.day}"
                is Row.Free -> "f${r.slot.start}"
            }
        }) { i ->
            when (val r = rows[i]) {
                is Row.Block -> TimelineBlock(
                    entry = r.entry, index = i, reduced = reduced,
                    onToggleDone = { viewModel.toggleDone(r.entry.occurrence.activity.id) },
                    onOpen = { onEditActivity(r.entry.occurrence.activity.id) },
                )
                is Row.Free -> FreeSlotRow(r.slot, i, reduced) { onAddAt(r.slot.start) }
            }
        }
    }
}
