package dev.governance.android.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.governance.android.app.R
import dev.governance.android.app.ui.ActionTemplates
import dev.governance.android.app.ui.components.VerifiedBadge
import dev.governance.android.app.ui.theme.BloomPalette
import dev.governance.android.app.BuildConfig
import dev.governance.core.GateDecision
import dev.governance.core.GovernanceSnapshot
import dev.governance.core.Outcome
import dev.governance.android.app.ui.RelativeTime

/**
 * Home screen: hero card showing agent health, activity observations,
 * and last 5 decisions in plain English. Stock Material 3 surfaces
 * with brand accent colors on trust-state indicators.
 */
@Composable
fun HomeScreen(
    snapshot: GovernanceSnapshot?,
    recentDecisions: List<GateDecision>,
    observationCount: Int,
    errorCount: Int,
    onDecisionTap: (GateDecision) -> Unit,
    onSeeDetails: () -> Unit,
    onChatTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // Debug-only banner when not using Ed25519
        if (BuildConfig.DEBUG && snapshot != null && snapshot.signingAlgorithm != "Ed25519") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    "Debug build \u2014 using software ${snapshot.signingAlgorithm}. Not for production.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // Hero card
        val needsAttention = snapshot != null && (snapshot.gamma > 0.6 || errorCount > 0)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (needsAttention)
                    MaterialTheme.colorScheme.errorContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (needsAttention) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(
                            if (needsAttention) R.string.home_needs_attention
                            else R.string.home_working_well
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                Spacer(Modifier.height(8.dp))
                VerifiedBadge()
                Spacer(Modifier.height(16.dp))

                if (snapshot != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        CountChip(snapshot.recentOutcomes.pass, stringResource(R.string.home_approved_today))
                        CountChip(snapshot.recentOutcomes.hold, stringResource(R.string.home_asked_today))
                        CountChip(snapshot.recentOutcomes.veto, stringResource(R.string.home_blocked_today))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Activity strip
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSeeDetails() },
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.home_observations, observationCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (errorCount == 0) stringResource(R.string.home_all_clear)
                    else stringResource(R.string.home_errors_detected, errorCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (errorCount > 0)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Chat entry point
        onChatTap?.let { onTap ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTap() },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Text(
                    "Ask your agent...",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        Text(
            stringResource(R.string.home_recent_activity),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))

        // Recent decisions
        recentDecisions.sortedByDescending { it.timestamp }.take(5).forEach { decision ->
            val time = RelativeTime.format(decision.timestamp)
            val label = ActionTemplates.pastTenseLabel(decision.actionKind)
            val outcome = ActionTemplates.outcomeLabel(decision.outcome)

            ListItem(
                modifier = Modifier.clickable { onDecisionTap(decision) },
                headlineContent = {
                    Text("$time \u2014 $label \u00b7 $outcome")
                },
                leadingContent = {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = when (decision.outcome) {
                            Outcome.PASS -> BloomPalette.TrustGreen
                            Outcome.HOLD -> BloomPalette.WarnAmber
                            Outcome.VETO -> BloomPalette.DangerRed
                        },
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun CountChip(count: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
