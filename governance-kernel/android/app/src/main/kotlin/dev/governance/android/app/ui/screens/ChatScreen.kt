package dev.governance.android.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.governance.android.app.agent.*
import dev.governance.android.app.ui.theme.BloomPalette
import dev.governance.android.app.ui.theme.OakPalette
import dev.governance.android.app.voice.VoiceState

/**
 * Modern chat interface — Claude/ChatGPT style.
 * Clean dark design with Oak & Sparrow branding.
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
    voiceState: VoiceState = VoiceState.Idle,
    voiceAvailable: Boolean = false,
    onMicTap: () -> Unit = {},
    onSuggestionTap: (String) -> Unit = {},
    onSpeak: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OakPalette.Background),
    ) {
        // Conversation
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Empty state — suggestion chips
            if (messages.none { it.role != ChatRole.SYSTEM }) {
                item { EmptyState(onSuggestionTap = onSuggestionTap) }
            }

            items(messages, key = { it.id }) { message ->
                when (message.role) {
                    ChatRole.USER -> UserBubble(message.text)
                    ChatRole.AGENT -> AgentBubble(message, onSpeak = onSpeak)
                    ChatRole.SYSTEM -> SystemBubble(message.text)
                }
            }

            // Typing indicator while processing
            if (isProcessing) {
                item { TypingIndicator() }
            }
        }

        // Input bar
        ChatInputBar(
            inputText = inputText,
            onInputChange = onInputChange,
            onSend = onSend,
            isProcessing = isProcessing,
            voiceState = voiceState,
            voiceAvailable = voiceAvailable,
            onMicTap = onMicTap,
        )
    }
}

@Composable
private fun EmptyState(onSuggestionTap: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Logo / brand mark
        Surface(
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            color = OakPalette.PrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Park,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = OakPalette.Primary,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            "Oak",
            style = MaterialTheme.typography.headlineMedium,
            color = OakPalette.TextPrimary,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Your private AI assistant",
            style = MaterialTheme.typography.bodyMedium,
            color = OakPalette.TextTertiary,
        )

        Spacer(Modifier.height(32.dp))

        // Suggestion chips
        val suggestions = listOf(
            "Open Instagram",
            "Set an alarm for 7am",
            "What can you do?",
            "Text Mom saying hi",
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            suggestions.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    row.forEach { suggestion ->
                        SuggestionChip(text = suggestion, onClick = { onSuggestionTap(suggestion) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = OakPalette.SurfaceVariant,
        border = null,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = OakPalette.TextSecondary,
        )
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "Oak" label
        Text(
            "Oak",
            style = MaterialTheme.typography.labelMedium,
            color = OakPalette.Primary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(10.dp))

        // Animated dots
        val infiniteTransition = rememberInfiniteTransition(label = "typing")
        repeat(3) { i ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = i * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(OakPalette.TextTertiary.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            color = OakPalette.UserBubble,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = OakPalette.OnUserBubble,
            )
        }
    }
}

@Composable
private fun SystemBubble(text: String) {
    if (text.startsWith("Not connected") || text.contains("error", ignoreCase = true)) {
        // Show errors visibly
        Text(
            text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = OakPalette.Error,
        )
    }
    // Hide other system messages (LLM status, etc.)
}

@Composable
private fun AgentBubble(message: ChatMessage, onSpeak: (String) -> Unit = {}) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        // Agent label
        Text(
            "Oak",
            style = MaterialTheme.typography.labelMedium,
            color = OakPalette.Primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )

        // Message text — no bubble background, just text on dark surface (like Claude)
        Text(
            message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = OakPalette.AgentText,
            modifier = Modifier.padding(start = 4.dp, end = 48.dp),
            lineHeight = 22.sp,
        )

        // Plan execution steps
        val log = message.executionLog
        if (log != null) {
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 48.dp),
                shape = RoundedCornerShape(12.dp),
                color = OakPalette.SurfaceVariant,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    log.plan.steps.forEachIndexed { i, step ->
                        val state = log.stepStates.getOrNull(i) ?: ExecutionLog.StepState.Pending
                        val tier = log.stepTiers.getOrNull(i)
                        StepRow(i + 1, step, state, tier)
                    }
                }
            }
        }

        // Read aloud + Copy buttons
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = { onSpeak(message.text) },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Filled.VolumeUp,
                    contentDescription = "Read aloud",
                    modifier = Modifier.size(16.dp),
                    tint = OakPalette.TextTertiary,
                )
            }
            IconButton(
                onClick = {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(message.text))
                    android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy",
                    modifier = Modifier.size(16.dp),
                    tint = OakPalette.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun StepRow(number: Int, step: PlannedStep, state: ExecutionLog.StepState, tier: DispatchTier? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            is ExecutionLog.StepState.Pending -> Text("$number.", style = MaterialTheme.typography.labelMedium, color = OakPalette.TextTertiary)
            is ExecutionLog.StepState.GateChecking -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = OakPalette.Primary)
            is ExecutionLog.StepState.AwaitingApproval -> Icon(Icons.Filled.Warning, null, Modifier.size(12.dp), tint = BloomPalette.WarnAmber)
            is ExecutionLog.StepState.Executing -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = OakPalette.Primary)
            is ExecutionLog.StepState.Done -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(12.dp), tint = OakPalette.Primary)
            is ExecutionLog.StepState.Failed -> Icon(Icons.Filled.Warning, null, Modifier.size(12.dp), tint = OakPalette.Error)
            is ExecutionLog.StepState.Skipped -> Text("—", style = MaterialTheme.typography.labelMedium, color = OakPalette.TextTertiary)
            is ExecutionLog.StepState.Vetoed -> Icon(Icons.Filled.Warning, null, Modifier.size(12.dp), tint = OakPalette.Error)
        }

        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${step.kind.replace('_', ' ')}${if (step.target != null) " → ${step.target}" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OakPalette.TextSecondary,
                )
                if (tier != null) {
                    Spacer(Modifier.width(6.dp))
                    val (label, color) = when (tier) {
                        DispatchTier.INSTANT -> "INSTANT" to OakPalette.Primary
                        DispatchTier.SPECULATIVE -> "SPEC" to BloomPalette.WarnAmber
                        DispatchTier.STRICT -> "STRICT" to OakPalette.TextTertiary
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
            val detail = when (state) {
                is ExecutionLog.StepState.Done -> state.result
                is ExecutionLog.StepState.Failed -> state.reason
                is ExecutionLog.StepState.Skipped -> state.reason
                is ExecutionLog.StepState.Vetoed -> state.reason
                is ExecutionLog.StepState.AwaitingApproval -> "Waiting for approval..."
                else -> null
            }
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = OakPalette.TextTertiary)
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    isProcessing: Boolean,
    voiceState: VoiceState,
    voiceAvailable: Boolean,
    onMicTap: () -> Unit,
) {
    Surface(
        color = OakPalette.Surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Mic button
            if (voiceAvailable) {
                IconButton(
                    onClick = onMicTap,
                    enabled = !isProcessing,
                    modifier = Modifier.size(40.dp),
                ) {
                    val (icon, tint) = when (voiceState) {
                        is VoiceState.Listening -> Icons.Filled.Mic to OakPalette.Error
                        is VoiceState.Speaking -> Icons.Filled.MicOff to OakPalette.Primary
                        else -> Icons.Filled.Mic to OakPalette.TextTertiary
                    }
                    Icon(icon, contentDescription = "Voice", tint = tint, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(4.dp))
            }

            // Text input — pill shaped
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                color = OakPalette.SurfaceVariant,
            ) {
                TextField(
                    value = when (val s = voiceState) {
                        is VoiceState.Listening -> if (s.partial.isNotEmpty()) s.partial else inputText
                        else -> inputText
                    },
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            when (voiceState) {
                                is VoiceState.Listening -> "Listening..."
                                is VoiceState.Speaking -> "Speaking..."
                                else -> "Message Oak..."
                            },
                            color = OakPalette.TextTertiary,
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = OakPalette.Primary,
                        focusedTextColor = OakPalette.TextPrimary,
                        unfocusedTextColor = OakPalette.TextPrimary,
                    ),
                    maxLines = 4,
                    enabled = !isProcessing && voiceState !is VoiceState.Listening,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (inputText.isNotBlank()) onSend() }),
                )
            }

            Spacer(Modifier.width(8.dp))

            // Send button
            if (inputText.isNotBlank() && !isProcessing) {
                FilledIconButton(
                    onClick = onSend,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = OakPalette.Primary,
                        contentColor = OakPalette.OnPrimary,
                    ),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else if (isProcessing) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = OakPalette.Primary,
                    )
                }
            }
        }
    }
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
