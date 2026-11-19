package app.brujula.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
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
        MaterialTheme(colorScheme = scheme, typography = brujulaTypography(BrujulaType.Default), content = content)
    }
}

/** Material usa DM Sans en todo el texto; los titulos grandes usan Bricolage. */
private fun brujulaTypography(t: BrujulaType): Typography {
    val base = Typography()
    fun TextStyle.body() = copy(fontFamily = t.body)
    fun TextStyle.display() = copy(fontFamily = t.display)
    return Typography(
        displayLarge = base.displayLarge.display(), displayMedium = base.displayMedium.display(),
        displaySmall = base.displaySmall.display(),
        headlineLarge = base.headlineLarge.display(), headlineMedium = base.headlineMedium.display(),
        headlineSmall = base.headlineSmall.display(),
        titleLarge = base.titleLarge.body(), titleMedium = base.titleMedium.body(), titleSmall = base.titleSmall.body(),
        bodyLarge = base.bodyLarge.body(), bodyMedium = base.bodyMedium.body(), bodySmall = base.bodySmall.body(),
        labelLarge = base.labelLarge.body(), labelMedium = base.labelMedium.body(), labelSmall = base.labelSmall.body(),
    )
}
