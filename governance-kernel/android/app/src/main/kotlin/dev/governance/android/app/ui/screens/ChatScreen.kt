package dev.governance.android.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.governance.android.app.agent.*
import dev.governance.android.app.ui.theme.BloomPalette
import dev.governance.android.app.voice.VoiceState

/**
 * Chat surface for interacting with the on-device agent.
 * Shows conversation history, plan steps with live status,
 * and a text input at the bottom.
 */
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isProcessing: Boolean,
    modelAvailable: Boolean,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    // Voice integration. Defaults make voice optional — Paparazzi
    // snapshots and previews keep working without wiring it.
    voiceState: VoiceState = VoiceState.Idle,
    voiceAvailable: Boolean = false,
    onMicTap: () -> Unit = {},
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Model-not-installed banner
        if (!modelAvailable) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Text(
                    "LLM model not installed. Using keyword planner. " +
                        "See README for model setup via adb push.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }

        // Conversation
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Onboarding hint
            if (messages.isEmpty()) {
                item {
                    Text(
                        "On-device AI. Your instructions don't leave the phone.\n\n" +
                            "Try: \"check my calendar\", \"email chen saying I'll be late\", " +
                            "or \"share this to Instagram\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            }

            items(messages, key = { it.id }) { message ->
                when (message.role) {
                    ChatRole.USER -> UserBubble(message.text)
                    ChatRole.AGENT -> AgentBubble(message)
                    ChatRole.SYSTEM -> SystemBubble(message.text)
                }
            }
        }

        // Input row
        Surface(tonalElevation = 3.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Mic button — voice input. Replaces text typing for
                // hands-free use. Tap toggles listening; transcript is
                // forwarded to the planner like a typed message.
                if (voiceAvailable) {
                    IconButton(
                        onClick = onMicTap,
                        enabled = !isProcessing,
                    ) {
                        when (voiceState) {
                            is VoiceState.Listening -> Icon(
                                Icons.Filled.Mic,
                                contentDescription = "Listening — tap to cancel",
                                tint = MaterialTheme.colorScheme.error,
                            )
                            is VoiceState.Speaking -> Icon(
                                Icons.Filled.MicOff,
                                contentDescription = "Speaking response",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            is VoiceState.Error -> Icon(
                                Icons.Filled.MicOff,
                                contentDescription = "Voice error: ${voiceState.reason}",
                                tint = MaterialTheme.colorScheme.error,
                            )
                            else -> Icon(
                                Icons.Filled.Mic,
                                contentDescription = "Tap to speak",
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
                OutlinedTextField(
                    value = when (val s = voiceState) {
                        is VoiceState.Listening -> if (s.partial.isNotEmpty()) s.partial else inputText
                        else -> inputText
                    },
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        when (voiceState) {
                            is VoiceState.Listening -> Text("Listening...")
                            is VoiceState.Speaking -> Text("Speaking...")
                            else -> Text("Ask your agent...")
                        }
                    },
                    maxLines = 3,
                    enabled = !isProcessing && voiceState !is VoiceState.Listening,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onSend,
                    enabled = inputText.isNotBlank() && !isProcessing,
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AgentBubble(message: ChatMessage) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(message.text, style = MaterialTheme.typography.bodyMedium)

                // Show plan steps if present
                val log = message.executionLog
                if (log != null) {
                    Spacer(Modifier.height(8.dp))
                    log.plan.steps.forEachIndexed { i, step ->
                        val state = log.stepStates.getOrNull(i) ?: ExecutionLog.StepState.Pending
                        val tier = log.stepTiers.getOrNull(i)
                        StepRow(i + 1, step, state, tier)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(number: Int, step: PlannedStep, state: ExecutionLog.StepState, tier: DispatchTier? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status icon
        when (state) {
            is ExecutionLog.StepState.Pending -> Text("$number.", style = MaterialTheme.typography.labelMedium)
            is ExecutionLog.StepState.GateChecking -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            is ExecutionLog.StepState.AwaitingApproval -> Icon(Icons.Filled.Warning, null, Modifier.size(14.dp), tint = BloomPalette.WarnAmber)
            is ExecutionLog.StepState.Executing -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            is ExecutionLog.StepState.Done -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp), tint = BloomPalette.TrustGreen)
            is ExecutionLog.StepState.Failed -> Icon(Icons.Filled.Warning, null, Modifier.size(14.dp), tint = BloomPalette.DangerRed)
            is ExecutionLog.StepState.Skipped -> Text("—", style = MaterialTheme.typography.labelMedium)
            is ExecutionLog.StepState.Vetoed -> Icon(Icons.Filled.Warning, null, Modifier.size(14.dp), tint = BloomPalette.DangerRed)
        }

        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${step.kind.replace('_', ' ')}${if (step.target != null) " → ${step.target}" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                )
                // Dispatch tier badge
                if (tier != null) {
                    Spacer(Modifier.width(6.dp))
                    val (label, color) = when (tier) {
                        DispatchTier.INSTANT -> "INSTANT" to BloomPalette.TrustGreen
                        DispatchTier.SPECULATIVE -> "SPEC" to BloomPalette.WarnAmber
                        DispatchTier.STRICT -> "STRICT" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(color.copy(alpha = 0.12f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            // Show result detail for completed states
            val detail = when (state) {
                is ExecutionLog.StepState.Done -> state.result
                is ExecutionLog.StepState.Failed -> state.reason
                is ExecutionLog.StepState.Skipped -> state.reason
                is ExecutionLog.StepState.Vetoed -> state.reason
                is ExecutionLog.StepState.AwaitingApproval -> "Waiting for your approval..."
                else -> null
            }
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SystemBubble(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A single message in the chat conversation. */
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val plan: Plan? = null,
    val executionLog: ExecutionLog? = null,
)

enum class ChatRole { USER, AGENT, SYSTEM }
