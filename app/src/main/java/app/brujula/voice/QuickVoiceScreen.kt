package app.brujula.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brujula.core.theme.BrujulaTheme
import java.time.LocalDate

/**
 * Tarjeta al pie sobre un fondo atenuado. Sin animaciones (respeta "reducir movimiento" por construcción) y con
 * botones de al menos 48 dp. Toda la lógica está en [VoiceViewModel] y [QuickVoicePolicy].
 */
@Composable
fun QuickVoiceScreen(
    viewModel: VoiceViewModel,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
    onOpenProposal: (LocalDate, String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hint by viewModel.hint.collectAsStateWithLifecycle()
    val textOnly by viewModel.textOnly.collectAsStateWithLifecycle()
    val undoing by viewModel.undoing.collectAsStateWithLifecycle()
    val c = BrujulaTheme.colors

    // Se reinicia al cambiar el estado; sin tiempo (escuchando, entendiendo, pregunta, error) no cierra.
    // Con un "Deshacer" en curso se pausa; al terminar (Deshecho o fallo) arranca de nuevo.
    LaunchedEffect(state, undoing) { if (!undoing) QuickVoicePolicy.closeWhenDue(state, onClose) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        // Hermano de la tarjeta (no su padre): así su nodo "Cerrar" no absorbe los textos de la tarjeta.
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Cerrar", onClick = onClose)
                .semantics { contentDescription = "Cerrar" },
        )
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp)
                .clip(RoundedCornerShape(24.dp)).background(c.bg).border(1.dp, c.line, RoundedCornerShape(24.dp))
                // Tocar la tarjeta no la cierra.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
                is VoiceState.Idle -> if (textOnly) Denied(hint, onOpenApp) else Title(QuickVoiceCopy.PREPARING)
                is VoiceState.Listening -> {
                    Title(if (s.partial.isEmpty()) QuickVoiceCopy.LISTENING else "\"${s.partial}\"", mic = true, live = s.partial.isEmpty())
                    Action(VoiceCopy.STOP_TALKING, primary = true, onClick = viewModel::stop)
                }
                VoiceState.Thinking, is VoiceState.OpenProposal -> Title(VoiceCopy.THINKING)
                is VoiceState.Result -> {
                    Title(s.title, check = s.created.isNotEmpty())
                    s.created.forEach { Body(it, strong = true) }
                    if (s.reply.isNotBlank()) Body(s.reply)
                    s.messages.forEach { Body(it) }
                    s.proposal?.let { p -> Action(VoiceCopy.VIEW_PROPOSAL, primary = true) { onOpenProposal(p.day, p.text) } }
                    if (s.undoAvailable) Action(VoiceCopy.UNDO, primary = false, onClick = viewModel::undo)
                }
                VoiceState.Undone -> Title(VoiceCopy.UNDONE)
                is VoiceState.NeedsMore -> {
                    Body(s.question)
                    if (!textOnly) Action(QuickVoiceCopy.ANSWER, primary = true, onClick = viewModel::start)
                    else Action(QuickVoiceCopy.OPEN_APP, primary = true, onClick = onOpenApp)
                }
                is VoiceState.Error -> {
                    Title(QuickVoiceCopy.floating(s.message))
                    if (s.retry && !textOnly) Action(VoiceCopy.RETRY, primary = true, onClick = viewModel::retry)
                    Action(QuickVoiceCopy.OPEN_APP, primary = !s.retry || textOnly, onClick = onOpenApp)
                }
                VoiceState.SavedOffline -> Title(QuickVoiceCopy.SAVED_OFFLINE)
                VoiceState.SavedNeedsKey -> Title(QuickVoiceCopy.SAVED_NO_KEY)
            }
        }
    }
}

@Composable
private fun Denied(hint: String?, onOpenApp: () -> Unit) {
    Title(QuickVoiceCopy.floating(hint ?: VoiceCopy.MIC_DENIED))
    Action(QuickVoiceCopy.OPEN_APP, primary = true, onClick = onOpenApp)
}

@Composable
private fun Title(text: String, mic: Boolean = false, check: Boolean = false, live: Boolean = true) {
    val c = BrujulaTheme.colors
    if (mic || check) {
        Box(Modifier.size(56.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (check) Icons.Outlined.Check else Icons.Outlined.Mic, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(28.dp))
        }
    }
    Text(
        text, Modifier.fillMaxWidth().then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        color = c.text, textAlign = TextAlign.Center,
        fontFamily = BrujulaTheme.type.display, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp,
    )
}

@Composable
private fun Body(text: String, strong: Boolean = false) {
    val c = BrujulaTheme.colors
    Text(
        text, Modifier.fillMaxWidth(), color = if (strong) c.text else c.textMuted, textAlign = TextAlign.Center,
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
    )
}

@Composable
private fun Action(label: String, primary: Boolean, onClick: () -> Unit) {
    val c = BrujulaTheme.colors
    Box(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(RoundedCornerShape(16.dp))
            .background(if (primary) c.accent else c.surface)
            .then(if (primary) Modifier else Modifier.border(1.dp, c.line, RoundedCornerShape(16.dp)))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) c.onAccent else c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 14.dp))
    }
}
