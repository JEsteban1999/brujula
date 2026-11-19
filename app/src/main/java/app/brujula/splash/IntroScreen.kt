package app.brujula.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.brujula.core.theme.BrujulaType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Night = Color(0xFF0F0D1F)
private val Indigo = Color(0xFF4B3BFF)
private val Violet = Color(0xFF7B6CFF)
private val Amber = Color(0xFFFFB020)
private val TextPrimary = Color(0xFFF1F0FF)
private val TextSecondary = Color(0xFFA9A5C9)

// Mismo tamaño que la baldosa del splash del sistema (ver SplashGeometry: 288 dp * 60/108 = 160 dp).
private val TileSize = SplashGeometry.TILE_DP.dp
private val RippleEase = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val SlideEase = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * Continúa el splash del sistema: la baldosa ya está completa y centrada. Dos ondas se expanden,
 * la aguja se asienta y suben el nombre y el lema desde detrás de una máscara.
 * Siempre sobre #0F0D1F, sin importar el tema. Con reducir movimiento muestra el estado final.
 */
@Composable
fun IntroScreen(reduceMotion: Boolean, onFinished: () -> Unit) {
    val finished = rememberUpdatedState(onFinished)
    val ripple1 = remember { Animatable(0f) }
    val ripple2 = remember { Animatable(0f) }
    val title = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val tagline = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val settle = remember { Animatable(0f) }

    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            delay(IntroTimings.composeMs(true).toLong())
        } else {
            coroutineScope {
                launch { ripple1.animateTo(1f, tween(800, easing = LinearEasing)) }
                launch { delay(150); ripple2.animateTo(1f, tween(800, easing = LinearEasing)) }
                launch { delay(250); title.animateTo(1f, tween(450, easing = SlideEase)) }
                launch { delay(400); tagline.animateTo(1f, tween(450, easing = SlideEase)) }
                // La aguja se balancea un instante y vuelve a 0 (continúa donde termina el splash).
                launch {
                    settle.animateTo(-8f, tween(180, easing = SlideEase))
                    settle.animateTo(0f, tween(320, easing = SlideEase))
                }
                launch { delay(IntroTimings.composeMs(false).toLong()) }
            }
        }
        finished.value()
    }

    Box(Modifier.fillMaxSize().background(Night), contentAlignment = Alignment.Center) {
        // Baldosa en el centro exacto de la pantalla, igual que el ícono del splash.
        Box(contentAlignment = Alignment.Center) {
            Ripple(ripple1.value, Violet, reduceMotion)
            Ripple(ripple2.value, Amber, reduceMotion)
            Box(Modifier.size(TileSize).background(Indigo, RoundedCornerShape(26))) {
                Canvas(
                    Modifier
                        .size(TileSize)
                        .semantics { contentDescription = "Ícono de Brújula" },
                ) {
                    val u = size.width / 100f
                    val c = Offset(50f * u, 50f * u)
                    drawCircle(Color.White, radius = 30f * u, center = c, style = Stroke(width = 5f * u))
                    rotate(settle.value, c) {
                        drawPath(
                            Path().apply {
                                moveTo(50f * u, 24f * u); lineTo(59f * u, 50f * u); lineTo(41f * u, 50f * u); close()
                            },
                            Amber,
                        )
                        drawPath(
                            Path().apply {
                                moveTo(41f * u, 50f * u); lineTo(59f * u, 50f * u); lineTo(50f * u, 76f * u); close()
                            },
                            Color.White,
                        )
                    }
                }
            }
        }

        // Texto bajo la baldosa: la mitad inferior empieza en el centro de la pantalla.
        Column(Modifier.fillMaxSize().clearAndSetSemantics { }) {
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.weight(1f).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(TileSize / 2 + 30.dp))
                Box(Modifier.clipToBounds().padding(horizontal = 4.dp)) {
                    Text(
                        text = "Brújula",
                        color = TextPrimary,
                        fontFamily = BrujulaType.Default.display,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 46.sp,
                        lineHeight = 51.sp,
                        letterSpacing = (-1.38).sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.slideUp(title.value),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.clipToBounds()) {
                    Text(
                        text = "Hacia dónde va tu día",
                        color = TextSecondary,
                        fontFamily = BrujulaType.Default.body,
                        fontWeight = FontWeight.Normal,
                        fontSize = 17.sp,
                        lineHeight = 24.sp,
                        modifier = Modifier.slideUp(tagline.value),
                    )
                }
            }
        }
    }
}

private fun Modifier.slideUp(progress: Float): Modifier = graphicsLayer {
    translationY = size.height * 1.1f * (1f - progress)
}

/** Onda: escala 1 -> 2.3 con la curva del diseño; opacidad sube a .7 en el 8 % y se desvanece. */
@Composable
private fun Ripple(t: Float, color: Color, reduceMotion: Boolean) {
    if (reduceMotion || t <= 0f || t >= 1f) return
    val scale = 1f + 1.3f * RippleEase.transform(t)
    val a = if (t < 0.08f) 0.7f * t / 0.08f else 0.7f * (1f - (t - 0.08f) / 0.92f)
    Box(
        Modifier
            .size(TileSize)
            .scale(scale)
            .alpha(a)
            .border(2.dp, color, RoundedCornerShape(27)),
    )
}
