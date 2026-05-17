package dev.governance.android.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.governance.android.app.ui.theme.OakPalette
import dev.governance.android.app.voice.VoiceState

/**
 * Full-screen voice mode — centered floating orb, no text input.
 * Tap the orb to start/stop listening. Like ChatGPT voice mode.
 */
@Composable
fun VoiceChatScreen(
    voiceState: VoiceState,
    conversationHistory: List<VoiceTurn>,
    onMicTap: () -> Unit,
    onTextSend: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lastAssistant = conversationHistory.lastOrNull { it.role == VoiceTurnRole.ASSISTANT }
    val lastUser = conversationHistory.lastOrNull { it.role == VoiceTurnRole.USER }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OakPalette.Background),
    ) {
        // Close button (top right)
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close",
                tint = OakPalette.TextTertiary,
            )
        }

        // Center content: orb + status
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Last response text (faded, above the orb)
            if (lastAssistant != null && voiceState !is VoiceState.Listening) {
                Text(
                    lastAssistant.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OakPalette.TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    modifier = Modifier.padding(bottom = 40.dp),
                )
            }

            // The orb
            VoiceOrb(
                voiceState = voiceState,
                onTap = onMicTap,
                size = 120.dp,
            )

            Spacer(Modifier.height(32.dp))

            // Status text
            Text(
                text = when (voiceState) {
                    is VoiceState.Listening -> {
                        val partial = voiceState.partial
                        if (partial.isNotEmpty()) partial else "Listening..."
                    }
                    is VoiceState.Speaking -> "Speaking..."
                    is VoiceState.Heard -> "Thinking..."
                    is VoiceState.Error -> voiceState.reason
                    is VoiceState.Idle -> "Tap to speak"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when (voiceState) {
                    is VoiceState.Error -> OakPalette.Error
                    is VoiceState.Listening -> OakPalette.Primary
                    else -> OakPalette.TextTertiary
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun VoiceOrb(
    voiceState: VoiceState,
    onTap: () -> Unit,
    size: Dp = 120.dp,
) {
    val isActive = voiceState is VoiceState.Listening || voiceState is VoiceState.Speaking
    val isListening = voiceState is VoiceState.Listening
    val isThinking = voiceState is VoiceState.Heard

    val infiniteTransition = rememberInfiniteTransition(label = "orb")

    // Pulse animation
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.12f else if (isThinking) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 600 else 1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    // Outer glow rings
    val ring1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = if (isActive) 0.25f else 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ring1",
    )
    val ring2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.04f,
        targetValue = if (isActive) 0.15f else 0.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ring2",
    )

    val orbColor = when (voiceState) {
        is VoiceState.Listening -> OakPalette.Primary
        is VoiceState.Speaking -> OakPalette.Primary.copy(alpha = 0.8f)
        is VoiceState.Heard -> OakPalette.Primary.copy(alpha = 0.6f)
        is VoiceState.Error -> OakPalette.Error
        else -> OakPalette.SurfaceVariant
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onTap,
        ),
    ) {
        // Outer glow ring 2
        if (isActive || isThinking) {
            Box(
                modifier = Modifier
                    .size(size * 1.6f)
                    .scale(pulseScale * 1.1f)
                    .clip(CircleShape)
                    .background(orbColor.copy(alpha = ring2Alpha)),
            )
        }

        // Outer glow ring 1
        if (isActive || isThinking) {
            Box(
                modifier = Modifier
                    .size(size * 1.3f)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(orbColor.copy(alpha = ring1Alpha)),
            )
        }

        // Main orb
        Surface(
            modifier = Modifier
                .size(size)
                .scale(if (isActive || isThinking) pulseScale else 1f),
            shape = CircleShape,
            color = orbColor,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = when (voiceState) {
                        is VoiceState.Error -> Icons.Filled.MicOff
                        is VoiceState.Listening -> Icons.Filled.Mic
                        else -> Icons.Filled.Mic
                    },
                    contentDescription = "Voice",
                    modifier = Modifier.size(size * 0.4f),
                    tint = if (voiceState is VoiceState.Idle)
                        OakPalette.TextSecondary
                    else
                        OakPalette.OnPrimary,
                )
            }
        }
    }
}

data class VoiceTurn(
    val role: VoiceTurnRole,
    val text: String,
)

enum class VoiceTurnRole { USER, ASSISTANT }
