package dev.governance.android.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import dev.governance.android.app.agent.*
import dev.governance.android.app.ui.screens.VoiceChatScreen
import dev.governance.android.app.ui.screens.VoiceTurn
import dev.governance.android.app.ui.screens.VoiceTurnRole
import dev.governance.android.app.ui.theme.OakSparrowTheme
import dev.governance.android.app.voice.VoiceController
import dev.governance.android.app.voice.VoiceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full voice chat assistant — the primary assistant entry point.
 *
 * Launched by:
 * - Long-press home (ACTION_ASSIST / VOICE_COMMAND)
 * - Floating mic bubble tap
 * - Voice chat button in the app
 *
 * This is the full LLM voice mode: continuous multi-turn conversation
 * with Claude, plus full task execution (open apps, send messages,
 * UI interaction). Every action still gates through the governance kernel.
 *
 * Say "bye", "done", or "close" to dismiss. Translucent theme keeps
 * whatever app was on screen visible behind the overlay.
 */
class AssistantActivity : ComponentActivity() {

    private val kernelState = mutableStateOf<AgentKernelInterface?>(null)

    companion object {
        /**
         * Static reference for the agent loop to hide/show the overlay.
         * When the agent needs to interact with an app behind us,
         * it calls [hideOverlay] to move the activity to the back,
         * giving the target app full accessibility tree access.
         */
        @Volatile
        private var currentInstance: AssistantActivity? = null

        fun hideOverlay() {
            currentInstance?.moveTaskToBack(true)
        }

        fun showOverlay() {
            val ctx = currentInstance ?: return
            val intent = Intent(ctx, AssistantActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            ctx.startActivity(intent)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            kernelState.value = AgentKernelInterface.Stub.asInterface(service)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            kernelState.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentInstance = this
        startForegroundService(Intent(this, GovernanceKernelService::class.java))

        setContent {
            OakSparrowTheme {
                AssistantVoiceChat(
                    kernelInterface = kernelState.value,
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

    override fun onDestroy() {
        super.onDestroy()
        if (currentInstance === this) currentInstance = null
    }
}

@Composable
private fun AssistantVoiceChat(
    kernelInterface: AgentKernelInterface?,
    context: Context,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val llmMode = remember { LlmPreference.getLlmMode(context) }

    // Planner + LLM wiring
    val planner = remember {
        val cloud = CloudLlmEngine(
            apiKey = BuildConfig.CLOUD_API_KEY,
            model = BuildConfig.CLOUD_MODEL,
        )
        val local = LlamaCppLlmEngine(context)
        val cloudEnabled = BuildConfig.CLOUD_API_KEY.isNotBlank() && llmMode != LlmMode.ON_DEVICE
        val hybrid = HybridLlmEngine(
            cloud = cloud, local = local, cloudEnabled = cloudEnabled,
        )
        val conversation = if (cloudEnabled) ConversationEngine(
            apiKey = BuildConfig.CLOUD_API_KEY,
        ) else null
        Planner(hybrid, conversationEngine = conversation)
    }
    val dispatcher = remember {
        val cloud = CloudLlmEngine(
            apiKey = BuildConfig.CLOUD_API_KEY,
            model = BuildConfig.CLOUD_MODEL,
        )
        ActionDispatcher(context, agentLoopEngine = cloud)
    }
    val speculationLog = remember { SpeculationLog() }

    // Voice
    val chatInput = remember { mutableStateOf("") }
    val voiceController = remember {
        VoiceController(context) { recognized ->
            chatInput.value = recognized
        }
    }
    val voiceState = voiceController.state.collectAsState().value
    val conversationHistory = remember { mutableStateListOf<VoiceTurn>() }
    var isProcessing by remember { mutableStateOf(false) }

    // Permission
    val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) voiceController.startListening()
    }

    DisposableEffect(Unit) {
        onDispose { voiceController.shutdown() }
    }

    // Load model on entry but do NOT auto-start voice — user taps mic to start
    LaunchedEffect(Unit) {
        if (planner.isModelAvailable()) planner.loadModel()
        planner.resetConversation()
    }

    // Shared logic for processing an instruction (voice or typed)
    fun processInstruction(instruction: String) {
        val lower = instruction.lowercase().trim()
        val exitWords = listOf("bye", "goodbye", "close", "done", "exit", "stop", "never mind", "quit")
        // Match if the entire input is an exit phrase, or contains one as the main intent
        val isExit = exitWords.any { lower == it || lower == "ok $it" || lower == "okay $it" ||
            lower == "$it $it" || lower.startsWith("i'm $it") || lower.startsWith("im $it") }
        if (isExit) {
            scope.launch {
                voiceController.speak("See you later!")
                delay(1200)
                onClose()
            }
            return
        }

        conversationHistory.add(VoiceTurn(VoiceTurnRole.USER, instruction))

        val ki = kernelInterface
        if (ki == null) {
            val msg = "Not connected to the governance kernel."
            conversationHistory.add(VoiceTurn(VoiceTurnRole.ASSISTANT, msg))
            voiceController.speak(msg)
            return
        }

        scope.launch {
            isProcessing = true
            if (planner.isModelAvailable()) planner.loadModel()
            val orchestrator = SpeculativeOrchestrator(
                context, planner, ki, dispatcher, speculationLog,
            )
            val (planResult, _, _) = orchestrator.execute(instruction)
            val text: String = when (planResult) {
                is PlanResult.Success -> planResult.plan.summary
                is PlanResult.Conversational -> planResult.message
                is PlanResult.Error -> planResult.message
            }
            conversationHistory.add(VoiceTurn(VoiceTurnRole.ASSISTANT, text))
            isProcessing = false

            // For action plans that launch external apps, don't speak —
            // the overlay is already hidden and TTS would bring it back.
            val launchesApp = planResult is PlanResult.Success &&
                planResult.plan.steps.any { it.kind in setOf(
                    "open_app", "send_sms", "make_call", "send_email",
                    "share_to_social_app", "open_url", "search_web",
                    "get_directions", "take_photo", "play_music", "ui_interact",
                ) }
            if (!launchesApp) {
                voiceController.speak(text)
            }
        }
    }

    // Handle voice transcript → plan → execute → speak
    LaunchedEffect(voiceState) {
        if (voiceState is VoiceState.Heard && chatInput.value.isNotBlank() && !isProcessing) {
            val instruction = chatInput.value.trim()
            chatInput.value = ""
            // Stop listening while we process
            voiceController.cancelListening()
            processInstruction(instruction)
        }
    }

    // Voice mode is manual — user taps mic to start/stop.
    // No auto-listen after TTS finishes.

    VoiceChatScreen(
        voiceState = voiceState,
        conversationHistory = conversationHistory,
        onMicTap = {
            if (voiceState is VoiceState.Listening) {
                voiceController.cancelListening()
                return@VoiceChatScreen
            }
            if (voiceState is VoiceState.Speaking) {
                voiceController.stopSpeaking()
            }
            if (voiceController.hasMicrophonePermission()) {
                voiceController.startListening()
            } else {
                micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        },
        onTextSend = { text ->
            if (!isProcessing) {
                processInstruction(text)
            }
        },
        onClose = onClose,
    )
}
