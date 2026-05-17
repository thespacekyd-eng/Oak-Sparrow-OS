package dev.governance.android.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.governance.android.app.voice.VoiceState

/**
 * Full-screen voice chat mode — continuous conversation with
 * human-like voice. Similar to ChatGPT's voice mode.
 *
 * Shows a pulsing orb when listening/speaking, conversation
 * transcript scrolling behind it, and a close button.
 */
@Composable
fun VoiceChatScreen(
    voiceState: VoiceState,
    conversationHistory: List<VoiceTurn>,
    onMicTap: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(conversationHistory.size) {
        if (conversationHistory.isNotEmpty()) {
            listState.animateScrollToItem(conversationHistory.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Top bar with close button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close voice chat")
            }
        }

        // Conversation history
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (conversationHistory.isEmpty()) {
                item {
                    Text(
                        "Tap the mic and start talking.\nI'll respond naturally.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                    )
                }
            }
            items(conversationHistory) { turn ->
                VoiceTurnBubble(turn)
            }
        }

        // Voice orb area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 48.dp, top = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            VoiceOrb(
                voiceState = voiceState,
                onTap = onMicTap,
            )
        }

        // Status text
        Text(
            text = when (voiceState) {
                is VoiceState.Listening -> "Listening..."
                is VoiceState.Speaking -> "Speaking..."
                is VoiceState.Heard -> "Thinking..."
                is VoiceState.Error -> voiceState.reason
                is VoiceState.Idle -> "Tap to speak"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        )
    }
}

@Composable
private fun VoiceOrb(
    voiceState: VoiceState,
    onTap: () -> Unit,
) {
    val isActive = voiceState is VoiceState.Listening || voiceState is VoiceState.Speaking

    // Pulsing animation when active
    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = if (isActive) 0.6f else 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    val orbColor = when (voiceState) {
        is VoiceState.Listening -> MaterialTheme.colorScheme.primary
        is VoiceState.Speaking -> MaterialTheme.colorScheme.tertiary
        is VoiceState.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primaryContainer
    }

    Box(contentAlignment = Alignment.Center) {
        // Glow ring
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(pulseScale * 1.2f)
                    .clip(CircleShape)
                    .background(orbColor.copy(alpha = glowAlpha * 0.3f)),
            )
        }

        // Main orb button
        FilledIconButton(
            onClick = onTap,
            modifier = Modifier
                .size(80.dp)
                .scale(if (isActive) pulseScale else 1f),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = orbColor,
            ),
        ) {
            Icon(
                imageVector = if (voiceState is VoiceState.Error) Icons.Filled.MicOff else Icons.Filled.Mic,
                contentDescription = "Voice",
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun VoiceTurnBubble(turn: VoiceTurn) {
    val isUser = turn.role == VoiceTurnRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isUser)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                turn.text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

data class VoiceTurn(
    val role: VoiceTurnRole,
    val text: String,
)

enum class VoiceTurnRole { USER, ASSISTANT }
