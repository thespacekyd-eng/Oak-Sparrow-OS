package dev.governance.android.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.governance.android.app.agent.LlmMode

/**
 * Settings screen for LLM mode preference.
 */
@Composable
fun SettingsScreen(
    llmMode: LlmMode,
    onLlmModeChange: (LlmMode) -> Unit,
    hasCloudKey: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        // LLM Mode section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("AI Engine", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Choose how Oak processes your requests",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                LlmMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = mode != LlmMode.CLOUD || hasCloudKey) {
                                onLlmModeChange(mode)
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = llmMode == mode,
                            onClick = { if (mode != LlmMode.CLOUD || hasCloudKey) onLlmModeChange(mode) },
                            enabled = mode != LlmMode.CLOUD || hasCloudKey,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                when (mode) {
                                    LlmMode.ON_DEVICE -> "On-device only"
                                    LlmMode.CLOUD -> "Cloud (Claude)"
                                    LlmMode.AUTO -> "Auto (recommended)"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                when (mode) {
                                    LlmMode.ON_DEVICE -> "Private. All processing on your phone. Limited conversation."
                                    LlmMode.CLOUD -> "Best for conversation and complex tasks. PII stripped before sending."
                                    LlmMode.AUTO -> "Cloud for conversation, on-device for simple commands."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (!hasCloudKey) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Cloud modes require an API key in local.properties",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
