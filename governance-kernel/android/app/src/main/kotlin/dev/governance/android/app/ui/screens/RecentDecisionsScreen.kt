package dev.governance.android.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.governance.android.app.R
import dev.governance.android.app.ui.ActionTemplates
import dev.governance.attestation.AttestationVerifier
import dev.governance.core.GateDecision
import dev.governance.core.Outcome
import dev.governance.android.app.ui.RelativeTime

/**
 * Full audit log viewer. Reverse-chronological list of decisions
 * in plain English. Expandable rows show attestation details.
 */
@Composable
fun RecentDecisionsScreen(
    decisions: List<GateDecision>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(decisions.sortedByDescending { it.timestamp }) { decision ->
            DecisionRow(decision)
        }
    }
}

@Composable
private fun DecisionRow(decision: GateDecision) {
    var expanded by remember { mutableStateOf(false) }
    var verified by remember { mutableStateOf<Boolean?>(null) }
    val time = RelativeTime.format(decision.timestamp)
    val label = ActionTemplates.pastTenseLabel(decision.actionKind)
    val outcome = ActionTemplates.outcomeLabel(decision.outcome)

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = when (decision.outcome) {
                        Outcome.PASS -> MaterialTheme.colorScheme.primary
                        Outcome.HOLD -> MaterialTheme.colorScheme.tertiary
                        Outcome.VETO -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "$time \u2014 $label \u00b7 $outcome",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text(
                        decision.rationale,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.decisions_attestation_prefix,
                            decision.attestation.contentHash.take(16),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(onClick = {
                        verified = AttestationVerifier.verify(decision)
                    }) {
                        if (verified == null) {
                            Text(stringResource(R.string.decisions_verify))
                        } else if (verified == true) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.decisions_verified))
                        } else {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.decisions_verification_failed))
                        }
                    }
                }
            }
        }
    }
}
