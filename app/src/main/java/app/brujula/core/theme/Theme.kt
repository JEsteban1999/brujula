package app.brujula.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalColors = staticCompositionLocalOf { BrujulaColors.Light }
private val LocalType = staticCompositionLocalOf { BrujulaType.Default }

object BrujulaTheme {
    val colors: BrujulaColors
        @Composable @ReadOnlyComposable get() = LocalColors.current
    val type: BrujulaType
        @Composable @ReadOnlyComposable get() = LocalType.current
}

@Composable
fun BrujulaTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) BrujulaColors.Dark else BrujulaColors.Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent,
            secondary = c.accent2, onSecondary = c.onAccent2,
            background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text,
            surfaceVariant = c.surface2, onSurfaceVariant = c.textMuted,
            outline = c.line,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent,
            secondary = c.accent2, onSecondary = c.onAccent2,
            background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text,
            surfaceVariant = c.surface2, onSurfaceVariant = c.textMuted,
            outline = c.line,
        )
    }
    CompositionLocalProvider(LocalColors provides c, LocalType provides BrujulaType.Default) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
