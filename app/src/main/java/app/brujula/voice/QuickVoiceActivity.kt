package app.brujula.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.brujula.container
import app.brujula.core.theme.BrujulaTheme
import app.brujula.core.theme.darkFlow
import kotlinx.coroutines.launch

/**
 * Ventanita flotante de dictado del widget: Android exige algo visible mientras se graba. No está exportada (la abre el
 * `PendingIntent` explícito del widget) y no lee extras. Reutiliza [VoiceViewModel], así que salir mientras Claude entiende
 * encola el dictado y un apply ya entregado termina solo.
 */
class QuickVoiceActivity : ComponentActivity() {
    private var permissionPending = false

    private val viewModel: VoiceViewModel by lazy {
        val app = applicationContext
        ViewModelProvider(
            this,
            viewModelFactory {
                initializer {
                    val c = app.container
                    VoiceViewModel(
                        speech = AndroidSpeechInput(app),
                        interpreter = c.interpreter,
                        executor = c.executor,
                        pending = c.pendingDictations,
                        requestDrain = { c.syncScope.launch { runCatching { c.drainPendingDictations() } } },
                        queueWithoutKey = true,
                        floating = true,
                        leaveScope = c.syncScope,
                    )
                }
            },
        )[VoiceViewModel::class.java]
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending = false
        if (granted) viewModel.start() else viewModel.onMicDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = viewModel
        if (savedInstanceState == null) {
            if (!vm.voiceAvailable) {
                vm.start() // muestra el aviso de que no hay dictado
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                vm.start()
            } else {
                permissionPending = true
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        setContent {
            val container = LocalContext.current.container
            val darkState = remember(container) { darkFlow(container.routineRepository.routine, container.clock) }
            val dark by darkState.collectAsStateWithLifecycle(initialValue = null)
            val state by vm.state.collectAsStateWithLifecycle()
            // Una propuesta pura no cabe aquí: se pasa a la app.
            (state as? VoiceState.OpenProposal)?.let { open ->
                LaunchedEffect(open) {
                    startActivity(QuickVoicePolicy.proposalIntent(this@QuickVoiceActivity, open.day, open.request))
                    finish()
                }
            }
            dark?.let { isDark ->
                BrujulaTheme(dark = isDark) {
                    QuickVoiceScreen(
                        viewModel = vm,
                        onClose = ::finish,
                        onOpenApp = {
                            // Un error definitivo no se pierde: el texto pasa a la cola antes de abrir la app.
                            vm.queueForApp()
                            startActivity(QuickVoicePolicy.voiceIntent(this))
                            finish()
                        },
                        onOpenProposal = { day, request ->
                            startActivity(QuickVoicePolicy.proposalIntent(this, day, request))
                            finish()
                        },
                    )
                }
            }
        }
    }

    /** Tocar fuera, Atrás o Inicio: cierra. Rotar o pedir el permiso no cuentan. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !permissionPending && !isFinishing) finish()
    }
}
