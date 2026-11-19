package app.brujula.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.brujula.core.theme.BrujulaTheme

/** Pantalla aun no construida; las tareas siguientes la reemplazan. */
@Composable
fun PlaceholderScreen() {
    Box(Modifier.fillMaxSize().background(BrujulaTheme.colors.bg), contentAlignment = Alignment.Center) {
        Text(
            "Pronto", color = BrujulaTheme.colors.textMuted, fontFamily = BrujulaTheme.type.display,
            fontWeight = FontWeight.SemiBold, fontSize = 24.sp,
        )
    }
}
