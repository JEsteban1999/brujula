package app.brujula.planning

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brujula.core.theme.BrujulaTheme
import app.brujula.core.time.formatMinutes
import app.brujula.core.time.formatTime
import app.brujula.ui.pressScale
import app.brujula.ui.rememberReducedMotion

private val Examples = listOf("Pasa el gym a la tarde", "Hoy no voy a entrenar")

@Composable
fun ProposalScreen(
    viewModel: ProposalViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** `Listo` tras aceptar: además de cerrar, puede sacar de la pila lo que el plan ya resolvió (el borrador del formulario). */
    onAcceptedDone: () -> Unit = onClose,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = BrujulaTheme.colors
    val reduced = rememberReducedMotion()
    Box(modifier.fillMaxSize().background(c.bg)) {
        when (val s = state) {
            ProposalUiState.Loading -> Loading(reduced, onCancel = viewModel::cancelWaiting)
            is ProposalUiState.Showing -> Showing(s, viewModel, reduced)
            is ProposalUiState.Accepted -> AcceptedView(s, reduced, onUndo = viewModel::undo, onDone = onAcceptedDone)
            is ProposalUiState.Error -> ErrorView(s, reduced, onRetry = viewModel::retry, onClose = onClose)
            ProposalUiState.Closed -> LaunchedEffect(Unit) { onClose() }
        }
    }
}

// ---- esperando a Claude ----

@Composable
private fun Loading(reduced: Boolean, onCancel: () -> Unit) {
    val c = BrujulaTheme.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        // El progreso indeterminado se mueve siempre: con "reducir movimiento" solo queda el texto.
        if (!reduced) CircularProgressIndicator(color = c.accent, strokeWidth = 4.dp, modifier = Modifier.size(44.dp))
        Text(
            ProposalCopy.LOADING, Modifier.padding(top = 20.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = c.text, textAlign = TextAlign.Center, fontFamily = BrujulaTheme.type.display,
            fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp,
        )
        Text(
            "Puede tardar un par de minutos.", Modifier.padding(top = 6.dp), color = c.textMuted, fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        BigButton("Cancelar", primary = false, reduced = reduced, onClick = onCancel, modifier = Modifier.padding(top = 28.dp))
    }
}

// ---- propuesta ----

@Composable
private fun Showing(s: ProposalUiState.Showing, vm: ProposalViewModel, reduced: Boolean) {
    val c = BrujulaTheme.colors
    val p = s.proposal
    Column(
        Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = c.accentText, modifier = Modifier.size(15.dp))
                Text("Propuesta de Claude", color = c.accentText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                s.dayTitle, Modifier.semantics { heading() }, color = c.text, fontFamily = BrujulaTheme.type.display,
                fontWeight = FontWeight.Bold, fontSize = 30.sp,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Total("Trabajo", formatMinutes(p.totals.workMinutes), Modifier.weight(1f))
            Total("Ejercicio", if (p.totals.exerciseMinutes == 0) "—" else formatMinutes(p.totals.exerciseMinutes), Modifier.weight(1f))
            Total("Sueño", formatMinutes(p.totals.sleepMinutes), Modifier.weight(1f))
        }
        if (p.summary.isNotBlank()) {
            Text(
                p.summary, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.soft).padding(horizontal = 14.dp, vertical = 12.dp),
                color = c.text, fontSize = 14.sp, lineHeight = 21.sp,
            )
        }
        s.message?.let {
            Text(
                it, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.soft2).padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp,
            )
        }
        if (p.rejected.isNotEmpty()) Rejected(p.rejected)

        if (p.ops.isEmpty() && !s.stale) {
            Text(
                ProposalCopy.NOTHING_TO_CHANGE, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                color = c.textMuted, fontSize = 14.sp,
            )
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            itemsIndexed(p.preview, key = { i, b -> "$i-${b.title}-${b.span.start}" }) { i, b -> BlockRow(b, i, reduced) }
        }

        val adjusting = s.adjusting
        when {
            adjusting != null -> AdjustPanel(adjusting, s.busy, reduced, vm)
            s.stale -> BigButton("Pedir una nueva", primary = true, reduced = reduced, onClick = vm::requestNew)
            // Nada que cambiar: igual se puede pedir un ajuste.
            p.ops.isEmpty() -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(
                    "Ajustar", primary = false, reduced = reduced, enabled = !s.busy, onClick = vm::openAdjust,
                    modifier = Modifier.weight(1f), icon = Icons.Outlined.Mic,
                )
                BigButton("Listo", primary = true, reduced = reduced, enabled = !s.busy, onClick = vm::discard, modifier = Modifier.weight(1.5f))
            }
            else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(
                    "Ajustar", primary = false, reduced = reduced, enabled = !s.busy, onClick = vm::openAdjust,
                    modifier = Modifier.weight(1f), icon = Icons.Outlined.Mic,
                )
                BigButton(
                    "Aceptar plan", primary = true, reduced = reduced, enabled = !s.busy, onClick = vm::accept,
                    modifier = Modifier.weight(1.5f),
                )
            }
        }
        if (adjusting == null) {
            Box(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable(enabled = !s.busy, role = Role.Button, onClick = vm::discard),
                contentAlignment = Alignment.Center,
            ) { Text("Descartar propuesta", color = c.textMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun Total(label: String, value: String, modifier: Modifier) {
    val c = BrujulaTheme.colors
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = c.textMuted, fontSize = 11.sp)
        Text(value, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun Rejected(lines: List<String>) {
    val c = BrujulaTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(ProposalCopy.REJECTED_TITLE, color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        lines.forEach { Text(it, color = c.textMuted, fontSize = 13.sp, lineHeight = 18.sp) }
    }
}

private fun BlockTag.label(): String? = when (this) {
    BlockTag.NUEVO -> "Nuevo"
    BlockTag.MOVIDO -> "Movido"
    BlockTag.QUITADO -> "Quitado"
    BlockTag.FIJO -> "Fijo"
    BlockTag.LO_DICTASTE -> "Lo dictaste"
    BlockTag.NONE -> null
}

@Composable
private fun BlockRow(b: PreviewBlock, index: Int, reduced: Boolean) {
    val c = BrujulaTheme.colors
    val tag = b.tag.label()
    val (tagBg, tagFg) = when (b.tag) {
        BlockTag.NUEVO -> c.soft to c.accentText
        BlockTag.MOVIDO, BlockTag.LO_DICTASTE -> c.soft2 to c.accent2Text
        else -> c.surface2 to c.textMuted
    }
    val removed = b.tag == BlockTag.QUITADO
    val time = formatTime(b.span.start.toLocalTime())
    Row(
        Modifier.fillMaxWidth().rise(index, reduced).clip(RoundedCornerShape(16.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 9.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("$time, ${b.title}")
                    tag?.let { append(", $it") }
                    append(". ${b.sub}")
                }
            },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(time, Modifier.width(42.dp), color = if (removed) c.textMuted else c.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                b.title, color = if (removed) c.textMuted else c.text, fontSize = 14.5.sp, fontWeight = FontWeight.Bold,
                textDecoration = if (removed) TextDecoration.LineThrough else null,
            )
            Text(b.sub, color = c.textMuted, fontSize = 12.sp)
        }
        if (tag != null) {
            Text(
                tag, Modifier.clip(RoundedCornerShape(999.dp)).background(tagBg).padding(horizontal = 8.dp, vertical = 4.dp),
                color = tagFg, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            )
        }
    }
}

/** Entrada con desplazamiento suave, escalonada; sin animación si se reduce el movimiento. */
@Composable
private fun Modifier.rise(index: Int, reduced: Boolean): Modifier {
    if (reduced) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(durationMillis = 450, delayMillis = (index * 50).coerceAtMost(400))) }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 12.dp.toPx()
    }
}

// ---- ajustar (D9) ----

@Composable
private fun AdjustPanel(a: AdjustState, busy: Boolean, reduced: Boolean, vm: ProposalViewModel) {
    val c = BrujulaTheme.colors
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val send = { val t = text; if (t.isNotBlank()) { text = ""; vm.adjust(t) } }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.adjustVoice() else vm.onMicDenied()
    }
    val listen = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.adjustVoice()
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(20.dp))
            .padding(14.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "¿Qué quieres cambiar?", Modifier.weight(1f).semantics { heading() }, color = c.text,
                fontFamily = BrujulaTheme.type.display, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            )
            Box(
                Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clickable(role = Role.Button, onClick = vm::closeAdjust),
                contentAlignment = Alignment.Center,
            ) { Text("Cerrar", color = c.textMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Examples.forEach { ex ->
                Text(
                    ex, Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(RoundedCornerShape(12.dp)).background(c.soft)
                        .clickable(enabled = !busy, role = Role.Button) { vm.adjust(ex) }.padding(horizontal = 14.dp, vertical = 13.dp),
                    color = c.accentText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (a.speechAvailable) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier.size(56.dp).pressScale(source, reduced).clip(CircleShape).background(if (a.listening) c.accent2 else c.accent)
                        .clickable(interactionSource = source, indication = null, role = Role.Button) { if (a.listening) vm.stopAdjustVoice() else listen() }
                        .semantics { contentDescription = if (a.listening) "Terminar de hablar" else "Hablar el ajuste" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Mic, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(26.dp)) }
                Text(
                    if (a.listening) a.partial.ifBlank { "Escuchando…" } else "Toca el micrófono y dilo",
                    Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, color = c.textMuted, fontSize = 14.sp,
                )
            }
        }
        a.hint?.let {
            Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = c.textMuted, fontSize = 13.sp)
        }
        AdjustField(text, onChange = { text = it }, onSend = send)
    }
}

@Composable
private fun AdjustField(value: String, onChange: (String) -> Unit, onSend: () -> Unit) {
    val c = BrujulaTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = value, onValueChange = onChange,
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).clip(RoundedCornerShape(14.dp)).background(c.bg)
                .border(1.dp, c.line, RoundedCornerShape(14.dp)).semantics { contentDescription = "Escribir el ajuste" },
            textStyle = TextStyle(color = c.text, fontSize = 15.sp),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text("Escribir el ajuste", color = c.textMuted, fontSize = 15.sp)
                    inner()
                }
            },
        )
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (value.isBlank()) c.surface2 else c.accent)
                .clickable(enabled = value.isNotBlank(), role = Role.Button, onClick = onSend)
                .semantics { contentDescription = "Enviar ajuste" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Send, contentDescription = null, tint = if (value.isBlank()) c.textMuted else c.onAccent, modifier = Modifier.size(22.dp)) }
    }
}

// ---- aceptada ----

private val Burst = listOf(
    Triple(-70f, -60f, true), Triple(70f, -55f, false), Triple(-80f, 30f, false), Triple(75f, 40f, true),
    Triple(0f, -85f, false), Triple(-20f, 80f, true), Triple(40f, 78f, false),
)

@Composable
private fun AcceptedView(s: ProposalUiState.Accepted, reduced: Boolean, onUndo: () -> Unit, onDone: () -> Unit) {
    val c = BrujulaTheme.colors
    // La confirmación de los bocetos: el círculo "salta" y siete confetis salen disparados. Con "reducir movimiento" solo
    // se ve el círculo con la marca, sin movimiento.
    val pop = remember { Animatable(if (reduced) 1f else 0.3f) }
    val burst = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) {
        if (!reduced) {
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
        }
    }
    LaunchedEffect(reduced) { if (!reduced) burst.animateTo(1f, tween(900)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            if (!reduced) {
                Burst.forEach { (dx, dy, first) ->
                    val t = burst.value
                    Box(
                        Modifier.size(8.dp).offset(x = Dp(dx * t), y = Dp(dy * t)).rotate(200f * t)
                            .graphicsLayer { alpha = (1f - t).coerceIn(0f, 1f) }
                            .clip(RoundedCornerShape(2.dp)).background(if (first) c.accent else c.accent2),
                    )
                }
            }
            Box(
                Modifier.size(96.dp).scale(pop.value).clip(CircleShape).background(c.accent),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Check, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(48.dp)) }
        }
        Text(
            s.title, Modifier.padding(top = 20.dp).semantics { heading(); liveRegion = LiveRegionMode.Polite }, color = c.text,
            fontFamily = BrujulaTheme.type.display, fontWeight = FontWeight.Bold, fontSize = 30.sp, textAlign = TextAlign.Center,
        )
        Text(
            s.body, Modifier.padding(top = 12.dp), color = c.textMuted, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
        )
        s.message?.let {
            Text(
                it, Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = c.text, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
        }
        BigButton("Listo", primary = true, reduced = reduced, onClick = onDone, modifier = Modifier.padding(top = 28.dp))
        BigButton(
            "Volver a la propuesta", primary = false, reduced = reduced, enabled = !s.busy, onClick = onUndo,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

// ---- error ----

@Composable
private fun ErrorView(s: ProposalUiState.Error, reduced: Boolean, onRetry: () -> Unit, onClose: () -> Unit) {
    val c = BrujulaTheme.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(
            s.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = c.text, textAlign = TextAlign.Center,
            fontFamily = BrujulaTheme.type.display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp,
        )
        if (s.retry) BigButton("Reintentar", primary = true, reduced = reduced, onClick = onRetry, modifier = Modifier.padding(top = 24.dp))
        BigButton("Cerrar", primary = false, reduced = reduced, onClick = onClose, modifier = Modifier.padding(top = 10.dp))
    }
}

// ---- botón grande (56 dp) ----

@Composable
private fun BigButton(
    label: String,
    primary: Boolean,
    reduced: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.fillMaxWidth().height(56.dp).pressScale(source, reduced).clip(shape)
            .background(if (primary) c.accent else c.surface)
            .then(if (primary) Modifier else Modifier.border(1.dp, c.line, shape))
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        val fg: Color = if (primary) c.onAccent else c.text
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Box(Modifier.size(8.dp))
        }
        Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}
