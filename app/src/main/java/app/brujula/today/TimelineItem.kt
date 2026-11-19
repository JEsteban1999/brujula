package app.brujula.today

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.brujula.activities.ActivityType
import app.brujula.core.theme.BrujulaTheme
import app.brujula.core.time.TimeSpan
import app.brujula.core.time.formatMinutes
import app.brujula.core.time.formatTime
import app.brujula.ui.pressScale

private val CardShape = RoundedCornerShape(24.dp)

/** Entrada escalonada: sube y aparece con resorte; sin movimiento si se reduce. */
@Composable
fun Modifier.staggeredEntrance(index: Int, reduced: Boolean): Modifier {
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) {
            kotlinx.coroutines.delay((index.coerceAtMost(10) * 60).toLong())
            progress.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow))
        }
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 14.dp.toPx()
        val s = 0.98f + 0.02f * p
        scaleX = s
        scaleY = s
    }
}

@Composable
fun OwnTimelineBlock(
    entry: TimelineBlock.Own,
    index: Int,
    reduced: Boolean,
    onToggleDone: () -> Unit,
    onOpen: () -> Unit,
) {
    val c = BrujulaTheme.colors
    val occ = entry.occurrence
    val a = occ.activity
    val done = occ.done
    val now = entry.isNow && !done
    val bg = when {
        now -> c.accent
        a.type == ActivityType.PERSONAL -> c.soft2
        else -> c.surface
    }
    val fg = if (now) c.onAccent else c.text
    val rail = when {
        now -> c.accent2
        a.type == ActivityType.EXERCISE -> c.accent
        a.type == ActivityType.APPOINTMENT -> c.accent2
        else -> c.line
    }
    val source = remember { MutableInteractionSource() }
    val label = (if (done) "Desmarcar " else "Marcar como hecho: ") + a.title + " " + formatTime(occ.span.start.toLocalTime())

    Row(
        Modifier
            .fillMaxWidth()
            .staggeredEntrance(index, reduced)
            .pressScale(source, reduced)
            .clip(CardShape)
            .background(bg)
            .then(if (now || a.type == ActivityType.PERSONAL) Modifier else Modifier.border(1.dp, c.line, CardShape))
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onOpen)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(44.dp)) {
            Text(formatTime(occ.span.start.toLocalTime()), color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                formatTime(occ.span.end.toLocalTime()), color = fg.copy(alpha = 0.75f),
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
            )
        }
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(rail))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                a.title, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                textDecoration = if (done) TextDecoration.LineThrough else null,
            )
            Text(
                "${a.type.labelEs} · ${formatMinutes(a.durationMin)}",
                color = fg.copy(alpha = 0.8f), fontSize = 12.sp,
            )
        }
        if (now) {
            Text(
                "AHORA", color = c.accentText, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp,
                modifier = Modifier.clip(CircleShape).background(c.onAccent).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        // Zona tactil de 48 dp alrededor del circulo de 24 dp.
        Box(
            Modifier.size(48.dp)
                .clickable(role = Role.Checkbox, onClick = onToggleDone)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            val borderColor = when { now -> c.onAccent; done -> c.accent2; else -> c.line }
            Box(
                Modifier.size(24.dp).clip(CircleShape)
                    .background(if (done) c.accent2 else Color.Transparent)
                    .border(2.dp, borderColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (done) Icon(Icons.Filled.Check, null, tint = c.onAccent2, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** Hueco libre de al menos 30 min: fila punteada que abre el formulario con la hora prellenada. */
@Composable
fun FreeSlotRow(slot: TimeSpan, index: Int, reduced: Boolean, onClick: () -> Unit) {
    val c = BrujulaTheme.colors
    val dash = c.line
    val muted = c.textMuted
    Row(
        Modifier
            .fillMaxWidth()
            .staggeredEntrance(index, reduced)
            .clip(CardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .drawBehind {
                drawRoundRect(
                    color = dash,
                    cornerRadius = CornerRadius(24.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(44.dp)) {
            Text(formatTime(slot.start.toLocalTime()), color = muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(formatTime(slot.end.toLocalTime()), color = muted.copy(alpha = 0.75f), fontSize = 12.sp)
        }
        Text("Libre · tocar para agregar", color = muted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Evento importado (solo lectura): sin círculo de hecho. El riel usa el color del calendario; el texto conserva
 * los colores del tema para mantener el contraste.
 */
@Composable
fun ExternalTimelineBlock(
    block: TimelineBlock.External,
    index: Int,
    reduced: Boolean,
    onOpen: () -> Unit,
) {
    val c = BrujulaTheme.colors
    val e = block.event
    val now = block.isNow
    val bg = if (now) c.accent else c.surface
    val fg = if (now) c.onAccent else c.text
    val rail = if (e.calendarColor != 0) Color(e.calendarColor or 0xFF000000.toInt()) else c.accent
    val source = remember { MutableInteractionSource() }
    val start = formatTime(e.span.start.toLocalTime())
    val end = formatTime(e.span.end.toLocalTime())
    val description = "Evento fijo de ${e.calendarName}: ${e.displayTitle}, de $start a $end" +
        (if (block.countsAsWork) ", cuenta como trabajo" else "")

    Row(
        Modifier
            .fillMaxWidth()
            .staggeredEntrance(index, reduced)
            .pressScale(source, reduced)
            .clip(CardShape)
            .background(bg)
            .then(if (now) Modifier else Modifier.border(1.dp, c.line, CardShape))
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 12.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(44.dp)) {
            Text(start, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(end, color = fg.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(rail))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(e.displayTitle, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "Fijo · ${e.calendarName}" + if (block.countsAsWork) " · Trabajo" else "",
                color = fg.copy(alpha = 0.8f), fontSize = 12.sp,
            )
        }
        if (now) {
            Text(
                "AHORA", color = c.accentText, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp,
                modifier = Modifier.clip(CircleShape).background(c.onAccent).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Evento de todo el día: fila compacta arriba de la línea de tiempo. */
@Composable
fun AllDayRow(event: app.brujula.calendar.ExternalEvent, onOpen: () -> Unit) {
    val c = BrujulaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.soft2)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Todo el día · ${event.displayTitle}", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
