package dev.governance.android.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.governance.android.app.agent.AuthorizationResultBridge
import dev.governance.android.app.ui.ActionTemplates
import dev.governance.android.app.ui.screens.VerificationFailureScreen
import dev.governance.android.app.ui.theme.BloomPalette
import dev.governance.android.app.ui.theme.OakSparrowTheme
import dev.governance.attestation.AttestationVerifier
import dev.governance.core.GateDecision
import dev.governance.core.Reversibility
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * HOLD confirmation dialog. Trusted-display surface with solid
 * dim scrim and black card — visually distinct from agent-renderable
 * content.
 *
 * Displays a plain-English question derived from the action kind,
 * with Skip / Approve buttons and a 14-second auto-deny countdown.
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
                            android.util.Log.i("AuthorizationActivity", "onApprove: delivering result true")
                            AuthorizationResultBridge.deliverResult(true)
                            android.util.Log.i("AuthorizationActivity", "onApprove: result delivered, finishing")
                            setResult(RESULT_OK)
                            finish()
                        },
                        onSkip = {
                            android.util.Log.i("AuthorizationActivity", "onSkip: delivering result false")
                            AuthorizationResultBridge.deliverResult(false)
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

    // Solid dim scrim — no Bloom backdrop
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.55f)) {
        Box(contentAlignment = Alignment.Center) {
            // Dark confirmation card
            Surface(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 400.dp),
                color = Color.Black,
                contentColor = Color.White,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)),
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    // System header
                    Text(
                        "SYSTEM \u00b7 CONFIRM",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(12.dp))

                    Text(
                        stringResource(questionResId, target),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                    )

                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = if (isIrreversible) BloomPalette.WarnAmber else BloomPalette.PetalCyan,
                        trackColor = Color.White.copy(alpha = 0.12f),
                    )

                    Spacer(Modifier.height(16.dp))

                    val bodyTemplate = if (isIrreversible)
                        stringResource(R.string.auth_body_irreversible, ActionTemplates.infinitivePhrase(decision.actionKind))
                    else
                        stringResource(R.string.auth_body_template, ActionTemplates.infinitivePhrase(decision.actionKind))
                    Text(
                        bodyTemplate,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )

                    Spacer(Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onSkip) {
                            Text(
                                stringResource(R.string.auth_skip),
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (isIrreversible) {
                            Button(
                                onClick = onApprove,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black,
                                ),
                            ) {
                                Text(stringResource(R.string.auth_approve))
                            }
                        } else {
                            OutlinedButton(
                                onClick = onApprove,
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                            ) {
                                Text(
                                    stringResource(R.string.auth_approve),
                                    color = Color.White,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(
                            if (isIrreversible) R.string.auth_verified_irreversible
                            else R.string.auth_verified
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}
