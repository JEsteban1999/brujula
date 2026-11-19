package app.brujula.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.brujula.core.theme.BrujulaTheme

/** Botón cuadrado de 48 dp (− / +) con escala al presionar. */
@Composable
fun SquareButton(symbol: String, description: String, reduced: Boolean, onClick: () -> Unit) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.size(48.dp).pressScale(source, reduced).clip(RoundedCornerShape(12.dp)).background(c.surface2)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(symbol, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
}

/** Etiqueta, − valor +. */
@Composable
fun StepperRow(
    label: String, value: String, minusDesc: String, plusDesc: String, reduced: Boolean,
    onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier,
) {
    val c = BrujulaTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.weight(1f), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        SquareButton("−", minusDesc, reduced, onMinus)
        Text(
            value, Modifier.width(64.dp), textAlign = TextAlign.Center, color = c.text,
            fontFamily = BrujulaTheme.type.display, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp,
        )
        SquareButton("+", plusDesc, reduced, onPlus)
    }
}

/** Opción de un selector segmentado (el contenedor lleva fondo `surface2`). */
@Composable
fun Segment(label: String, selected: Boolean, reduced: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.height(48.dp).pressScale(source, reduced).clip(RoundedCornerShape(11.dp))
            .background(if (selected) c.accent else Color.Transparent)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (selected) c.onAccent else c.textMuted, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold,
            maxLines = 1, textAlign = TextAlign.Center,
        )
    }
}
