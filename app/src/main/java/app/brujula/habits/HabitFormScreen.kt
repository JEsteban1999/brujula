package app.brujula.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brujula.core.theme.BrujulaTheme
import app.brujula.core.time.formatTime
import app.brujula.ui.StepperRow
import app.brujula.ui.pressScale
import app.brujula.ui.rememberReducedMotion
import java.time.DayOfWeek
import java.time.LocalTime

private val ICONS = listOf(
    HabitIcon.WATER to "Agua", HabitIcon.LIP_BALM to "Bálsamo",
    HabitIcon.STRETCH to "Estirar", HabitIcon.GENERIC to "Otro",
)
private val DAY_LABELS = listOf(
    DayOfWeek.MONDAY to "L", DayOfWeek.TUESDAY to "M", DayOfWeek.WEDNESDAY to "X", DayOfWeek.THURSDAY to "J",
    DayOfWeek.FRIDAY to "V", DayOfWeek.SATURDAY to "S", DayOfWeek.SUNDAY to "D",
)
private val DAY_NAMES = mapOf(
    DayOfWeek.MONDAY to "lunes", DayOfWeek.TUESDAY to "martes", DayOfWeek.WEDNESDAY to "miércoles",
    DayOfWeek.THURSDAY to "jueves", DayOfWeek.FRIDAY to "viernes", DayOfWeek.SATURDAY to "sábado",
    DayOfWeek.SUNDAY to "domingo",
)
private const val INTERVAL_STEP = 15

@Composable
fun HabitFormScreen(viewModel: HabitFormViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = BrujulaTheme.colors
    val reduced = rememberReducedMotion()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved, state.notFound) { if (state.saved || state.notFound) onClose() }

    Column(
        modifier.fillMaxSize().background(c.bg).imePadding()
            .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)
                    .border(1.dp, c.line, RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = "Cerrar" },
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Text(
                if (state.isEdit) "Editar hábito" else "Nuevo hábito",
                Modifier.weight(1f), color = c.text, textAlign = TextAlign.Center,
                fontFamily = BrujulaTheme.type.display, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp,
            )
            Spacer(Modifier.size(48.dp))
        }

        Box(Modifier.weight(1f)) {
        Column(
            Modifier.fillMaxSize().alpha(if (state.ready) 1f else 0.5f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            NameField(state.name, state.nameError, viewModel::setName)

            Field("Ícono") {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ICONS.forEach { (icon, label) ->
                        Chip(label, state.icon == icon, reduced) { viewModel.setIcon(icon) }
                    }
                }
            }

            Field("Cuándo avisar") {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Cada cierto tiempo", state.mode == HabitMode.EVERY, reduced) { viewModel.setMode(HabitMode.EVERY) }
                    Chip("A horas fijas", state.mode == HabitMode.AT_TIMES, reduced) { viewModel.setMode(HabitMode.AT_TIMES) }
                    Chip("Antes de dormir", state.mode == HabitMode.BEFORE_BED, reduced) { viewModel.setMode(HabitMode.BEFORE_BED) }
                }
            }

            if (state.mode == HabitMode.BEFORE_BED) {
                Card {
                    StepperRow(
                        "Antes de dormir", "${state.beforeBedMin} min", "15 minutos menos", "15 minutos más", reduced,
                        onMinus = { viewModel.setBeforeBedMin(state.beforeBedMin - INTERVAL_STEP) },
                        onPlus = { viewModel.setBeforeBedMin(state.beforeBedMin + INTERVAL_STEP) },
                    )
                    Text(
                        "Un aviso al día, de $MIN_BEFORE_BED_MIN a $MAX_BEFORE_BED_MIN minutos antes de tu hora de dormir.",
                        color = c.textMuted, fontSize = 12.sp,
                    )
                }
            } else if (state.mode == HabitMode.EVERY) {
                Card {
                    StepperRow(
                        "Cada", "${state.intervalMin} min", "15 minutos menos", "15 minutos más", reduced,
                        onMinus = { viewModel.setIntervalMin((state.intervalMin - INTERVAL_STEP).coerceAtLeast(MIN_INTERVAL_MIN)) },
                        onPlus = { viewModel.setIntervalMin((state.intervalMin + INTERVAL_STEP).coerceAtMost(MAX_INTERVAL_MIN)) },
                    )
                    Text(
                        "Solo mientras estás despierto. De $MIN_INTERVAL_MIN a $MAX_INTERVAL_MIN minutos.",
                        color = c.textMuted, fontSize = 12.sp,
                    )
                    ErrorText(state.intervalError)
                }
            } else {
                TimesCard(state, viewModel, reduced)
            }

            if (state.mode != HabitMode.BEFORE_BED) {
                Card {
                    WorkOnlyRow(state.workOnly) { viewModel.setWorkOnly(it) }
                }
            }

            Card {
                if (state.mode == HabitMode.EVERY) {
                    StepperRow(
                        "Meta del día", "${state.dailyTarget}", "Una menos", "Una más", reduced,
                        onMinus = { viewModel.setDailyTarget(state.dailyTarget - 1) },
                        onPlus = { viewModel.setDailyTarget(state.dailyTarget + 1) },
                    )
                    Text(
                        "Al llegar a la meta, se pausan los avisos del día.",
                        color = c.textMuted, fontSize = 12.sp,
                    )
                } else if (state.mode == HabitMode.BEFORE_BED) {
                    Text("Meta del día: 1", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Una vez al día.", color = c.textMuted, fontSize = 12.sp)
                } else {
                    // A horas fijas la meta es una por cada hora: no se edita.
                    Text(
                        "Meta del día: ${state.times.size}",
                        color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    )
                    Text("Una por cada hora que agregues.", color = c.textMuted, fontSize = 12.sp)
                }
            }

            Field("Días") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAY_LABELS.forEach { (d, label) ->
                        val on = d in state.days
                        DayChip(label, DAY_NAMES.getValue(d), on, reduced, Modifier.weight(1f)) { viewModel.toggleDay(d) }
                    }
                }
                ErrorText(state.daysError)
            }

            if (state.isEdit) {
                if (confirmDelete) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.soft2).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("¿Eliminar este hábito?", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { confirmDelete = false }) {
                                Text("Cancelar", color = c.textMuted, fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = viewModel::delete, enabled = !state.saving) {
                                Text("Sí, eliminar", color = c.accent2Text, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                } else {
                    TextButton(onClick = { confirmDelete = true }, Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Eliminar", color = c.accent2Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }

        if (!state.ready) {
            // Bloquea toques mientras carga el hábito a editar.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
        }
        }

        val canSave = state.ready && !state.saving
        val saveSource = remember { MutableInteractionSource() }
        Box(
            Modifier.fillMaxWidth().height(56.dp).pressScale(saveSource, reduced)
                .clip(RoundedCornerShape(18.dp)).background(if (canSave) c.accent else c.accent.copy(alpha = 0.4f))
                .clickable(
                    enabled = canSave, interactionSource = saveSource, indication = null,
                    role = Role.Button, onClick = viewModel::save,
                ),
            contentAlignment = Alignment.Center,
        ) { Text("Guardar hábito", color = c.onAccent, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = BrujulaTheme.colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) Text(error, color = BrujulaTheme.colors.accent2Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = BrujulaTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun NameField(value: String, error: String?, onChange: (String) -> Unit) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Nombre del hábito", color = c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true, interactionSource = source,
            textStyle = TextStyle(color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = BrujulaTheme.type.body),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Nombre del hábito" },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().height(52.dp).clip(shape).background(c.surface)
                        .border(2.dp, if (error != null) c.accent2Text else if (focused) c.accent else c.line, shape)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text("Ej. Tomar vitaminas", color = c.textMuted, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                    inner()
                }
            },
        )
        ErrorText(error)
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, reduced: Boolean, onClick: () -> Unit) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier.height(48.dp).pressScale(source, reduced).clip(shape)
            .background(if (selected) c.accent else c.surface)
            .border(1.dp, if (selected) Color.Transparent else c.line, shape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) c.onAccent else c.text, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun DayChip(label: String, name: String, on: Boolean, reduced: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.height(48.dp).pressScale(source, reduced).clip(RoundedCornerShape(12.dp))
            .background(if (on) c.accent else c.surface2)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.selected = on; contentDescription = name },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) c.onAccent else c.text, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun TimesCard(state: HabitFormState, vm: HabitFormViewModel, reduced: Boolean) {
    val c = BrujulaTheme.colors
    var candidate by remember { mutableStateOf(LocalTime.of(12, 0)) }
    Card {
        Text("Horas de aviso", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (state.times.isEmpty()) {
            Text("Todavía no hay horas.", color = c.textMuted, fontSize = 12.sp)
        } else {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.times.forEach { t ->
                    val label = formatTime(t)
                    Row(
                        Modifier.defaultMinSize(minHeight = 48.dp).clip(RoundedCornerShape(999.dp)).background(c.soft)
                            .clickable(role = Role.Button, onClick = { vm.removeTime(t) })
                            .semantics { contentDescription = "Quitar $label" }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(label, color = c.accentText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("✕", color = c.accentText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        StepperRow(
            "Nueva hora", formatTime(candidate), "15 minutos antes", "15 minutos después", reduced,
            onMinus = { candidate = candidate.minusMinutes(INTERVAL_STEP.toLong()) },
            onPlus = { candidate = candidate.plusMinutes(INTERVAL_STEP.toLong()) },
        )
        TextButton(onClick = { vm.addTime(candidate) }, Modifier.defaultMinSize(minHeight = 48.dp)) {
            Text("Agregar ${formatTime(candidate)}", color = c.accentText, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
        }
        ErrorText(state.timesError)
    }
}

@Composable
private fun WorkOnlyRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = BrujulaTheme.colors
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Solo en horario de trabajo", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Solo en días laborales y dentro de tus bloques de trabajo.", color = c.textMuted, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent, checkedTrackColor = c.accent,
                uncheckedThumbColor = c.textMuted, uncheckedTrackColor = c.surface2, uncheckedBorderColor = c.line,
            ),
        )
    }
}
