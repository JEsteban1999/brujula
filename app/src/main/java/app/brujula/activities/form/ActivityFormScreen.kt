package app.brujula.activities.form

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.core.theme.BrujulaTheme
import app.brujula.core.time.formatMinutes
import app.brujula.core.time.formatTime
import app.brujula.ui.pressScale
import app.brujula.ui.rememberReducedMotion
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

private val ES = Locale.forLanguageTag("es")
private val PRESETS = listOf(30, 60, 90, 120)
private val TYPE_CHIPS = listOf(ActivityType.WORK, ActivityType.EXERCISE, ActivityType.APPOINTMENT, ActivityType.PERSONAL)

private fun shortDay(d: LocalDate): String =
    "${d.dayOfWeek.getDisplayName(DateTextStyle.SHORT, ES).lowercase(ES).trimEnd('.')} ${d.dayOfMonth}"

@Composable
fun ActivityFormScreen(
    viewModel: ActivityFormViewModel,
    onClose: () -> Unit,
    onHabit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = BrujulaTheme.colors
    val reduced = rememberReducedMotion()
    var confirmDelete by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) { if (state.saved) onClose() }

    Column(
        modifier.fillMaxSize().background(c.bg).imePadding()
            .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)
                    .border(1.dp, c.line, RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = "Cerrar" },
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Text(
                if (state.isEdit) "Editar actividad" else "Nueva actividad",
                Modifier.weight(1f), color = c.text, textAlign = TextAlign.Center,
                fontFamily = BrujulaTheme.type.display, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp,
            )
            // Mismo ancho que el botón de cerrar; el micrófono queda oculto en esta fase.
            Spacer(Modifier.size(44.dp))
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TitleField(state.title, state.titleError, viewModel::setTitle)

            Field("Tipo") {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TYPE_CHIPS.forEach { t ->
                        TypeChip(t.labelEs, dot = typeDot(t), selected = state.type == t, reduced = reduced) { viewModel.setType(t) }
                    }
                    TypeChip("Hábito", dot = okColor(), selected = false, reduced = reduced, onClick = onHabit)
                }
            }

            Field("Cuándo") {
                val today = viewModel.today
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Segment("Hoy", state.dateChoice == DateChoice.TODAY, reduced, Modifier.weight(1f)) { viewModel.setDateChoice(DateChoice.TODAY) }
                    Segment(
                        "Mañana · " + shortDay(today.plusDays(1)),
                        state.dateChoice == DateChoice.TOMORROW, reduced, Modifier.weight(1f),
                    ) { viewModel.setDateChoice(DateChoice.TOMORROW) }
                    Segment(
                        if (state.dateChoice == DateChoice.OTHER) shortDay(state.date) else "Otro día",
                        state.dateChoice == DateChoice.OTHER, reduced, Modifier.weight(1f),
                    ) {
                        viewModel.setDateChoice(DateChoice.OTHER)
                        showPicker = true
                    }
                }
            }

            TimeCard(state, viewModel, reduced)
            ConflictStatus(state)

            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
                    .border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 2.dp),
            ) {
                ToggleRow("Avisarme 15 min antes", null, state.remind, viewModel::setRemind)
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                ToggleRow(
                    "Copiar a Google Calendar",
                    if (state.type == ActivityType.APPOINTMENT) "Las citas se copian por defecto"
                    else "Los bloques de rutina se quedan en Brújula",
                    state.copyToGoogle, viewModel::setCopyToGoogle,
                )
            }

            state.googleNotice?.let { notice ->
                Text(
                    notice, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.soft2)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .semantics { this.liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                )
            }

            if (state.isEdit) {
                if (confirmDelete) {
                    DeleteConfirm(onCancel = { confirmDelete = false }, onConfirm = viewModel::delete)
                } else {
                    TextButton(onClick = { confirmDelete = true }, Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Eliminar", color = c.accent2Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
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
        ) { Text("Guardar actividad", color = c.onAccent, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
    }

    if (showPicker) {
        @OptIn(ExperimentalMaterial3Api::class)
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = state.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        @OptIn(ExperimentalMaterial3Api::class)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let {
                        viewModel.setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancelar") } },
        ) { DatePicker(picker) }
    }
}

@Composable
private fun okColor(): Color = if (BrujulaTheme.colors.bg.luminance() < 0.5f) Color(0xFF5BD69A) else Color(0xFF1F8A5B)

@Composable
private fun typeDot(t: ActivityType): Color {
    val c = BrujulaTheme.colors
    return when (t) {
        ActivityType.WORK -> c.accent
        ActivityType.EXERCISE -> c.accent2
        ActivityType.APPOINTMENT -> c.text
        ActivityType.PERSONAL -> c.textMuted
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
private fun TitleField(value: String, error: String?, onChange: (String) -> Unit) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(16.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("¿Qué vas a hacer?", color = c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true, interactionSource = source,
            textStyle = TextStyle(color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = BrujulaTheme.type.body),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "¿Qué vas a hacer?" },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().height(52.dp).clip(shape).background(c.surface)
                        .border(
                            2.dp,
                            when {
                                error != null -> c.accent2Text
                                focused -> c.accent
                                else -> c.line
                            },
                            shape,
                        )
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text("Ej. Dentista con la Dra. Ruiz", color = c.textMuted, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                    inner()
                }
            },
        )
        if (error != null) Text(error, color = c.accent2Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TypeChip(label: String, dot: Color, selected: Boolean, reduced: Boolean, onClick: () -> Unit) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier.height(38.dp).pressScale(source, reduced).clip(shape)
            .background(if (selected) c.accent else c.surface)
            .border(1.dp, if (selected) Color.Transparent else c.line, shape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (selected) c.onAccent else dot))
        Text(label, color = if (selected) c.onAccent else c.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, reduced: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.height(38.dp).pressScale(source, reduced).clip(RoundedCornerShape(11.dp))
            .background(if (selected) c.surface else Color.Transparent)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (selected) c.text else c.textMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SquareButton(symbol: String, description: String, size: Dp, reduced: Boolean, onClick: () -> Unit) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.size(size).pressScale(source, reduced).clip(RoundedCornerShape(10.dp)).background(c.surface2)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(symbol, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun TimeCard(state: ActivityFormState, vm: ActivityFormViewModel, reduced: Boolean) {
    val c = BrujulaTheme.colors
    var customOpen by remember { mutableStateOf(false) }
    val isCustom = customOpen || state.durationMin !in PRESETS
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Empieza", Modifier.weight(1f), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            SquareButton("−", "15 minutos antes", 34.dp, reduced) { vm.shiftStart(-15) }
            Text(
                formatTime(state.start), Modifier.width(56.dp), textAlign = TextAlign.Center, color = c.text,
                fontFamily = BrujulaTheme.type.display, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp,
            )
            SquareButton("+", "15 minutos después", 34.dp, reduced) { vm.shiftStart(15) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PRESETS.forEach { m ->
                DurationChip(formatMinutes(m), !isCustom && state.durationMin == m, reduced, Modifier.weight(1f)) {
                    customOpen = false
                    vm.setDuration(m)
                }
            }
            DurationChip("Otra", isCustom, reduced, Modifier.weight(1f)) { customOpen = true }
        }
        if (isCustom) CustomDuration(state.durationMin, vm::setDuration)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Termina a las ${formatTime(state.end)} · Repetir: ", color = c.textMuted, fontSize = 12.sp,
            )
            Text(state.repeat.labelEs, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                "cambiar", color = c.accentText, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(role = Role.Button) {
                    val all = Repeat.entries
                    vm.setRepeat(all[(state.repeat.ordinal + 1) % all.size])
                }.padding(8.dp),
            )
        }
    }
}

@Composable
private fun DurationChip(label: String, selected: Boolean, reduced: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.height(36.dp).pressScale(source, reduced).clip(shape)
            .background(if (selected) c.soft else Color.Transparent)
            .border(BorderStroke(1.dp, if (selected) c.accent else c.line), shape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (selected) c.accentText else c.text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** Minutos libres (1–720): un valor vacío o 0 nunca llega al ViewModel. */
@Composable
private fun CustomDuration(current: Int, onChange: (Int) -> Unit) {
    val c = BrujulaTheme.colors
    var text by remember { mutableStateOf(current.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val digits = raw.filter(Char::isDigit).take(3)
                val n = digits.toIntOrNull()
                text = if (n != null && n > MAX_DURATION) MAX_DURATION.toString() else digits
                if (n != null && n >= MIN_DURATION) onChange(n)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.width(72.dp).height(40.dp).clip(RoundedCornerShape(10.dp)).background(c.surface2)
                .semantics { contentDescription = "Duración en minutos" },
            decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { inner() } },
        )
        Text("minutos (1 a 720)", color = c.textMuted, fontSize = 12.sp)
    }
}

@Composable
private fun ConflictStatus(state: ActivityFormState) {
    val dark = BrujulaTheme.colors.bg.luminance() < 0.5f
    val message = state.conflictMessage
    val bg = if (message != null) BrujulaTheme.colors.soft2 else if (dark) Color(0xFF13332A) else Color(0xFFDDF3E8)
    val dot = if (message != null) BrujulaTheme.colors.accent2Text else okColor()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(dot), contentAlignment = Alignment.Center) {
            Text(
                if (message != null) "!" else "✓", color = BrujulaTheme.colors.bg,
                fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
            )
        }
        Text(
            message ?: "Libre a esa hora. No choca con nada.",
            color = BrujulaTheme.colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = BrujulaTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = c.textMuted, fontSize = 11.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = title },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = c.accent, checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White, uncheckedTrackColor = c.surface2, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun DeleteConfirm(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val c = BrujulaTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.soft2).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("¿Eliminar esta actividad?", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel) { Text("Cancelar", color = c.textMuted, fontWeight = FontWeight.Bold) }
            TextButton(onClick = onConfirm) { Text("Sí, eliminar", color = c.accent2Text, fontWeight = FontWeight.ExtraBold) }
        }
    }
}
