package app.brujula.core.theme

import androidx.compose.ui.graphics.Color

data class BrujulaColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentText: Color,
    val accent2: Color,
    val onAccent2: Color,
    val accent2Text: Color,
    val soft: Color,
    val soft2: Color,
    val line: Color,
) {
    companion object {
        val Light = BrujulaColors(
            bg = Color(0xFFF4F3FF),
            surface = Color(0xFFFFFFFF),
            surface2 = Color(0xFFE9E7FF),
            text = Color(0xFF14121F),
            textMuted = Color(0xFF55516E),
            accent = Color(0xFF4B3BFF),
            onAccent = Color(0xFFFFFFFF),
            accentText = Color(0xFF3A2BE0),
            accent2 = Color(0xFFFFB020),
            onAccent2 = Color(0xFF14121F),
            accent2Text = Color(0xFF9A6200),
            soft = Color(0xFFE3E0FF),
            soft2 = Color(0xFFFFF0D1),
            line = Color(0xFFDEDBF5),
        )
        val Dark = BrujulaColors(
            bg = Color(0xFF0F0D1F),
            surface = Color(0xFF1A1733),
            surface2 = Color(0xFF25214A),
            text = Color(0xFFF1F0FF),
            textMuted = Color(0xFFA9A5C9),
            accent = Color(0xFF7B6CFF),
            onAccent = Color(0xFF0F0D1F),
            accentText = Color(0xFFA79CFF),
            accent2 = Color(0xFFFFB020),
            onAccent2 = Color(0xFF14121F),
            accent2Text = Color(0xFFFFC657),
            soft = Color(0xFF241F52),
            soft2 = Color(0xFF33280F),
            line = Color(0xFF2E2A55),
        )
    }
}
