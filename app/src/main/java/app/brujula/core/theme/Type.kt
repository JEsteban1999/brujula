package app.brujula.core.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.brujula.R

@OptIn(ExperimentalTextApi::class)
private fun variable(resId: Int, vararg weights: Int): FontFamily = FontFamily(
    weights.map { w ->
        Font(
            resId = resId,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    },
)

/** Bricolage Grotesque (títulos, cifras grandes) y DM Sans (texto). */
class BrujulaType(
    val display: FontFamily,
    val body: FontFamily,
) {
    companion object {
        val Default = BrujulaType(
            display = variable(R.font.bricolage_grotesque, 600, 800),
            body = variable(R.font.dm_sans, 400, 500, 700),
        )
    }
}
