package dev.governance.android.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.governance.android.app.R
import dev.governance.android.app.ui.theme.BloomPalette

/**
 * Fallback screen when attestation verification fails on any
 * decision-derived surface. Blocks interaction and directs the
 * user to the technical detail screen for diagnosis.
 *
 * Uses Material 3 surfaceVariant card with DangerRed brand
 * accents on icon and headline to signal error distinctly.
 */
@Composable
fun VerificationFailureScreen(
    onDiagnose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.padding(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = BloomPalette.DangerRed,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.verification_failure_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = BloomPalette.DangerRed,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.verification_failure_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onDiagnose) {
                    Text(stringResource(R.string.verification_failure_diagnose))
                }
            }
        }
    }
}
