package dev.governance.android.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.governance.android.app.agent.*
import dev.governance.android.app.ui.PreviewKernelState
import dev.governance.android.app.ui.screens.*
import dev.governance.android.app.ui.theme.OakPalette
import dev.governance.android.app.ui.theme.OakSparrowTheme
import dev.governance.android.app.voice.VoiceController
import dev.governance.android.app.voice.VoiceState
import dev.governance.core.GovernanceSnapshot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var kernelInterface by mutableStateOf<AgentKernelInterface?>(null)
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
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Chat state
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var chatInput by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var currentConversationId by remember { mutableStateOf("conv-${System.currentTimeMillis()}") }
    val conversationStore = remember { ConversationStore(context) }
    var conversationList by remember { mutableStateOf(conversationStore.listConversations()) }

    // User preferences
    var llmMode by remember { mutableStateOf(LlmPreference.getLlmMode(context)) }
    val hasCloudKey = BuildConfig.CLOUD_API_KEY.isNotBlank()

    // Memory
    val oakMemory = remember { OakMemory(context) }

    // LLM wiring
    val cloudEnabled = hasCloudKey && llmMode != LlmMode.ON_DEVICE
    val planner = remember(llmMode) {
        // Haiku for action planning — fast routing, JSON generation
        val cloud = CloudLlmEngine(
            apiKey = BuildConfig.CLOUD_API_KEY,
        )
        val local = LlamaCppLlmEngine(context)
        val hybrid = HybridLlmEngine(cloud = cloud, local = local, cloudEnabled = cloudEnabled)
        val conversation = if (cloudEnabled) {
            ConversationEngine(
                apiKey = BuildConfig.CLOUD_API_KEY,
                memoryBlock = oakMemory.toPromptBlock(),
                onRemember = { fact -> oakMemory.addMemory(fact) },
                onForget = { fact -> oakMemory.removeMemory(fact) },
            )
        } else null
        Planner(hybrid, conversationEngine = conversation)
    }
    val dispatcher = remember {
        val cloud = CloudLlmEngine(apiKey = BuildConfig.CLOUD_API_KEY, model = BuildConfig.CLOUD_MODEL)
        ActionDispatcher(context, agentLoopEngine = cloud)
    }
    val speculationLog = remember { SpeculationLog() }

    // Voice
    val voiceController = remember { VoiceController(context) { transcript -> chatInput = transcript } }
    val voiceState = voiceController.state.collectAsState().value
    val voiceAvailable = remember { voiceController.isRecognitionAvailable() }
    val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) voiceController.startListening() }
    DisposableEffect(Unit) { onDispose { voiceController.shutdown() } }

    val buildMode = remember { BuildModeDetector.detect(context) }

    // Voice chat state
    val voiceChatHistory = remember { mutableStateListOf<VoiceTurn>() }

    // Send instruction handler
    val sendInstruction: (String) -> Unit = remember(kernelInterface) {
        { rawInstruction: String ->
            val instruction = rawInstruction.trim()
            if (instruction.isNotBlank() && !isProcessing) {
                chatInput = ""
                messages.add(ChatMessage(id = "user-${System.nanoTime()}", role = ChatRole.USER, text = instruction))

                val ki = kernelInterface
                if (ki == null) {
                    messages.add(ChatMessage(id = "err-${System.nanoTime()}", role = ChatRole.SYSTEM, text = "Not connected to governance service."))
                } else {
                    scope.launch {
                        isProcessing = true
                        if (planner.isModelAvailable()) planner.loadModel()
                        val orchestrator = SpeculativeOrchestrator(context, planner, ki, dispatcher, speculationLog)
                        val (planResult, log, _) = orchestrator.execute(instruction)
                        when (planResult) {
                            is PlanResult.Success -> messages.add(ChatMessage(
                                id = "plan-${System.nanoTime()}", role = ChatRole.AGENT,
                                text = planResult.plan.summary, plan = planResult.plan, executionLog = log,
                            ))
                            is PlanResult.Conversational -> messages.add(ChatMessage(
                                id = "chat-${System.nanoTime()}", role = ChatRole.AGENT, text = planResult.message,
                            ))
                            is PlanResult.Error -> messages.add(ChatMessage(
                                id = "err-${System.nanoTime()}", role = ChatRole.AGENT, text = planResult.message,
                            ))
                        }
                        // Auto-save conversation
                        conversationStore.saveConversation(currentConversationId, messages.toList())
                        conversationList = conversationStore.listConversations()
                        isProcessing = false
                    }
                }
            }
        }
    }

    // Sidebar drawer + chat-first layout
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = OakPalette.DrawerBackground,
                modifier = Modifier.width(300.dp),
            ) {
                DrawerContent(
                    conversations = conversationList,
                    currentConversationId = currentConversationId,
                    onNewChat = {
                        messages.clear()
                        currentConversationId = "conv-${System.currentTimeMillis()}"
                        planner.resetConversation()
                        scope.launch { drawerState.close() }
                        navController.navigate("chat") {
                            popUpTo("chat") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onLoadConversation = { summary ->
                        val loaded = conversationStore.loadConversation(summary.id)
                        if (loaded != null) {
                            messages.clear()
                            messages.addAll(loaded)
                            currentConversationId = summary.id
                            planner.resetConversation()
                        }
                        scope.launch { drawerState.close() }
                        navController.navigate("chat") {
                            popUpTo("chat") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onDeleteConversation = { summary ->
                        conversationStore.deleteConversation(summary.id)
                        conversationList = conversationStore.listConversations()
                        if (summary.id == currentConversationId) {
                            messages.clear()
                            currentConversationId = "conv-${System.currentTimeMillis()}"
                        }
                    },
                    onVoiceChat = {
                        scope.launch { drawerState.close() }
                        navController.navigate("voice-chat") {
                            launchSingleTop = true
                        }
                    },
                    onGovernance = {
                        scope.launch { drawerState.close() }
                        navController.navigate("governance") { launchSingleTop = true }
                    },
                    onSettings = {
                        scope.launch { drawerState.close() }
                        navController.navigate("settings") { launchSingleTop = true }
                    },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Oak",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = OakPalette.TextSecondary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = OakPalette.Background,
                        titleContentColor = OakPalette.TextPrimary,
                    ),
                )
            },
            containerColor = OakPalette.Background,
        ) { padding ->
            NavHost(
                navController,
                startDestination = "chat",
                modifier = Modifier.padding(padding),
            ) {
                composable("chat") {
                    LaunchedEffect(Unit) {
                        if (planner.isModelAvailable()) planner.loadModel()
                    }
                    LaunchedEffect(voiceState) {
                        if (voiceState is VoiceState.Heard && chatInput.isNotBlank() && !isProcessing) {
                            sendInstruction(chatInput)
                        }
                    }
                    ChatScreen(
                        messages = messages,
                        isProcessing = isProcessing,
                        modelAvailable = planner.isModelAvailable(),
                        inputText = chatInput,
                        onInputChange = { chatInput = it },
                        voiceState = voiceState,
                        voiceAvailable = voiceAvailable,
                        onMicTap = {
                            if (voiceState is VoiceState.Listening) { voiceController.cancelListening(); return@ChatScreen }
                            if (voiceState is VoiceState.Speaking) voiceController.stopSpeaking()
                            if (voiceController.hasMicrophonePermission()) voiceController.startListening()
                            else micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                        onSend = { sendInstruction(chatInput) },
                        onSuggestionTap = { sendInstruction(it) },
                        onSpeak = { text -> voiceController.speak(text) },
                    )
                }
                composable("voice-chat") {
                    // Enable continuous conversation loop
                    LaunchedEffect(Unit) {
                        voiceController.autoListenEnabled = true
                        planner.resetConversation()
                    }
                    DisposableEffect(Unit) {
                        onDispose { voiceController.autoListenEnabled = false }
                    }

                    VoiceChatScreen(
                        voiceState = voiceState,
                        conversationHistory = voiceChatHistory,
                        onMicTap = {
                            if (voiceState is VoiceState.Listening) { voiceController.cancelListening(); return@VoiceChatScreen }
                            if (voiceState is VoiceState.Speaking) { voiceController.stopSpeaking(); return@VoiceChatScreen }
                            if (voiceController.hasMicrophonePermission()) voiceController.startListening()
                            else micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                        onTextSend = {},
                        onClose = {
                            voiceController.stopSpeaking()
                            voiceController.cancelListening()
                            navController.popBackStack()
                        },
                    )

                    // Process voice input when heard
                    LaunchedEffect(voiceState) {
                        if (voiceState is VoiceState.Heard && !isProcessing) {
                            val instruction = voiceState.transcript
                            voiceChatHistory.add(VoiceTurn(VoiceTurnRole.USER, instruction))
                            isProcessing = true

                            val ki = kernelInterface
                            if (ki == null) {
                                voiceChatHistory.add(VoiceTurn(VoiceTurnRole.ASSISTANT, "Not connected yet. Try again."))
                                isProcessing = false
                                return@LaunchedEffect
                            }

                            val orchestrator = SpeculativeOrchestrator(context, planner, ki, dispatcher, speculationLog)
                            val (planResult, _, _) = orchestrator.execute(instruction)
                            val resp = when (planResult) {
                                is PlanResult.Success -> planResult.plan.summary
                                is PlanResult.Conversational -> planResult.message
                                is PlanResult.Error -> planResult.message
                            }
                            voiceChatHistory.add(VoiceTurn(VoiceTurnRole.ASSISTANT, resp))
                            isProcessing = false
                            voiceController.speak(resp)
                        }
                    }
                }
                composable("governance") {
                    HomeScreen(
                        snapshot = snapshot,
                        recentDecisions = PreviewKernelState.recentDecisions,
                        observationCount = PreviewKernelState.systemEvents.size,
                        errorCount = 0,
                        onDecisionTap = { navController.navigate("decisions") },
                        onSeeDetails = { navController.navigate("technical") },
                        onChatTap = { navController.navigate("chat") },
                        onVoiceChatTap = { navController.navigate("voice-chat") },
                        onSettingsTap = { navController.navigate("settings") },
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
                composable("settings") {
                    SettingsScreen(
                        llmMode = llmMode,
                        onLlmModeChange = { mode ->
                            llmMode = mode
                            LlmPreference.setLlmMode(context, mode)
                        },
                        hasCloudKey = hasCloudKey,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerContent(
    conversations: List<ConversationSummary>,
    currentConversationId: String,
    onNewChat: () -> Unit,
    onLoadConversation: (ConversationSummary) -> Unit,
    onDeleteConversation: (ConversationSummary) -> Unit,
    onVoiceChat: () -> Unit,
    onGovernance: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(vertical = 16.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = OakPalette.PrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Park, null, Modifier.size(18.dp), tint = OakPalette.Primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "Oak & Sparrow",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = OakPalette.TextPrimary,
            )
        }

        Spacer(Modifier.height(8.dp))

        // New Chat button
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clickable(onClick = onNewChat),
            shape = RoundedCornerShape(12.dp),
            color = OakPalette.SurfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(20.dp), tint = OakPalette.TextPrimary)
                Spacer(Modifier.width(12.dp))
                Text("New chat", style = MaterialTheme.typography.bodyMedium, color = OakPalette.TextPrimary)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Conversation history
        if (conversations.isNotEmpty()) {
            Text(
                "Recent",
                style = MaterialTheme.typography.labelSmall,
                color = OakPalette.TextTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
            ) {
                items(conversations, key = { it.id }) { conv ->
                    val isActive = conv.id == currentConversationId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLoadConversation(conv) }
                            .background(if (isActive) OakPalette.SurfaceVariant else OakPalette.DrawerBackground)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat,
                            null,
                            Modifier.size(16.dp),
                            tint = if (isActive) OakPalette.Primary else OakPalette.TextTertiary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                conv.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isActive) OakPalette.TextPrimary else OakPalette.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = { onDeleteConversation(conv) },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Delete",
                                modifier = Modifier.size(14.dp),
                                tint = OakPalette.TextTertiary,
                            )
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        HorizontalDivider(color = OakPalette.Outline, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))

        // Navigation items
        DrawerItem(icon = Icons.Filled.Mic, label = "Voice mode", onClick = onVoiceChat)
        DrawerItem(icon = Icons.Filled.Shield, label = "Governance", onClick = onGovernance)
        DrawerItem(icon = Icons.Filled.Settings, label = "Settings", onClick = onSettings)

        Spacer(Modifier.height(8.dp))

        // Footer
        Text(
            "Oak & Sparrow OS",
            style = MaterialTheme.typography.labelSmall,
            color = OakPalette.TextTertiary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun DrawerItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = OakPalette.TextSecondary)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = OakPalette.TextSecondary)
    }
}
