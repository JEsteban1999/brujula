package app.brujula.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.brujula.core.theme.BrujulaTheme

@Composable
fun BottomBar(
    currentRoute: String?,
    onToday: () -> Unit,
    onWeek: () -> Unit,
    onHabits: () -> Unit,
    onSettings: () -> Unit,
    onVoice: () -> Unit,
    onAdd: () -> Unit,
) {
    val c = BrujulaTheme.colors
    Column(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(
            Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem("Hoy", Icons.Outlined.WbSunny, currentRoute == Routes.TODAY, true, onToday)
            NavItem("Semana", Icons.Outlined.CalendarMonth, currentRoute == Routes.WEEK, true, onWeek)
            AddButton(onVoice, onAdd)
            NavItem("Hábitos", Icons.Outlined.CheckCircleOutline, currentRoute == Routes.HABITS, true, onHabits)
            NavItem("Ajustes", Icons.Outlined.Tune, currentRoute == Routes.ROUTINE, true, onSettings)
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    hint: String? = null,
) {
    val c = BrujulaTheme.colors
    val tint = if (selected) c.accentText else c.textMuted
    val source = remember { MutableInteractionSource() }
    val reduced = rememberReducedMotion()
    Column(
        Modifier
            .pressScale(source, reduced)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Tab, onClick = onClick)
            .semantics { if (hint != null) contentDescription = "$label, $hint" }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = tint, fontSize = 11.sp, fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold)
    }
}

/** Boton central: tocar = dictar, mantener = formulario de actividad. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AddButton(onVoice: () -> Unit, onAdd: () -> Unit) {
    val c = BrujulaTheme.colors
    val reduced = rememberReducedMotion()
    val source = remember { MutableInteractionSource() }
    Box(Modifier.offset(y = (-18).dp).size(64.dp), contentAlignment = Alignment.Center) {
        if (!reduced) {
            val t = rememberInfiniteTransition(label = "halo")
            val s by t.animateFloat(0.9f, 1.35f, infiniteRepeatable(tween(2400), RepeatMode.Restart), label = "s")
            val a by t.animateFloat(0.6f, 0f, infiniteRepeatable(tween(2400), RepeatMode.Restart), label = "a")
            Box(Modifier.size(64.dp).scale(s).alpha(a).border(BorderStroke(2.dp, c.accent), CircleShape))
        }
        Box(
            Modifier.size(60.dp)
                .pressScale(source, reduced)
                .shadow(10.dp, CircleShape, ambientColor = c.accent, spotColor = c.accent)
                .background(c.accent, CircleShape)
                .combinedClickable(
                    interactionSource = source, indication = null, role = Role.Button,
                    onClickLabel = "Dictar", onClick = onVoice,
                    onLongClickLabel = "Nueva actividad", onLongClick = onAdd,
                )
                .semantics { contentDescription = "Dictar" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Mic, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(28.dp))
        }
    }
}
