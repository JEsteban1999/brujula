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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
        enableEdgeToEdge()

        // La intro solo en arranque en frío: ni al recrear la actividad (rotación) ni al reabrirla
        // con el proceso vivo.
        val playIntro = savedInstanceState == null && !introShownInProcess
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
            var showIntro by rememberSaveable { mutableStateOf(playIntro) }
            val darkState = remember(container) { darkFlow(container.routineRepository.routine, container.clock) }
            // null hasta que la rutina guardada se carga: así no hay destello del tema por defecto.
            // Se recoge en paralelo a la intro; la intro no espera.
            val dark by darkState.collectAsStateWithLifecycle(initialValue = null)
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
