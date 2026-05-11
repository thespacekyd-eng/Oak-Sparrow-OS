package dev.governance.android.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import dev.governance.android.app.agent.*
import dev.governance.android.app.ui.screens.AssistantOverlayScreen
import dev.governance.android.app.ui.theme.OakSparrowTheme
import dev.governance.android.app.voice.VoiceController
import dev.governance.android.app.voice.VoiceState
import dev.governance.android.platform.parcel.ProposedActionParcel
import kotlinx.coroutines.launch

/**
 * Voice-first assistant entry point — the Siri-style invocation.
 *
 * Registered against `ACTION_ASSIST` and `VOICE_COMMAND` intents in the
 * manifest. When Oak & Sparrow is set as the device's default Digital
 * assistant app (Settings → Apps → Default apps → Digital assistant
 * app), long-press home (or the equivalent assist gesture) launches
 * this activity in a translucent immersive overlay.
 *
 * Behavior:
 * 1. On resume, immediately request the mic permission (if not granted)
 *    and start listening.
 * 2. Recognized transcript → planner → kernel.decide() → dispatcher
 *    (every action still gates through the governance kernel — voice is
 *    just a different input modality, not a permission grant).
 * 3. Speak the response via TTS.
 * 4. Either offer to ask another question, or auto-close after speaking.
 *
 * Implementation notes:
 * - `singleInstance` launch mode prevents stale intents from stacking.
 * - Translucent theme keeps whatever was on screen visible behind us.
 * - We bind to the same [GovernanceKernelService] that MainActivity
 *   uses, so kernel state is consistent across entry points.
 */
class AssistantActivity : ComponentActivity() {

    private var kernelInterface: AgentKernelInterface? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            kernelInterface = AgentKernelInterface.Stub.asInterface(service)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            kernelInterface = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Make sure the foreground governance service is running before
        // the user starts speaking. Cheap if already up.
        startForegroundService(Intent(this, GovernanceKernelService::class.java))

        setContent {
            OakSparrowTheme {
                AssistantHost(
                    kernelInterface = kernelInterface,
                    context = this,
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, GovernanceKernelService::class.java),
            connection, Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        super.onStop()
        try { unbindService(connection) } catch (_: IllegalArgumentException) {}
    }
}

@androidx.compose.runtime.Composable
private fun AssistantHost(
    kernelInterface: AgentKernelInterface?,
    context: Context,
    onClose: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Same Planner + LLM engine as MainActivity. We construct fresh
    // instances here because the activity may be invoked while
    // MainActivity is in the background or not even running.
    val planner = androidx.compose.runtime.remember {
        Planner(LlamaCppLlmEngine(context))
    }
    val dispatcher = androidx.compose.runtime.remember {
        ActionDispatcher(context)
    }
    val speculationLog = androidx.compose.runtime.remember { SpeculationLog() }

    // Voice controller — autoStart true: as soon as the activity is
    // resumed and permission is granted, start listening.
    val transcript = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf("")
    }
    val responseText = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf("")
    }
    val voiceController = androidx.compose.runtime.remember {
        VoiceController(context) { recognized ->
            transcript.value = recognized
        }
    }
    val voiceState = voiceController.state.collectAsState().value
    val voiceAvailable = androidx.compose.runtime.remember {
        voiceController.isRecognitionAvailable()
    }

    // Permission gate — we MUST have RECORD_AUDIO before listening.
    // If not granted, show the friendly explanation in the overlay
    // and request it; on grant, start listening immediately.
    val permissionState = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            voiceController.hasMicrophonePermission()
        )
    }
    val micPermissionLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            permissionState.value = granted
            if (granted) voiceController.startListening()
        }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { voiceController.shutdown() }
    }

    // Auto-start the listen loop on entry.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        when {
            !voiceAvailable -> {
                responseText.value = "On-device speech recognition isn't available on this device."
            }
            !voiceController.hasMicrophonePermission() -> {
                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
            else -> voiceController.startListening()
        }
    }

    // When a transcript lands, route it through Planner → Kernel →
    // Dispatcher (same path as MainActivity's chat). Kernel still
    // gates every action.
    androidx.compose.runtime.LaunchedEffect(voiceState) {
        if (voiceState is VoiceState.Heard && transcript.value.isNotBlank()) {
            val instruction = transcript.value
            transcript.value = ""
            val ki = kernelInterface
            if (ki == null) {
                val msg = "Not connected to the governance kernel."
                responseText.value = msg
                voiceController.speak(msg)
                return@LaunchedEffect
            }
            scope.launch {
                if (planner.isModelAvailable()) planner.loadModel()
                val orchestrator = SpeculativeOrchestrator(
                    context, planner, ki, dispatcher, speculationLog,
                )
                val (planResult, _, _) = orchestrator.execute(instruction)
                val text: String = when (planResult) {
                    is PlanResult.Success -> planResult.plan.summary
                    is PlanResult.Error -> planResult.message
                }
                responseText.value = text
                voiceController.speak(text)
            }
        }
    }

    // Auto-close after TTS finishes. Gives the user a moment to see
    // the spoken text on screen before the overlay disappears.
    androidx.compose.runtime.LaunchedEffect(voiceState) {
        if (voiceState is VoiceState.Idle && responseText.value.isNotEmpty()) {
            kotlinx.coroutines.delay(800)
            onClose()
        }
    }

    AssistantOverlayScreen(
        voiceState = voiceState,
        transcript = transcript.value,
        response = responseText.value,
        permissionDenied = !permissionState.value,
        onCancel = { onClose() },
        onRetry = {
            responseText.value = ""
            transcript.value = ""
            voiceController.startListening()
        },
    )
}
