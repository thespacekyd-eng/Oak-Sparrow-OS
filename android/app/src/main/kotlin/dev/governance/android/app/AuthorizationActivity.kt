package dev.governance.android.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.governance.android.app.ui.ActionTemplates
import dev.governance.android.app.ui.screens.VerificationFailureScreen
import dev.governance.android.app.ui.theme.OakSparrowTheme
import dev.governance.attestation.AttestationVerifier
import dev.governance.core.GateDecision
import dev.governance.core.Reversibility
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * HOLD confirmation dialog. Displays a plain-English question derived
 * from the action kind, with Skip / Approve buttons and a 14-second
 * auto-deny countdown.
 *
 * Launched by the service when a HOLD decision needs user confirmation.
 * The decision is passed as a JSON extra.
 */
class AuthorizationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val decisionJson = intent.getStringExtra(EXTRA_DECISION_JSON)
        val decision = decisionJson?.let {
            try {
                Json.decodeFromString(GateDecision.serializer(), it)
            } catch (_: Exception) { null }
        }

        setContent {
            OakSparrowTheme {
                if (decision == null || !AttestationVerifier.verify(decision)) {
                    VerificationFailureScreen(onDiagnose = { finish() })
                } else {
                    HoldConfirmationDialog(
                        decision = decision,
                        onApprove = {
                            setResult(RESULT_OK)
                            finish()
                        },
                        onSkip = {
                            setResult(RESULT_CANCELED)
                            finish()
                        },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_DECISION_JSON = "decision_json"
    }
}

@Composable
internal fun HoldConfirmationDialog(
    decision: GateDecision,
    onApprove: () -> Unit,
    onSkip: () -> Unit,
) {
    val autoTimeoutSeconds = 14
    var remaining by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(Unit) {
        val startMs = System.currentTimeMillis()
        while (remaining > 0f) {
            delay(100)
            val elapsed = (System.currentTimeMillis() - startMs) / (autoTimeoutSeconds * 1000f)
            remaining = (1f - elapsed).coerceAtLeast(0f)
        }
        onSkip() // auto-deny on timeout
    }

    val animatedProgress by animateFloatAsState(targetValue = remaining, label = "countdown")

    val target = ActionTemplates.targetFromDecision(decision)
    val questionResId = ActionTemplates.questionResId(decision.actionKind)
    val isIrreversible = decision.reversibility == Reversibility.OneShot ||
        decision.reversibility == Reversibility.Irreversible

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)) {
        Box(contentAlignment = Alignment.Center) {
            Card(modifier = Modifier.padding(24.dp).widthIn(max = 400.dp)) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        stringResource(questionResId, target),
                        style = MaterialTheme.typography.headlineSmall,
                    )

                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )

                    Spacer(Modifier.height(16.dp))

                    val bodyTemplate = if (isIrreversible)
                        stringResource(R.string.auth_body_irreversible, ActionTemplates.infinitivePhrase(decision.actionKind))
                    else
                        stringResource(R.string.auth_body_template, ActionTemplates.infinitivePhrase(decision.actionKind))
                    Text(bodyTemplate, style = MaterialTheme.typography.bodyMedium)

                    Spacer(Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onSkip) {
                            Text(stringResource(R.string.auth_skip))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onApprove) {
                            Text(stringResource(R.string.auth_approve))
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(
                            if (isIrreversible) R.string.auth_verified_irreversible
                            else R.string.auth_verified
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
