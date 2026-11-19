package app.brujula.today

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.brujula.core.theme.BrujulaTheme
import app.brujula.core.time.formatMinutes
import app.brujula.habits.HabitIcon
import app.brujula.reminders.PermissionStatus
import app.brujula.ui.pressScale

private val CardShape = RoundedCornerShape(24.dp)

@Composable
fun TodayHeader(state: TodayUiState, reduced: Boolean) {
    val c = BrujulaTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(state.dayLabel, color = c.textMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                "Hoy", color = c.text, fontFamily = BrujulaTheme.type.display, fontWeight = FontWeight.ExtraBold,
                fontSize = 42.sp, letterSpacing = (-1.2).sp, lineHeight = 42.sp,
            )
        }
        WorkSummary(state, reduced)
    }
}

@Composable
private fun WorkSummary(state: TodayUiState, reduced: Boolean) {
    val c = BrujulaTheme.colors
    val goal = "${state.workGoal.first / 60}–${state.workGoal.last / 60} h"
    val fraction = (state.workMinutes.toFloat() / state.workGoal.last).coerceIn(0f, 1f)
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Trabajo hoy", color = c.textMuted, fontSize = 12.sp)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = c.text)) { append(formatMinutes(state.workMinutes)) }
                withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = c.textMuted)) { append(" / $goal") }
            },
            fontSize = 15.sp,
        )
        ProgressBar(fraction, track = c.surface2, fill = c.accent, reduced = reduced, modifier = Modifier.width(96.dp))
    }
}

@Composable
private fun ProgressBar(fraction: Float, track: androidx.compose.ui.graphics.Color, fill: androidx.compose.ui.graphics.Color, reduced: Boolean, modifier: Modifier) {
    val animated by animateFloatAsState(
        fraction,
        if (reduced) tween(0) else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "bar",
    )
    Box(modifier.height(8.dp).clip(RoundedCornerShape(4.dp)).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(animated.coerceIn(0f, 1f)).background(fill))
    }
}

/** Aviso cuando faltan permisos: abre los ajustes que correspondan. */
@Composable
fun PermissionBanner(permissions: PermissionStatus, context: Context) {
    val c = BrujulaTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(CardShape).background(c.text)
            .clickable(role = Role.Button) { openPermissionSettings(context, permissions) }
            .padding(12.dp, 12.dp, 14.dp, 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(c.accent2), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.NotificationsOff, null, tint = c.onAccent2, modifier = Modifier.size(16.dp))
        }
        Text(
            "Los recordatorios no pueden sonar. Toca para activarlos.",
            color = c.bg, fontSize = 13.5.sp, lineHeight = 19.sp,
        )
    }
}

internal fun openPermissionSettings(context: Context, permissions: PermissionStatus) {
    val intent = if (!permissions.notifications) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
    }
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun HabitIcon.vector(): ImageVector = when (this) {
    HabitIcon.WATER -> Icons.Outlined.WaterDrop
    HabitIcon.LIP_BALM -> Icons.Outlined.Spa
    HabitIcon.STRETCH -> Icons.Outlined.SelfImprovement
    HabitIcon.GENERIC -> Icons.Outlined.TaskAlt
}

@Composable
fun HabitCards(habits: List<HabitChip>, reduced: Boolean, onAdd: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        habits.forEachIndexed { i, h ->
            HabitCard(h, warm = i % 2 == 0, reduced = reduced, modifier = Modifier.weight(1f)) { onAdd(h.habitId) }
        }
        if (habits.size == 1) Box(Modifier.weight(1f))
    }
}

@Composable
private fun HabitCard(chip: HabitChip, warm: Boolean, reduced: Boolean, modifier: Modifier, onAdd: () -> Unit) {
    val c = BrujulaTheme.colors
    val bg = if (warm) c.soft2 else c.soft
    val accent = if (warm) c.accent2 else c.accent
    val onAccent = if (warm) c.onAccent2 else c.onAccent
    val label = if (warm) c.accent2Text else c.accentText
    val unit = if (chip.icon == HabitIcon.WATER) "vasos" else "veces"
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressScale(source, reduced)
            .clip(CardShape).background(bg)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onAdd)
            .semantics { contentDescription = "Sumar uno a ${chip.name}, van ${chip.count} de ${chip.target}" }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(chip.icon.vector(), null, tint = label, modifier = Modifier.size(16.dp))
                Text(chip.name, color = BrujulaTheme.colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Box(Modifier.size(28.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Add, null, tint = onAccent, modifier = Modifier.size(16.dp))
            }
        }
        Text(
            buildAnnotatedString {
                append("${chip.count}")
                withStyle(SpanStyle(fontSize = 14.sp, color = c.textMuted, fontWeight = FontWeight.Medium)) {
                    append(" / ${chip.target} $unit")
                }
            },
            color = c.text, fontFamily = BrujulaTheme.type.display, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp,
        )
        ProgressBar(
            (chip.count.toFloat() / chip.target.coerceAtLeast(1)).coerceIn(0f, 1f),
            track = c.surface, fill = accent, reduced = reduced, modifier = Modifier.fillMaxWidth(),
        )
    }
}
