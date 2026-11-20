package app.brujula.week

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brujula.calendar.ExternalEventSheet
import app.brujula.core.theme.BrujulaColors
import app.brujula.core.theme.BrujulaTheme
import app.brujula.ui.pressScale
import app.brujula.ui.rememberReducedMotion
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime

/** La altura de la barra más alta (8 h) y de la columna del gráfico. */
private const val CHART_MAX_MIN = 480
private val BAR_AREA = 144.dp
private val LETTER_ROW = 26.dp
private val CARD_RADIUS = 20.dp

@Composable
fun WeekScreen(
    viewModel: WeekViewModel,
    onEditActivity: (Long) -> Unit,
    onAddAt: (LocalDateTime?) -> Unit,
    onAskClaude: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val reduced = rememberReducedMotion()
    val c = BrujulaTheme.colors
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshExternal() }

    var openOwnKey by rememberSaveable { mutableStateOf<String?>(null) }
    var openEventKey by rememberSaveable { mutableStateOf<String?>(null) }

    // Al dejar la pantalla (otra pestaña o destino) el aviso se descarta; al rotar se conserva.
    val activity = LocalContext.current.findActivity()
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.dismissMessage() }
    }
    val snackbar = remember { SnackbarHostState() }
    val message = state.message
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = message.text,
            actionLabel = if (message.undo != null) "Deshacer" else null,
            withDismissAction = message.undo == null,
            duration = if (message.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoSkip() else viewModel.dismissMessage()
    }

    Box(modifier.fillMaxSize().background(c.bg)) {
        val data = state.data
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header") {
                WeekHeader(
                    range = data?.rangeLabel.orEmpty(),
                    title = data?.title ?: "Tu semana",
                    canGoBack = state.canGoBack,
                    canGoForward = state.canGoForward,
                    reduced = reduced,
                    onPrevious = viewModel::previousWeek,
                    onNext = viewModel::nextWeek,
                )
            }
            if (data != null) {
                val todayDay = data.days.firstOrNull { it.state == WeekDayState.TODAY }?.day
                item(key = "chart") {
                    ChartCard(data, state.selected, reduced, viewModel::select)
                }
                item(key = "totals") { TotalsRow(data.totals) }
                val selectedDay = data.days.first { it.day == state.selected }
                item(key = "detail-header") {
                    DetailHeader(dayDetailTitle(selectedDay.day, todayDay ?: LocalDate.MIN))
                }
                if (selectedDay.events.isEmpty()) {
                    item(key = "empty") {
                        Text("Nada agendado este día.", color = c.textMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                } else {
                    items(selectedDay.events.size, key = { eventKey(selectedDay.events[it]) }) { i ->
                        EventRow(selectedDay.events[i], reduced) { e ->
                            when (e) {
                                is WeekEvent.Own ->
                                    if (e.canSkip) openOwnKey = ownKey(e) else onEditActivity(e.occurrence.activity.id)
                                is WeekEvent.External -> openEventKey = e.event.key
                            }
                        }
                    }
                }
                item(key = "actions") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onAddAt(viewModel.addStartFor(selectedDay.day)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Agregar a este día") }
                        if (viewModel.canAskClaude(selectedDay.day)) {
                            Button(
                                onClick = { onAskClaude(selectedDay.day) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                            ) { Text("Que Claude arme este día") }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    // Las hojas se resuelven contra el estado vigente: si el evento desaparece o cambia de día, se cierran solas.
    val events = state.data?.days?.firstOrNull { it.day == state.selected }?.events.orEmpty()
    val ownKeyOpen = openOwnKey
    if (ownKeyOpen != null) {
        val own = events.filterIsInstance<WeekEvent.Own>().firstOrNull { ownKey(it) == ownKeyOpen && it.canSkip }
        if (own != null) {
            RecurringSheet(
                event = own,
                onEditSeries = { openOwnKey = null; onEditActivity(own.occurrence.activity.id) },
                onSkip = { openOwnKey = null; viewModel.skipOccurrence(own) },
                onDismiss = { openOwnKey = null },
            )
        } else if (state.data != null) {
            LaunchedEffect(ownKeyOpen) { openOwnKey = null }
        }
    }
    val eventKeyOpen = openEventKey
    if (eventKeyOpen != null) {
        val ext = events.filterIsInstance<WeekEvent.External>().firstOrNull { it.event.key == eventKeyOpen }
        if (ext != null) {
            ExternalEventSheet(
                event = ext.event,
                countsAsWork = ext.countsAsWork,
                onCountsAsWork = { viewModel.setCountsAsWork(ext.event, it) },
                onDismiss = { openEventKey = null },
            )
        } else if (state.data != null) {
            LaunchedEffect(eventKeyOpen) { openEventKey = null }
        }
    }
}

private fun ownKey(e: WeekEvent.Own) = "${e.occurrence.activity.id}:${e.occurrence.day}"

private fun eventKey(e: WeekEvent): String = when (e) {
    is WeekEvent.Own -> "o${ownKey(e)}"
    is WeekEvent.External -> "e${e.event.key}"
}

@Composable
private fun WeekHeader(
    range: String,
    title: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    reduced: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val c = BrujulaTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(range, color = c.textMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                title, color = c.text, fontFamily = BrujulaTheme.type.display, fontWeight = FontWeight.ExtraBold,
                fontSize = 32.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ArrowButton(false, "Semana anterior", canGoBack, reduced, onPrevious)
            ArrowButton(true, "Semana siguiente", canGoForward, reduced, onNext)
        }
    }
}

@Composable
private fun ArrowButton(forward: Boolean, description: String, enabled: Boolean, reduced: Boolean, onClick: () -> Unit) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.size(48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .pressScale(source, reduced)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(14.dp))
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (forward) Icons.AutoMirrored.Outlined.KeyboardArrowRight else Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
            contentDescription = null, tint = c.text, modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun ChartCard(data: WeekData, selected: LocalDate, reduced: Boolean, onPick: (LocalDate) -> Unit) {
    val c = BrujulaTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(CARD_RADIUS)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(CARD_RADIUS))
            .padding(horizontal = 8.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text("Horas de trabajo", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(data.goalLabel, color = c.textMuted, fontSize = 12.sp)
        }
        Box(Modifier.fillMaxWidth().height(BAR_AREA + LETTER_ROW + 20.dp)) {
            // Banda de la meta, bajo las barras. En semanas con todo en descanso se ve igual: es la meta de la rutina.
            val bandBottom = LETTER_ROW + BAR_AREA * (data.goal.first.coerceAtMost(CHART_MAX_MIN) / CHART_MAX_MIN.toFloat())
            val bandHeight = BAR_AREA * ((data.goal.last - data.goal.first).coerceAtLeast(0) / CHART_MAX_MIN.toFloat())
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().offset(y = -bandBottom).height(bandHeight)
                    .background(c.soft2)
                    .drawBehind {
                        val w = 1.dp.toPx()
                        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                        drawLine(c.accent2, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), w, pathEffect = dash)
                        drawLine(c.accent2, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), w, pathEffect = dash)
                    },
            )
            Text(
                "META", color = c.accent2Text, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.BottomEnd).offset(y = -(bandBottom + bandHeight)).padding(end = 4.dp),
            )
            Row(Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(bottom = 0.dp)) {
                data.days.forEachIndexed { i, d ->
                    DayColumn(d, i, d.day == selected, data.monday, reduced, Modifier.weight(1f)) { onPick(d.day) }
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    d: WeekDay,
    index: Int,
    isSelected: Boolean,
    monday: LocalDate,
    reduced: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    // Las barras crecen con resorte al entrar y al cambiar de semana; con "reducir movimiento" aparecen ya del tamaño final.
    val targetHeight = barHeight(d.workMinutes)
    var grown by remember(monday) { mutableStateOf(reduced) }
    LaunchedEffect(monday) {
        if (!reduced) {
            delay(index * 50L)
            grown = true
        }
    }
    val height by animateDpAsState(
        targetValue = if (grown) targetHeight else 0.dp,
        animationSpec = if (reduced) spring(stiffness = Spring.StiffnessHigh) else spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "bar",
    )
    val barHeightNow = if (reduced) targetHeight else height

    Column(
        modifier
            .height(BAR_AREA + LETTER_ROW + 20.dp)
            .pressScale(source, reduced)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = d.a11y
                selected = isSelected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(d.barLabel, color = c.textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Box(Modifier.height(4.dp))
        BarShape(d, barHeightNow, c)
        Box(Modifier.height(LETTER_ROW), contentAlignment = Alignment.Center) {
            Text(
                d.letter,
                color = if (isSelected) c.text else c.textMuted,
                fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
            )
        }
    }
}

private fun barHeight(minutes: Int): Dp =
    maxOf(6.dp, BAR_AREA * (minutes.coerceIn(0, CHART_MAX_MIN) / CHART_MAX_MIN.toFloat()))

/** Pasado = acento 2; hoy = acento; planeado = suave 2 con borde punteado de acento 2; descanso en 0 = superficie 2. */
@Composable
private fun BarShape(d: WeekDay, height: Dp, c: BrujulaColors) {
    val shape = RoundedCornerShape(8.dp)
    val restEmpty = d.rest && d.workMinutes <= 0
    val base = Modifier.padding(horizontal = 3.dp).fillMaxWidth().height(height).clip(shape)
    when {
        restEmpty -> Box(base.background(c.surface2))
        d.state == WeekDayState.PLANNED -> Box(
            base.background(c.soft2).drawBehind {
                drawRoundRect(
                    color = c.accent2, cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            },
        )
        d.state == WeekDayState.TODAY -> Box(base.background(c.accent))
        else -> Box(base.background(c.accent2))
    }
}

@Composable
private fun TotalsRow(t: WeekTotals) {
    val c = BrujulaTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TotalCard(
            Modifier.weight(1f), c.soft2, "Promedio",
            t.workAverage?.let { compactDuration(it) } ?: "–", t.workLabel, c.accent2Text,
            description = "Promedio de trabajo: " + (t.workAverage?.let { durationText(it) } ?: "sin datos") + ", ${t.workLabel}",
        )
        TotalCard(
            Modifier.weight(1f), c.soft, "Ejercicio",
            "${t.exerciseDone} / ${t.exerciseTotal}", "sesiones", c.accentText,
            description = "Ejercicio: ${t.exerciseDone} de ${t.exerciseTotal} sesiones",
        )
        TotalCard(
            Modifier.weight(1f), c.surface2, "Sueño",
            t.sleepAverage?.let { compactDuration(it) } ?: "–", "promedio", c.textMuted,
            description = "Sueño promedio: " + (t.sleepAverage?.let { durationText(it) } ?: "sin datos"),
        )
    }
}

@Composable
private fun TotalCard(modifier: Modifier, bg: Color, label: String, value: String, sub: String, subColor: Color, description: String) {
    val c = BrujulaTheme.colors
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(bg).padding(12.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = c.textMuted, fontSize = 11.sp)
        Text(value, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(sub, color = subColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DetailHeader(title: String) {
    val c = BrujulaTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            title, Modifier.weight(1f, fill = false).semantics { heading() }, color = c.text, fontFamily = BrujulaTheme.type.display,
            fontWeight = FontWeight.ExtraBold, fontSize = 20.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(c.accent, "Brújula")
            Legend(c.accent2, "Google")
            Legend(c.text, "Outlook")
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, color = BrujulaTheme.colors.textMuted, fontSize = 11.sp)
    }
}

@Composable
private fun originColor(origin: EventOrigin): Color {
    val c = BrujulaTheme.colors
    return when (origin) {
        EventOrigin.BRUJULA -> c.accent
        EventOrigin.GOOGLE -> c.accent2
        EventOrigin.OUTLOOK -> c.text
        EventOrigin.OTHER -> c.textMuted
    }
}

@Composable
private fun EventRow(e: WeekEvent, reduced: Boolean, onOpen: (WeekEvent) -> Unit) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    val description = buildString {
        append(e.title).append(", ").append(e.timeLabel).append(", ").append(e.sourceLabel)
        if (e is WeekEvent.External && e.countsAsWork) append(", cuenta como trabajo")
        if (e is WeekEvent.Own && e.canSkip) append(", se repite")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressScale(source, reduced)
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(14.dp))
            .clickable(interactionSource = source, indication = null, role = Role.Button) { onOpen(e) }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(originColor(e.origin)))
        Column(Modifier.weight(1f)) {
            Text(e.title, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (e.origin == EventOrigin.OTHER) Text(e.sourceLabel, color = c.textMuted, fontSize = 11.sp)
        }
        Text(e.timeLabel, color = c.textMuted, fontSize = 12.sp, textAlign = TextAlign.End)
    }
}

/** Hoja de una recurrente sin copia en Google: editar la serie o quitar solo este día. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurringSheet(event: WeekEvent.Own, onEditSeries: () -> Unit, onSkip: () -> Unit, onDismiss: () -> Unit) {
    val c = BrujulaTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        contentColor = c.text,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(event.title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Se repite: ${event.occurrence.activity.repeat.labelEs}", color = c.textMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Button(
                onClick = onEditSeries,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
            ) { Text("Editar la serie") }
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                border = BorderStroke(1.dp, c.line),
            ) { Text("Quitar solo este día", color = c.text) }
        }
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
