package app.brujula.habits

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brujula.core.theme.BrujulaTheme
import app.brujula.core.time.formatDayEs
import app.brujula.core.time.formatTime
import app.brujula.routine.logicalDay
import app.brujula.ui.pressScale
import app.brujula.ui.rememberReducedMotion
import java.time.LocalDateTime

private val WEEK_LETTERS = listOf("L", "M", "X", "J", "V", "S", "D")

@Composable
fun HabitsScreen(
    viewModel: HabitsViewModel,
    onNewHabit: () -> Unit,
    onEditHabit: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = BrujulaTheme.colors
    val reduced = rememberReducedMotion()
    var menuFor by remember { mutableStateOf<Habit?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LazyColumn(
        modifier.fillMaxSize().background(c.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header") { Header(onNewHabit, reduced) }
        items(state.cards, key = { it.habit.id }) { card ->
            val onMenu = { menuFor = card.habit; confirmDelete = false }
            if (card.habit.schedule is HabitSchedule.AtTimes) {
                SlotsCard(card, reduced, onMenu, onTap = viewModel::add, onUndo = viewModel::undo)
            } else {
                RingCard(card, reduced, onMenu, onAdd = { viewModel.add(card.habit.id) }, onUndo = { viewModel.undo(card.habit.id) })
            }
        }
        item(key = "streak") { StreakCard(state, reduced) }
    }

    menuFor?.let { habit ->
        if (!confirmDelete) {
            AlertDialog(
                onDismissRequest = { menuFor = null },
                containerColor = c.surface,
                title = { Text(habit.name, color = c.text, fontWeight = FontWeight.ExtraBold) },
                text = { Text("¿Qué quieres hacer con este hábito?", color = c.textMuted) },
                confirmButton = {
                    TextButton(onClick = { menuFor = null; onEditHabit(habit.id) }) {
                        Text("Editar", color = c.accentText, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Eliminar", color = c.accent2Text, fontWeight = FontWeight.Bold)
                    }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { menuFor = null; confirmDelete = false },
                containerColor = c.surface,
                title = { Text("¿Eliminar ${habit.name}?", color = c.text, fontWeight = FontWeight.ExtraBold) },
                text = { Text("Se borran también sus avisos y su historial.", color = c.textMuted) },
                confirmButton = {
                    TextButton(onClick = { viewModel.delete(habit.id); menuFor = null; confirmDelete = false }) {
                        Text("Sí, eliminar", color = c.accent2Text, fontWeight = FontWeight.ExtraBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { menuFor = null; confirmDelete = false }) {
                        Text("Cancelar", color = c.textMuted, fontWeight = FontWeight.Bold)
                    }
                },
            )
        }
    }
}

@Composable
private fun Header(onNewHabit: () -> Unit, reduced: Boolean) {
    val c = BrujulaTheme.colors
    val day = remember { logicalDay(LocalDateTime.now()) }
    val source = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(formatDayEs(day), color = c.textMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                "Hábitos", color = c.text, fontFamily = BrujulaTheme.type.display,
                fontWeight = FontWeight.ExtraBold, fontSize = 34.sp,
            )
        }
        Row(
            Modifier.defaultMinSize(minHeight = 48.dp).pressScale(source, reduced).clip(RoundedCornerShape(999.dp))
                .background(c.text)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onNewHabit)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("+", color = c.bg, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Text("Nuevo hábito", color = c.bg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardShell(onMenu: () -> Unit, name: String, content: @Composable () -> Unit) {
    val c = BrujulaTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape)
            .combinedClickable(onClick = {}, onLongClick = onMenu, onLongClickLabel = "Editar o eliminar $name")
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun RingCard(card: HabitCard, reduced: Boolean, onMenu: () -> Unit, onAdd: () -> Unit, onUndo: () -> Unit) {
    val c = BrujulaTheme.colors
    val h = card.habit
    val target = h.dailyTarget.coerceAtLeast(1)
    val fraction by animateFloatAsState(
        targetValue = (card.count.toFloat() / target).coerceIn(0f, 1f),
        animationSpec = if (reduced) snap() else tween(700),
        label = "ring",
    )
    val addSource = remember { MutableInteractionSource() }
    val every = (h.schedule as? HabitSchedule.Every)?.intervalMin
    CardShell(onMenu, h.name) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(140.dp).semantics { contentDescription = "${card.count} de $target" }, contentAlignment = Alignment.Center) {
                val track = c.soft2
                val progress = c.accent2
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 14.dp.toPx()
                    val inset = stroke / 2
                    val arc = Size(size.width - stroke, size.height - stroke)
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                    if (fraction > 0f) {
                        drawArc(progress, -90f, 360f * fraction, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${card.count}", color = c.text, fontFamily = BrujulaTheme.type.display,
                        fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 40.sp,
                    )
                    Text(
                        "de $target ${if (h.icon == HabitIcon.WATER) "vasos" else "veces"}",
                        color = c.textMuted, fontSize = 12.sp,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(h.name, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                val next = card.nextAt
                val text = buildString {
                    if (next != null) append("Próximo aviso a las ${formatTime(next.toLocalTime())}. ")
                    if (every != null) append("Cada $every min mientras estás despierto.")
                }.trim()
                if (text.isNotEmpty()) Text(text, color = c.textMuted, fontSize = 13.sp, lineHeight = 18.sp)
                Box(
                    Modifier.fillMaxWidth().height(48.dp).pressScale(addSource, reduced).clip(RoundedCornerShape(14.dp))
                        .background(c.accent2)
                        .clickable(interactionSource = addSource, indication = null, role = Role.Button, onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (h.icon == HabitIcon.WATER) "+1 vaso" else "+1",
                        color = c.onAccent2, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                    )
                }
                if (card.count > 0) {
                    Text(
                        "Deshacer el último", color = c.accentText, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp).clickable(role = Role.Button, onClick = onUndo)
                            .padding(vertical = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotsCard(card: HabitCard, reduced: Boolean, onMenu: () -> Unit, onTap: (Long) -> Unit, onUndo: (Long) -> Unit) {
    val c = BrujulaTheme.colors
    val h = card.habit
    CardShell(onMenu, h.name) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(h.name, Modifier.weight(1f), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("${card.count} de ${h.dailyTarget}", color = c.textMuted, fontSize = 13.sp)
            }
            if (card.slots.isEmpty()) {
                Text("Hoy no toca", color = c.textMuted, fontSize = 13.sp)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    card.slots.forEach { (time, done) ->
                        val source = remember { MutableInteractionSource() }
                        val label = formatTime(time)
                        Box(
                            Modifier.weight(1f).height(48.dp).pressScale(source, reduced).clip(RoundedCornerShape(12.dp))
                                .background(if (done) c.accent else c.surface2)
                                .clickable(interactionSource = source, indication = null, role = Role.Button) {
                                    if (done) onUndo(h.id) else onTap(h.id)
                                }
                                .semantics {
                                    contentDescription = if (done) "$label hecho, toca para deshacer" else "$label pendiente, toca para marcar"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (done) "✓ $label" else label,
                                color = if (done) c.onAccent else c.text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreakCard(state: HabitsUiState, reduced: Boolean) {
    val c = BrujulaTheme.colors
    val streak = state.exerciseStreak
    val pulse = rememberInfiniteTransition(label = "flame")
    val flameScale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "flameScale",
    )
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.text).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Flame(c.accent2, Modifier.size(34.dp).scale(if (reduced || streak == 0) 1f else flameScale))
            Column(Modifier.weight(1f)) {
                Text(
                    when (streak) {
                        0 -> "Sin racha todavía"
                        1 -> "1 día seguido"
                        else -> "$streak días seguidos"
                    },
                    color = c.bg, fontFamily = BrujulaTheme.type.display, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp,
                )
                Text(
                    if (streak == 0) "Ejercicio · haz el de hoy para empezar" else "Ejercicio",
                    color = c.bg.copy(alpha = 0.75f), fontSize = 13.sp,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WEEK_LETTERS.forEachIndexed { i, letter ->
                val done = state.weekDone.getOrElse(i) { false }
                Column(
                    Modifier.weight(1f).semantics { contentDescription = "$letter ${if (done) "hecho" else "sin hacer"}" },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(letter, color = c.bg.copy(alpha = 0.7f), fontSize = 11.sp, textAlign = TextAlign.Center)
                    Box(
                        Modifier.size(32.dp).clip(CircleShape)
                            .background(if (done) c.accent2 else Color.Transparent)
                            .border(2.dp, if (done) c.accent2 else c.bg.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (done) Text("✓", color = c.onAccent2, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Flame(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.5f, h * 0.05f)
            cubicTo(w * 0.58f, h * 0.33f, w * 0.86f, h * 0.45f, w * 0.86f, h * 0.68f)
            cubicTo(w * 0.86f, h * 0.88f, w * 0.7f, h * 0.97f, w * 0.5f, h * 0.97f)
            cubicTo(w * 0.3f, h * 0.97f, w * 0.14f, h * 0.88f, w * 0.14f, h * 0.68f)
            cubicTo(w * 0.14f, h * 0.5f, w * 0.3f, h * 0.38f, w * 0.38f, h * 0.3f)
            cubicTo(w * 0.4f, h * 0.42f, w * 0.45f, h * 0.48f, w * 0.5f, h * 0.5f)
            cubicTo(w * 0.46f, h * 0.35f, w * 0.46f, h * 0.2f, w * 0.5f, h * 0.05f)
            close()
        }
        drawPath(p, color)
    }
}
