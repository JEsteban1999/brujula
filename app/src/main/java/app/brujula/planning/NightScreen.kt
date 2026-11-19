package app.brujula.planning

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brujula.core.theme.BrujulaTheme
import app.brujula.core.time.formatMinutes
import app.brujula.ui.pressScale
import app.brujula.ui.rememberReducedMotion
import java.time.LocalDate

/**
 * "¿Armamos mañana?" (ArmarManana.dc.html). Sin animaciones continuas: lo unico que se mueve es la escala al presionar un
 * boton, y con "reducir movimiento" tampoco.
 */
@Composable
fun NightScreen(
    viewModel: NightViewModel,
    onArmDay: (LocalDate) -> Unit,
    onAddSomething: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = BrujulaTheme.colors
    val reduced = rememberReducedMotion()
    Column(
        modifier.fillMaxSize().background(c.bg).padding(horizontal = 22.dp).padding(top = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Outlined.NightsStay, contentDescription = null, tint = c.accent2Text, modifier = Modifier.size(40.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state?.timeLabel.orEmpty(), color = c.textMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "¿Armamos mañana?", Modifier.semantics { heading() }, color = c.text, fontFamily = BrujulaTheme.type.display,
                    fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp,
                )
            }
            state?.let { s ->
                TodayCard(s.today)
                TomorrowCard(s.tomorrow)
                BedtimeCard(s, onToggle = { viewModel.setReminder(it) })
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Que Claude arme mi día", reduced) { onArmDay(viewModel.tomorrow) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Agregar algo", reduced, strong = true, Modifier.weight(1f), onAddSomething, mic = true)
                SecondaryButton("Mañana lo veo", reduced, strong = false, Modifier.weight(1f), onClose)
            }
        }
    }
}

@Composable
private fun CardBox(content: @Composable () -> Unit) {
    val c = BrujulaTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun CardTitle(text: String) {
    Text(text.uppercase(), color = BrujulaTheme.colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
private fun TodayCard(today: DaySummary) {
    val c = BrujulaTheme.colors
    CardBox {
        CardTitle("Así te fue hoy")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(formatMinutes(today.workMinutes), "de trabajo", c.text, Modifier.weight(1f))
            val water = if (today.waterCount != null && today.waterTarget != null) "${today.waterCount} / ${today.waterTarget}" else "—"
            Stat(water, "vasos de agua", c.text, Modifier.weight(1f))
            val (label, color) = when (today.exercise) {
                ExerciseState.DONE -> "Hecho" to c.accentText
                ExerciseState.PENDING -> "Pendiente" to c.text
                ExerciseState.NONE -> "No tocaba" to c.textMuted
            }
            Stat(label, "ejercicio", color, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(value: String, label: String, valueColor: Color, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(value, color = valueColor, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = BrujulaTheme.colors.textMuted, fontSize = 12.sp)
    }
}

@Composable
private fun TomorrowCard(items: List<NightItem>) {
    val c = BrujulaTheme.colors
    CardBox {
        CardTitle("Mañana ya tienes")
        if (items.isEmpty()) {
            Text("Mañana aún no tienes nada.", color = c.textMuted, fontSize = 14.sp)
        } else {
            items.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(item.title, Modifier.weight(1f), color = c.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${item.timeLabel} · ${item.sourceLabel}", color = c.textMuted, fontSize = 14.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun BedtimeCard(s: NightUiState, onToggle: (Boolean) -> Unit) {
    val c = BrujulaTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.soft2).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.NightsStay, contentDescription = null, tint = c.accent2Text, modifier = Modifier.size(22.dp))
            Text(s.bedtimeText, Modifier.weight(1f), color = c.text, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        if (s.reminderVisible) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = s.reminderOn, role = Role.Switch, onValueChange = onToggle)
                    .semantics { contentDescription = "Aviso para dormir" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(s.reminderText, Modifier.weight(1f), color = c.text, fontSize = 14.sp)
                Switch(
                    checked = s.reminderOn, onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = c.accent2, checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = c.surface2, uncheckedBorderColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, reduced: Boolean, onClick: () -> Unit) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).pressScale(source, reduced).clip(shape).background(c.accent)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(18.dp))
        Box(Modifier.size(10.dp))
        Text(label, color = c.onAccent, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun SecondaryButton(
    label: String, reduced: Boolean, strong: Boolean, modifier: Modifier, onClick: () -> Unit, mic: Boolean = false,
) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    val fg = if (strong) c.text else c.textMuted
    Row(
        modifier.heightIn(min = 50.dp).pressScale(source, reduced).clip(shape)
            .background(if (strong) c.surface else Color.Transparent).border(1.dp, c.line, shape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (mic) {
            Icon(Icons.Outlined.Mic, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Box(Modifier.size(8.dp))
        }
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
