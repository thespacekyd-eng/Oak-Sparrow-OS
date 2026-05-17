package dev.governance.android.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.governance.android.app.ui.theme.OakPalette
import dev.governance.android.app.voice.VoiceState
import kotlin.math.PI
import kotlin.math.sin

/**
 * Full-screen voice mode — audio-reactive floating orb.
 * Continuous conversation: speak → Oak responds → auto-listens.
 * Tap orb to start, tap while speaking to interrupt.
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

    // Get amplitude from state
    val amplitude = when (voiceState) {
        is VoiceState.Listening -> voiceState.amplitude
        is VoiceState.Speaking -> voiceState.amplitude
        else -> 0f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OakPalette.Background),
    ) {
        // Close button (top-left, like ChatGPT)
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(40.dp),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close",
                tint = OakPalette.TextTertiary,
                modifier = Modifier.size(24.dp),
            )
        }

        // Center: orb + status
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Last response (faded, only when idle)
            if (lastAssistant != null && voiceState is VoiceState.Idle) {
                Text(
                    lastAssistant.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OakPalette.TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    modifier = Modifier
                        .padding(horizontal = 40.dp)
                        .padding(bottom = 48.dp),
                )
            }

            // Audio-reactive orb
            AudioReactiveOrb(
                voiceState = voiceState,
                amplitude = amplitude,
                onTap = onMicTap,
                size = 160.dp,
            )

            Spacer(Modifier.height(36.dp))

            // Status text
            Text(
                text = when (voiceState) {
                    is VoiceState.Listening -> {
                        if (voiceState.partial.isNotEmpty()) voiceState.partial else "Listening..."
                    }
                    is VoiceState.Speaking -> ""
                    is VoiceState.Heard -> "Thinking..."
                    is VoiceState.Error -> voiceState.reason
                    is VoiceState.Idle -> if (lastAssistant != null) "Tap to continue" else "Tap to speak"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when (voiceState) {
                    is VoiceState.Error -> OakPalette.Error
                    is VoiceState.Listening -> OakPalette.Primary
                    else -> OakPalette.TextTertiary
                },
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp),
            )
        }
    }
}

/**
 * Audio-reactive orb that responds to voice amplitude.
 * Multiple concentric rings pulse and scale based on audio level.
 * Inspired by ChatGPT's voice mode — organic, fluid motion.
 */
@Composable
private fun AudioReactiveOrb(
    voiceState: VoiceState,
    amplitude: Float,
    onTap: () -> Unit,
    size: Dp = 160.dp,
) {
    val isActive = voiceState is VoiceState.Listening || voiceState is VoiceState.Speaking
    val isListening = voiceState is VoiceState.Listening
    val isSpeaking = voiceState is VoiceState.Speaking
    val isThinking = voiceState is VoiceState.Heard

    // Smooth the amplitude for fluid animation
    val smoothAmplitude by animateFloatAsState(
        targetValue = amplitude,
        animationSpec = tween(100, easing = LinearEasing),
        label = "amp",
    )

    // Continuous time for organic wave motion
    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "time",
    )

    // Breathing pulse when idle
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "idle",
    )

    // Thinking pulse (faster)
    val thinkPulse by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "think",
    )

    val baseColor = when {
        isListening -> OakPalette.Primary
        isSpeaking -> OakPalette.Primary
        isThinking -> OakPalette.Primary.copy(alpha = 0.7f)
        else -> OakPalette.SurfaceVariant
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size * 1.8f) // Extra space for rings
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            ),
    ) {
        // Animated rings drawn on Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val baseRadius = this.size.minDimension / 2 * 0.45f

            if (isActive || isThinking) {
                // Outer ring 3 — large, faint
                val r3Scale = if (isActive) 1.5f + smoothAmplitude * 0.4f + sin(time) * 0.05f
                              else thinkPulse * 1.4f
                val r3Alpha = if (isActive) 0.06f + smoothAmplitude * 0.08f else 0.05f
                drawCircle(
                    color = baseColor.copy(alpha = r3Alpha),
                    radius = baseRadius * r3Scale,
                    center = center,
                )

                // Outer ring 2
                val r2Scale = if (isActive) 1.25f + smoothAmplitude * 0.3f + sin(time * 1.3f) * 0.04f
                              else thinkPulse * 1.2f
                val r2Alpha = if (isActive) 0.1f + smoothAmplitude * 0.12f else 0.08f
                drawCircle(
                    color = baseColor.copy(alpha = r2Alpha),
                    radius = baseRadius * r2Scale,
                    center = center,
                )

                // Inner ring 1
                val r1Scale = if (isActive) 1.08f + smoothAmplitude * 0.15f + sin(time * 1.7f) * 0.03f
                              else thinkPulse * 1.05f
                val r1Alpha = if (isActive) 0.15f + smoothAmplitude * 0.15f else 0.12f
                drawCircle(
                    color = baseColor.copy(alpha = r1Alpha),
                    radius = baseRadius * r1Scale,
                    center = center,
                )
            }
        }

        // Main orb
        val orbScale = when {
            isActive -> 1f + smoothAmplitude * 0.08f
            isThinking -> thinkPulse
            else -> idlePulse
        }

        Surface(
            modifier = Modifier
                .size(size * 0.55f * orbScale),
            shape = CircleShape,
            color = baseColor,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.background(
                    brush = Brush.radialGradient(
                        colors = if (isActive) listOf(
                            baseColor.copy(alpha = 0.9f),
                            baseColor,
                        ) else listOf(baseColor, baseColor),
                    )
                ),
            ) {
                if (!isActive && !isThinking) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = "Tap to speak",
                        modifier = Modifier.size(size * 0.15f),
                        tint = if (voiceState is VoiceState.Idle)
                            OakPalette.TextSecondary
                        else
                            OakPalette.OnPrimary,
                    )
                }
            }
        }
    }
}

data class VoiceTurn(
    val role: VoiceTurnRole,
    val text: String,
)

enum class VoiceTurnRole { USER, ASSISTANT }
