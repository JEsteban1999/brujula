package app.brujula.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import app.brujula.splash.shouldPlayIntro
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.brujula.splash.IntroScreen
import app.brujula.splash.IntroTimings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.brujula.container
import app.brujula.core.theme.BrujulaTheme
import app.brujula.core.theme.darkFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        applySystemBars(dark = true) // el fondo inicial y la intro son oscuros

        // La intro solo en arranque en frío: ni al recrear la actividad (rotación) ni al reabrirla
        // con el proceso vivo.
        val playIntro = shouldPlayIntro(introShownInProcess, savedInstanceState != null)
        if (playIntro) {
            introShownInProcess = true
            // Mantiene el splash del sistema (ícono animado) hasta completar SYSTEM_MS.
            val start = SystemClock.uptimeMillis()
            splash.setKeepOnScreenCondition { SystemClock.uptimeMillis() - start < IntroTimings.SYSTEM_MS }
            // Sin salida animada del sistema: la intro continúa en el mismo punto, sin corte.
            splash.setOnExitAnimationListener { it.remove() }
        }

        setContent {
            val container = LocalContext.current.container
            val reduceMotion = rememberReducedMotion()
            // Sin rememberSaveable: tras recrear la actividad playIntro ya es false y la intro no se repite.
            var showIntro by remember { mutableStateOf(playIntro) }
            val darkState = remember(container) { darkFlow(container.routineRepository.routine, container.clock) }
            // null hasta que la rutina guardada se carga: así no hay destello del tema por defecto.
            // Se recoge en paralelo a la intro; la intro no espera.
            val dark by darkState.collectAsStateWithLifecycle(initialValue = null)
            // Los iconos de las barras siguen el tema de la app (no el del sistema); oscuro mientras suena la intro.
            val barsDark = showIntro || (dark ?: true)
            LaunchedEffect(barsDark) { applySystemBars(barsDark) }
            Box(Modifier.fillMaxSize().background(Color(0xFF0F0D1F))) {
                if (!showIntro) {
                    dark?.let { isDark ->
                        BrujulaTheme(dark = isDark) {
                            AskNotificationsOnFirstOpen()
                            BrujulaNavHost()
                        }
                    }
                }
                if (showIntro) {
                    BackHandler { }
                    IntroScreen(reduceMotion = reduceMotion, onFinished = { showIntro = false })
                }
            }
        }
    }

    /** Barras transparentes; iconos claros sobre fondo oscuro e iconos oscuros sobre fondo claro. */
    private fun applySystemBars(dark: Boolean) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    private companion object {
        var introShownInProcess = false
    }

    /** Pide POST_NOTIFICATIONS solo la primera vez que se abre la app. */
    @androidx.compose.runtime.Composable
    private fun AskNotificationsOnFirstOpen() {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
            val prefs = getSharedPreferences("brujula_ui", MODE_PRIVATE)
            if (prefs.getBoolean("asked_notifications", false)) return@LaunchedEffect
            prefs.edit().putBoolean("asked_notifications", true).apply()
            val granted = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
