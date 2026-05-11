package dev.governance.android.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.governance.android.app.voice.VoiceState

/**
 * Voice-first assistant overlay. Translucent, sits in front of whatever
 * the user was doing when they invoked the assist gesture (long-press
 * home, etc.). Shows:
 *
 * - Listening state with the live partial transcript
 * - The kernel-mediated response, with a visible microphone affordance
 * - A clear "voice not available / permission denied" state
 *
 * The host activity ([AssistantActivity]) drives the lifecycle. This
 * composable is purely presentational so Paparazzi snapshots can cover
 * each state.
 */
@Composable
fun AssistantOverlayScreen(
    voiceState: VoiceState,
    transcript: String,
    response: String,
    permissionDenied: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(24.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Status row: mic glyph + state label + cancel
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val (label, tint) = when {
                        permissionDenied -> "Microphone permission needed" to MaterialTheme.colorScheme.error
                        voiceState is VoiceState.Listening -> "Listening..." to MaterialTheme.colorScheme.primary
                        voiceState is VoiceState.Speaking -> "Responding..." to MaterialTheme.colorScheme.tertiary
                        voiceState is VoiceState.Heard -> "Got it." to MaterialTheme.colorScheme.primary
                        voiceState is VoiceState.Error -> "Voice error" to MaterialTheme.colorScheme.error
                        else -> "Tap microphone to talk" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(tint.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tint,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Transcript area
                if (permissionDenied) {
                    Text(
                        "Oak & Sparrow needs microphone access to listen. " +
                            "Grant permission in Settings → Apps → Oak & Sparrow → Permissions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    val partial = (voiceState as? VoiceState.Listening)?.partial.orEmpty()
                    val show = when {
                        partial.isNotBlank() -> partial
                        transcript.isNotBlank() -> transcript
                        else -> "Try: \"check my calendar\" or \"open Instagram\"."
                    }
                    Text(
                        show,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Response (after kernel-mediated dispatch)
                if (response.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            response,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                // Error state recovery
                if (voiceState is VoiceState.Error) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        voiceState.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Try again")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Kernel-gated · on-device · no network",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
