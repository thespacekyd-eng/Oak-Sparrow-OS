package dev.governance.android.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.governance.android.app.agent.*
import dev.governance.android.app.ui.PreviewKernelState
import dev.governance.android.app.ui.screens.*
import dev.governance.android.app.ui.theme.OakSparrowTheme
import dev.governance.core.GovernanceSnapshot
import kotlinx.coroutines.launch

/**
 * Single Activity hosting Compose Navigation with routes:
 * /home, /decisions, /permissions, /technical, /chat.
 *
 * Binds to [GovernanceKernelService] in onStart, exposes snapshot
 * via Compose state.
 */
class MainActivity : ComponentActivity() {

    private var kernelInterface: AgentKernelInterface? = null
    private var snapshot by mutableStateOf<GovernanceSnapshot?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            kernelInterface = AgentKernelInterface.Stub.asInterface(service)
            refreshSnapshot()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            kernelInterface = null
            snapshot = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, GovernanceKernelService::class.java))

        setContent {
            OakSparrowTheme {
                MainNavigation(
                    snapshot = snapshot,
                    onRefresh = { refreshSnapshot() },
                    kernelInterface = kernelInterface,
                    context = this,
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
        unbindService(connection)
    }

    private fun refreshSnapshot() {
        try { snapshot = kernelInterface?.snapshot()?.toKernel() }
        catch (_: Exception) { snapshot = null }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainNavigation(
    snapshot: GovernanceSnapshot?,
    onRefresh: () -> Unit,
    kernelInterface: AgentKernelInterface?,
    context: android.content.Context,
) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    // Chat state
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var chatInput by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    // Production wiring: llama.cpp + Qwen3-4B (Apache 2.0). Falls back to
    // keyword routing if the .so isn't built (NDK setup not run) or the
    // model file isn't pushed yet. See android/MODEL_SETUP.md.
    val planner = remember { Planner(LlamaCppLlmEngine(context)) }
    val dispatcher = remember { ActionDispatcher(context) }
    val speculationLog = remember { SpeculationLog() }
    val scope = rememberCoroutineScope()

    // Detected once per Activity lifecycle. The mode can't change at
    // runtime — it's determined by where the APK was installed.
    val buildMode = remember { BuildModeDetector.detect(context) }

    data class NavItem(val route: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)
    val items = listOf(
        NavItem("home", R.string.nav_home, Icons.Filled.Home),
        NavItem("decisions", R.string.nav_decisions, Icons.Filled.List),
        NavItem("permissions", R.string.nav_permissions, Icons.Filled.Star),
        NavItem("technical", R.string.nav_technical, Icons.Filled.Settings),
    )

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
        bottomBar = {
            if (currentRoute != "chat") {
                NavigationBar {
                    items.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") {
                HomeScreen(
                    snapshot = snapshot,
                    recentDecisions = PreviewKernelState.recentDecisions,
                    observationCount = PreviewKernelState.systemEvents.size,
                    errorCount = 0,
                    onDecisionTap = { navController.navigate("decisions") },
                    onSeeDetails = { navController.navigate("technical") },
                    onChatTap = { navController.navigate("chat") },
                    buildMode = buildMode,
                )
            }
            composable("decisions") {
                RecentDecisionsScreen(records = PreviewKernelState.auditRecords)
            }
            composable("permissions") {
                val apps = remember {
                    mutableStateListOf(
                        AppCapability("com.google.android.gm", "Gmail"),
                        AppCapability("com.instagram.android", "Instagram"),
                    )
                }
                AppPermissionsScreen(
                    apps = apps,
                    onUpdate = { updated ->
                        val idx = apps.indexOfFirst { it.packageName == updated.packageName }
                        if (idx >= 0) apps[idx] = updated
                    },
                )
            }
            composable("technical") {
                TechnicalDetailScreen(
                    snapshot = snapshot,
                    records = PreviewKernelState.auditRecords,
                    systemEvents = PreviewKernelState.systemEvents.map { it.event },
                    chainVerified = true,
                    chainProblemTime = null,
                    buildMode = buildMode,
                )
            }
            composable("chat") {
                // Eagerly load the LLM on first chat-surface entry so the
                // user doesn't wait through cold-load on their first message.
                // Posts a single SYSTEM message reporting the load state —
                // makes it visible in-app whether the LLM is actually
                // running vs whether the planner is on the keyword fallback.
                LaunchedEffect(Unit) {
                    if (messages.none { it.id == "llm-status" }) {
                        val statusText = when {
                            !planner.isModelAvailable() ->
                                "Model file not present. Run android/setup-model.sh, " +
                                "then re-open the chat. Falling back to keyword router."
                            else -> {
                                val loadResult = planner.loadModel()
                                loadResult ?: "On-device LLM ready (${BuildConfig.MODEL_NAME})."
                            }
                        }
                        messages.add(ChatMessage(
                            id = "llm-status",
                            role = ChatRole.SYSTEM,
                            text = statusText,
                        ))
                    }
                }
                ChatScreen(
                    messages = messages,
                    isProcessing = isProcessing,
                    modelAvailable = planner.isModelAvailable(),
                    inputText = chatInput,
                    onInputChange = { chatInput = it },
                    onSend = {
                        val instruction = chatInput.trim()
                        if (instruction.isBlank() || isProcessing) return@ChatScreen
                        chatInput = ""

                        val userMsg = ChatMessage(
                            id = "user-${System.nanoTime()}",
                            role = ChatRole.USER,
                            text = instruction,
                        )
                        messages.add(userMsg)

                        val ki = kernelInterface
                        if (ki == null) {
                            messages.add(ChatMessage(
                                id = "err-${System.nanoTime()}",
                                role = ChatRole.SYSTEM,
                                text = "Not connected to governance service.",
                            ))
                            return@ChatScreen
                        }

                        scope.launch {
                            isProcessing = true
                            if (planner.isModelAvailable()) planner.loadModel()
                            val orchestrator = SpeculativeOrchestrator(
                                context, planner, ki, dispatcher, speculationLog,
                            )
                            val (planResult, log, _) = orchestrator.execute(instruction)

                            when (planResult) {
                                is PlanResult.Success -> {
                                    messages.add(ChatMessage(
                                        id = "plan-${System.nanoTime()}",
                                        role = ChatRole.AGENT,
                                        text = planResult.plan.summary,
                                        plan = planResult.plan,
                                        executionLog = log,
                                    ))
                                }
                                is PlanResult.Error -> {
                                    messages.add(ChatMessage(
                                        id = "err-${System.nanoTime()}",
                                        role = ChatRole.AGENT,
                                        text = planResult.message,
                                    ))
                                }
                            }
                            isProcessing = false
                        }
                    },
                )
            }
        }
    }
}
