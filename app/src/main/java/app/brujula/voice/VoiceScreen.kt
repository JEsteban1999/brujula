package app.brujula.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Send
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brujula.core.theme.BrujulaTheme
import app.brujula.ui.pressScale
import app.brujula.ui.rememberReducedMotion
import java.time.LocalDate

@Composable
fun VoiceScreen(
    viewModel: VoiceViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** "Prefiero escribir": abre el formulario manual de actividad (la misma ruta del toque largo). */
    onWriteInstead: () -> Unit = {},
    /** Un cambio o armado de día dictado pasa a la Propuesta (la navegación quita esta pantalla). */
    onOpenProposal: (LocalDate, String) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val hint by viewModel.hint.collectAsStateWithLifecycle()
    val textOnly by viewModel.textOnly.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = BrujulaTheme.colors
    val reduced = rememberReducedMotion()

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.start() else viewModel.onMicDenied()
    }
    val listen = {
        if (!viewModel.voiceAvailable) viewModel.start() // muestra el aviso de que no hay dictado
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) viewModel.start()
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Al volver de Ajustes con el permiso concedido, se recupera el micrófono.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) viewModel.onMicGranted()
    }
    // Empieza a escuchar al abrir, una sola vez (no al rotar).
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!autoStarted) {
            autoStarted = true
            listen()
        }
    }

    (state as? VoiceState.OpenProposal)?.let { open ->
        LaunchedEffect(open) { onOpenProposal(open.day, open.request) }
    }

    Column(
        modifier.fillMaxSize().background(c.bg).imePadding().padding(horizontal = 22.dp).padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
                stepLabel(state), Modifier.weight(1f), color = c.textMuted, textAlign = TextAlign.Center,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(48.dp))
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
            when (val s = state) {
                is VoiceState.Idle -> Idle(reduced, hint, textOnly, listen, onWriteInstead)
                is VoiceState.Listening -> Listening(s.partial, reduced, viewModel::stop)
                VoiceState.Thinking -> Thinking(reduced)
                is VoiceState.OpenProposal -> Thinking(reduced)
                is VoiceState.Result -> ResultView(s, reduced, viewModel::undo, onClose, onOpenProposal)
                VoiceState.Undone -> Message(VoiceCopy.UNDONE, null, reduced, primary = VoiceCopy.DONE, onPrimary = onClose)
                is VoiceState.NeedsMore -> NeedsMore(s.question, reduced, textOnly, listen)
                is VoiceState.Error -> ErrorView(s, hint, reduced, textOnly, listen, viewModel::retry)
                VoiceState.SavedOffline -> Message(
                    VoiceCopy.OFFLINE, null, reduced, primary = VoiceCopy.DONE, onPrimary = onClose,
                )
            }
        }

        if (state is VoiceState.Idle || state is VoiceState.Error || state is VoiceState.NeedsMore || state is VoiceState.Listening) {
            TextEntry(draft, viewModel::setDraft) { viewModel.submitText(draft) }
        }
    }
}

private fun stepLabel(s: VoiceState) = when (s) {
    is VoiceState.Listening -> "Escuchando"
    VoiceState.Thinking, is VoiceState.OpenProposal -> "Interpretando"
    is VoiceState.Result, VoiceState.Undone, VoiceState.SavedOffline -> "Listo"
    else -> "Dictar"
}

@Composable
private fun Orb(reduced: Boolean, onClick: (() -> Unit)?, description: String) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        if (!reduced) {
            val t = rememberInfiniteTransition(label = "orb")
            val s by t.animateFloat(1f, 1.9f, infiniteRepeatable(tween(2000), RepeatMode.Restart), label = "s")
            val a by t.animateFloat(0.6f, 0f, infiniteRepeatable(tween(2000), RepeatMode.Restart), label = "a")
            Box(Modifier.size(150.dp).scale(s).alpha(a).border(BorderStroke(2.dp, c.accent), CircleShape))
        }
        Box(
            Modifier.size(150.dp).pressScale(source, reduced).background(c.accent, CircleShape)
                .then(
                    if (onClick != null) Modifier.clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
                    else Modifier,
                )
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Mic, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(54.dp)) }
    }
}

@Composable
private fun Idle(reduced: Boolean, hint: String?, textOnly: Boolean, onTapMic: () -> Unit, onWriteInstead: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (textOnly) {
            Hint(hint)
            if (hint == VoiceCopy.MIC_DENIED) SettingsLink()
        } else {
            Orb(reduced, onTapMic, "Tocar para dictar")
            Hint(hint)
            Examples()
        }
        WriteInsteadLink(onWriteInstead)
    }
}

@Composable
private fun WriteInsteadLink(onClick: () -> Unit) {
    Text(
        VoiceCopy.PREFER_WRITING, color = BrujulaTheme.colors.accentText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
    )
}

@Composable
private fun SettingsLink() {
    val context = LocalContext.current
    Text(
        VoiceCopy.ENABLE_MIC, color = BrujulaTheme.colors.accentText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            .clickable(role = Role.Button) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            .padding(vertical = 14.dp),
    )
}

@Composable
private fun Hint(hint: String?) {
    if (hint == null) return
    Text(
        hint, color = BrujulaTheme.colors.textMuted, fontSize = 14.sp, textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun Examples() {
    val c = BrujulaTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("TAMBIÉN PUEDES DECIR", color = c.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        listOf("Recuérdame estirarme cada 2 horas", "Mañana a las 7 de la tarde, ejercicio una hora", "Dentista el viernes a las 4:45").forEach {
            Text(
                "\"$it\"", color = c.text, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Listening(partial: String, reduced: Boolean, onStop: () -> Unit) {
    val c = BrujulaTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Orb(reduced, null, VoiceCopy.LISTENING)
        Text(
            if (partial.isEmpty()) VoiceCopy.LISTENING else "\"$partial\"",
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = c.text, textAlign = TextAlign.Center,
            fontFamily = BrujulaTheme.type.display, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp,
        )
        BigButton(VoiceCopy.STOP_TALKING, primary = true, reduced = reduced, onClick = onStop)
    }
}

@Composable
private fun Thinking(reduced: Boolean) {
    val c = BrujulaTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(30.dp)) {
            repeat(3) { i ->
                val y = if (reduced) 0f else {
                    val t = rememberInfiniteTransition(label = "dot$i")
                    val v by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(600 + i * 150), RepeatMode.Reverse), label = "d")
                    v
                }
                Box(Modifier.size(10.dp).alpha(if (reduced) 1f else y).background(c.accent, CircleShape))
            }
        }
        Text(
            VoiceCopy.THINKING, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = c.text, textAlign = TextAlign.Center,
            fontFamily = BrujulaTheme.type.display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp,
        )
    }
}

@Composable
private fun ResultView(
    s: VoiceState.Result, reduced: Boolean, onUndo: () -> Unit, onDone: () -> Unit, onOpenProposal: (LocalDate, String) -> Unit,
) {
    val c = BrujulaTheme.colors
    val scheduled = s.created.isNotEmpty()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (scheduled) {
            Box(
                Modifier.size(72.dp).background(c.accent, CircleShape).align(Alignment.CenterHorizontally),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Check, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(36.dp)) }
        }
        Text(
            s.title,
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, color = c.text, textAlign = TextAlign.Center,
            fontFamily = BrujulaTheme.type.display, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp,
        )
        s.created.forEach { label ->
            Text(
                label, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)
                    .border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(16.dp),
                color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            )
        }
        if (s.reply.isNotBlank()) {
            Text(
                s.reply, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.soft).padding(14.dp),
                color = c.text, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
        s.messages.forEach { Text(it, color = c.textMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth()) }
        s.proposal?.let { p ->
            BigButton(VoiceCopy.VIEW_PROPOSAL, primary = true, reduced = reduced, onClick = { onOpenProposal(p.day, p.text) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.undoAvailable) BigButton(VoiceCopy.UNDO, primary = false, reduced = reduced, onClick = onUndo, modifier = Modifier.weight(1f))
            BigButton(VoiceCopy.DONE, primary = s.proposal == null, reduced = reduced, onClick = onDone, modifier = Modifier.weight(if (s.undoAvailable) 1.4f else 1f))
        }
    }
}

@Composable
private fun Message(title: String, body: String?, reduced: Boolean, primary: String, onPrimary: () -> Unit) {
    val c = BrujulaTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            title, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = c.text, textAlign = TextAlign.Center,
            fontFamily = BrujulaTheme.type.display, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp,
        )
        if (body != null) Text(body, color = c.textMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
        BigButton(primary, primary = true, reduced = reduced, onClick = onPrimary)
    }
}

@Composable
private fun NeedsMore(question: String, reduced: Boolean, textOnly: Boolean, onTapMic: () -> Unit) {
    val c = BrujulaTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            question, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.soft).padding(16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = c.text, fontSize = 16.sp, lineHeight = 22.sp,
        )
        if (!textOnly) Orb(reduced, onTapMic, "Tocar para responder")
    }
}

@Composable
private fun ErrorView(s: VoiceState.Error, hint: String?, reduced: Boolean, textOnly: Boolean, onTapMic: () -> Unit, onRetry: () -> Unit) {
    val c = BrujulaTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            s.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = c.text, textAlign = TextAlign.Center,
            fontFamily = BrujulaTheme.type.display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp,
        )
        if (s.retry && s.message != VoiceCopy.NOT_HEARD && s.message != VoiceCopy.MIC_ERROR) {
            BigButton(VoiceCopy.RETRY, primary = true, reduced = reduced, onClick = onRetry)
        } else if (s.retry && !textOnly) {
            // Por el mismo camino con permiso que el micrófono principal.
            Orb(reduced, onTapMic, "Tocar para intentar de nuevo")
        }
        Hint(hint)
        if (textOnly && hint == VoiceCopy.MIC_DENIED) SettingsLink()
    }
}

@Composable
private fun BigButton(label: String, primary: Boolean, reduced: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = BrujulaTheme.colors
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.fillMaxWidth().height(56.dp).pressScale(source, reduced).clip(RoundedCornerShape(18.dp))
            .background(if (primary) c.accent else c.surface)
            .then(if (primary) Modifier else Modifier.border(1.dp, c.line, RoundedCornerShape(18.dp)))
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) c.onAccent else c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TextEntry(value: String, onChange: (String) -> Unit, onSend: () -> Unit) {
    val c = BrujulaTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = value, onValueChange = onChange,
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                .clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp))
                .semantics { contentDescription = VoiceCopy.WRITE_INSTEAD },
            textStyle = TextStyle(color = c.text, fontSize = 15.sp),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(VoiceCopy.WRITE_INSTEAD, color = c.textMuted, fontSize = 15.sp)
                    inner()
                }
            },
        )
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (value.isBlank()) c.surface2 else c.accent)
                .clickable(enabled = value.isNotBlank(), role = Role.Button, onClick = onSend)
                .semantics { contentDescription = "Enviar" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Send, contentDescription = null, tint = if (value.isBlank()) c.textMuted else c.onAccent, modifier = Modifier.size(22.dp)) }
    }
}
