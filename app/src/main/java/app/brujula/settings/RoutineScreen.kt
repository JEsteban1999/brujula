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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

    Column(
        modifier.fillMaxSize().background(c.bg).statusBarsPadding().verticalScroll(rememberScrollState())
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
