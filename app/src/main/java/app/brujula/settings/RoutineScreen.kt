package app.brujula.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brujula.core.theme.BrujulaColors
import app.brujula.core.theme.BrujulaTheme
import app.brujula.core.theme.ThemeMode
import app.brujula.core.time.formatTime
import app.brujula.ui.Segment
import app.brujula.ui.StepperRow
import app.brujula.ui.pressScale
import app.brujula.ui.rememberReducedMotion

private val GOAL_LABELS = listOf("5–6 h", "6–7 h", "7–8 h")
private const val STEP = 15

@Composable
fun RoutineScreen(viewModel: RoutineViewModel, modifier: Modifier = Modifier) {
    val routine by viewModel.routine.collectAsStateWithLifecycle()
    val c = BrujulaTheme.colors
    val reduced = rememberReducedMotion()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val claude by viewModel.claude.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        viewModel.onPermissionResult(result[Manifest.permission.READ_CALENDAR] == true)
    }
    // El permiso puede darse o quitarse desde los ajustes del sistema.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshCalendars() }

    Column(
        modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column {
            Text("Claude organiza tu día con esto", color = c.textMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Tu rutina", color = c.text, fontFamily = BrujulaTheme.type.display,
                fontWeight = FontWeight.ExtraBold, fontSize = 32.sp,
            )
        }

        Card {
            StepperRow(
                "Me levanto", formatTime(routine.wake), "Levantarme 15 minutos antes", "Levantarme 15 minutos después", reduced,
                onMinus = { viewModel.shiftWake(-STEP) }, onPlus = { viewModel.shiftWake(STEP) },
            )
            StepperRow(
                "Me acuesto", formatTime(routine.bed), "Acostarme 15 minutos antes", "Acostarme 15 minutos después", reduced,
                onMinus = { viewModel.shiftBed(-STEP) }, onPlus = { viewModel.shiftBed(STEP) },
            )
        }

        Text("FINES DE SEMANA Y FESTIVOS", color = c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Card {
            StepperRow(
                "Me levanto", formatTime(routine.restWake), "Levantarme 15 minutos antes en descanso", "Levantarme 15 minutos después en descanso", reduced,
                onMinus = { viewModel.shiftRestWake(-STEP) }, onPlus = { viewModel.shiftRestWake(STEP) },
            )
            StepperRow(
                "Me acuesto", formatTime(routine.restBed), "Acostarme 15 minutos antes en descanso", "Acostarme 15 minutos después en descanso", reduced,
                onMinus = { viewModel.shiftRestBed(-STEP) }, onPlus = { viewModel.shiftRestBed(STEP) },
            )
            Text(
                "Sin meta de trabajo. El ejercicio es opcional: te pregunto si te animas.",
                color = c.textMuted, fontSize = 12.sp,
            )
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Trabajo flexible", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Preferido: 8–12 y 2–6", color = c.textMuted, fontSize = 12.sp)
                }
                Switch(
                    checked = routine.flexibleWork, onCheckedChange = viewModel::setFlexible,
                    modifier = Modifier.semantics { contentDescription = "Trabajo flexible" },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = c.accent, checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = c.surface2, uncheckedBorderColor = Color.Transparent,
                    ),
                )
            }
            Text("Meta de horas por día", color = c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RoutineViewModel.GOAL_OPTIONS.forEachIndexed { i, range ->
                    Segment(GOAL_LABELS[i], routine.workGoalMin == range, reduced, Modifier.weight(1f)) { viewModel.setGoal(range) }
                }
            }
        }

        Text("CALENDARIOS", color = c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Card {
            if (!calendars.hasPermission) {
                Text(
                    "Brújula lee tus calendarios de Google y Outlook para saber qué huecos tienes libres. Nunca los modifica.",
                    color = c.textMuted, fontSize = 13.sp,
                )
                if (calendars.showSettingsHint) {
                    Text("Activa el permiso de Calendario en los ajustes de la app.", color = c.text, fontSize = 13.sp)
                    val context = LocalContext.current
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(c.accent)
                            .clickable(role = Role.Button) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Abrir ajustes", color = c.onAccent, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(c.accent)
                        .clickable(role = Role.Button) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Conectar calendarios", color = c.onAccent, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                }
            } else {
                if (calendars.groups.isEmpty()) {
                    Text("No encontré calendarios en este teléfono.", color = c.textMuted, fontSize = 13.sp)
                }
                calendars.groups.forEach { group ->
                    Text(
                        if (group.source == group.accountName) group.accountName else "${group.source} · ${group.accountName}",
                        color = c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    group.items.forEach { item ->
                        CalendarRow(item, onToggle = { viewModel.toggleCalendar(item.calendar.id) })
                    }
                }
                val copyOn by viewModel.copyToGoogleEnabled.collectAsStateWithLifecycle()
                SwitchRow(
                    label = "Copiar citas a Google Calendar", checked = copyOn,
                    description = "Copiar citas a Google Calendar", dot = null,
                    onToggle = { viewModel.setCopyToGoogleEnabled(!copyOn) },
                )
                Text(
                    "Para ver Outlook aquí, activa en la app de Outlook la opción de sincronizar calendarios con el teléfono.",
                    color = c.textMuted, fontSize = 12.sp,
                )
            }
        }

        Text("CLAUDE", color = c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        ClaudeCard(claude, onSave = viewModel::saveClaudeKey, onTest = viewModel::testClaudeKey)

        Text("TEMA DE BRÚJULA", color = c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeOption("Claro", ThemeMode.LIGHT, routine.themeMode, reduced, Modifier.weight(1f), viewModel::setThemeMode)
            ThemeOption("Oscuro", ThemeMode.DARK, routine.themeMode, reduced, Modifier.weight(1f), viewModel::setThemeMode)
            ThemeOption("Automático", ThemeMode.AUTO, routine.themeMode, reduced, Modifier.weight(1f), viewModel::setThemeMode)
        }
        Text("Automático es oscuro desde las 21:00 hasta que te levantas.", color = c.textMuted, fontSize = 12.sp)
    }
}

@Composable
private fun CalendarRow(item: CalendarItem, onToggle: () -> Unit) {
    SwitchRow(
        label = item.calendar.name, checked = item.selected, description = "Importar ${item.calendar.name}",
        dot = Color(item.calendar.color or 0xFF000000.toInt()), onToggle = onToggle,
    )
}

/** Fila accesible de 48 dp: toda la fila es el interruptor. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, description: String, dot: Color?, onToggle: () -> Unit) {
    val c = BrujulaTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() })
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (dot != null) Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(dot))
        Text(
            label, Modifier.weight(1f), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = c.accent, checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White, uncheckedTrackColor = c.surface2, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = BrujulaTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun ThemeOption(
    name: String, mode: ThemeMode, current: ThemeMode, reduced: Boolean, modifier: Modifier, onPick: (ThemeMode) -> Unit,
) {
    val c = BrujulaTheme.colors
    val on = mode == current
    val light = BrujulaColors.Light
    val dark = BrujulaColors.Dark
    val bg = if (mode == ThemeMode.DARK) dark.bg else light.bg
    val fg = if (mode == ThemeMode.DARK) dark.text else light.text
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.pressScale(source, reduced).clip(shape).background(bg)
            .border(2.dp, if (on) c.accent else c.line, shape)
            .clickable(interactionSource = source, indication = null, role = Role.Button) { onPick(mode) }
            .semantics { selected = on }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (mode) {
                ThemeMode.LIGHT -> { Swatch(light.accent); Swatch(light.accent2) }
                ThemeMode.DARK -> { Swatch(dark.accent); Swatch(dark.accent2) }
                ThemeMode.AUTO -> { Swatch(light.accent); Swatch(dark.bg) }
            }
        }
        Text(name, color = fg, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun Swatch(color: Color) {
    Box(Modifier.size(18.dp).clip(RoundedCornerShape(6.dp)).background(color).border(1.dp, Color(0x22000000), RoundedCornerShape(6.dp)))
}

@Composable
private fun ClaudeCard(state: ClaudeSectionState, onSave: (String) -> Unit, onTest: () -> Unit) {
    val c = BrujulaTheme.colors
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    Card {
        Text("Clave de la API", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (state.hasKey) {
            Text(
                if (state.lastFour != null) "Clave guardada · …${state.lastFour}" else "Clave guardada",
                color = c.textMuted, fontSize = 12.sp,
            )
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = input, onValueChange = { input = it }, singleLine = true,
                textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                cursorBrush = SolidColor(c.accent),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Clave de la API" },
                decorationBox = { inner ->
                    if (input.isEmpty()) Text("Pega tu clave aquí", color = c.textMuted, fontSize = 14.sp)
                    inner()
                },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClaudeButton("Guardar", Modifier.weight(1f), enabled = input.isNotBlank()) {
                onSave(input)
                input = ""
            }
            ClaudeButton("Probar", Modifier.weight(1f), enabled = state.hasKey && state.status != ClaudeStatus.TESTING, onClick = onTest)
        }
        if (state.hasKey) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onSave("") },
                contentAlignment = Alignment.CenterStart,
            ) { Text("Quitar clave", color = c.textMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
        when (state.status) {
            ClaudeStatus.IDLE -> Unit
            ClaudeStatus.TESTING -> Text("Probando…", color = c.textMuted, fontSize = 13.sp)
            ClaudeStatus.CONNECTED -> Text("Conectado", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            ClaudeStatus.INVALID -> Text("La clave no funciona", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            ClaudeStatus.NETWORK -> Text("Sin conexión. Inténtalo de nuevo.", color = c.textMuted, fontSize = 13.sp)
            ClaudeStatus.ERROR -> Text("No pude probar la clave. Inténtalo de nuevo.", color = c.textMuted, fontSize = 13.sp)
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.claude.com"))) }
            },
            contentAlignment = Alignment.CenterStart,
        ) { Text("Consigue tu clave en platform.claude.com", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun ClaudeButton(label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val c = BrujulaTheme.colors
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(if (enabled) c.accent else c.surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) c.onAccent else c.textMuted, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
    }
}
