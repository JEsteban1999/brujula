package app.brujula.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.brujula.container
import app.brujula.core.theme.BrujulaTheme
import app.brujula.core.theme.darkFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val container = LocalContext.current.container
            val darkState = remember(container) { darkFlow(container.routineRepository.routine, container.clock) }
            // null hasta que la rutina guardada se carga: así no hay destello del tema por defecto.
            val dark by darkState.collectAsState(initial = null)
            dark?.let { isDark ->
                BrujulaTheme(dark = isDark) {
                    AskNotificationsOnFirstOpen()
                    BrujulaNavHost()
                }
            }
        }
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
