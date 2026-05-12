package dev.governance.android.testagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

class MainActivity : ComponentActivity() {

    private lateinit var client: KernelClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = KernelClient(this)
        client.bind()

        setContent {
            MaterialTheme {
                TestAgentScreen(client)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (client.connected) client.unbind()
    }
}

@Composable
private fun TestAgentScreen(client: KernelClient) {
    var log by remember { mutableStateOf("Waiting for service connection...\n") }
    val scope = rememberCoroutineScope()

    fun appendLog(msg: String) { log += "$msg\n" }

    fun runScenario(name: String, actionJson: String) {
        scope.launch(Dispatchers.IO) {
            appendLog("--- $name ---")
            try {
                val resultJson = client.decide(actionJson)
                val parsed = Json.parseToJsonElement(resultJson).jsonObject
                val outcome = parsed["outcome"]?.jsonPrimitive?.content ?: "?"
                val hash = parsed["attestation"]?.jsonObject?.get("contentHash")
                    ?.jsonPrimitive?.content?.take(16) ?: "?"
                val seq = parsed["sequenceNumber"]?.jsonPrimitive?.content ?: "?"
                appendLog("Outcome: $outcome | seq: $seq | hash: $hash...")

                // Resolve with BenignSuccess
                val auditId = parsed["auditId"]?.jsonObject?.get("value")
                    ?.jsonPrimitive?.content
                if (auditId != null) {
                    client.resolve(auditId, Scenarios.benignOutcome())
                    appendLog("Resolved: BenignSuccess")
                }
            } catch (e: Exception) {
                appendLog("ERROR: ${e.message}")
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Sparrow Test Agent", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (client.connected) "Connected" else "Connecting...",
                color = if (client.connected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )

            Spacer(Modifier.height(16.dp))

            Button(onClick = { runScenario("Read calendar (reversible)", Scenarios.readCalendar()) },
                modifier = Modifier.fillMaxWidth()) {
                Text("Read calendar (reversible)")
            }

            Button(onClick = { runScenario("Send email (one-shot)", Scenarios.sendEmail()) },
                modifier = Modifier.fillMaxWidth()) {
                Text("Send email (one-shot)")
            }

            Button(onClick = { runScenario("Post photos (irreversible)", Scenarios.postPhotos()) },
                modifier = Modifier.fillMaxWidth()) {
                Text("Post photos (irreversible)")
            }

            Button(onClick = {
                scope.launch(Dispatchers.IO) {
                    appendLog("--- Rate limiter test (250 calls) ---")
                    var passed = 0; var held = 0; var errors = 0
                    repeat(250) { i ->
                        try {
                            val r = client.decide(Scenarios.rateLimiterBurst(i))
                            val o = Json.parseToJsonElement(r).jsonObject["outcome"]
                                ?.jsonPrimitive?.content
                            if (o == "HOLD") held++ else passed++
                        } catch (_: Exception) { errors++ }
                    }
                    appendLog("Results: $passed passed, $held held (rate-limited), $errors errors")
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Run rate limiter test")
            }

            Button(onClick = {
                scope.launch(Dispatchers.IO) {
                    appendLog("--- Gradual escalation (50 calls) ---")
                    repeat(50) { i ->
                        val level = (i / 12).coerceAtMost(3)
                        try {
                            val r = client.decide(Scenarios.escalation(level))
                            val o = Json.parseToJsonElement(r).jsonObject["outcome"]
                                ?.jsonPrimitive?.content ?: "?"
                            if (i % 10 == 0) appendLog("Step $i (level $level): $o")
                        } catch (_: Exception) {}
                    }
                    appendLog("Escalation complete")
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Run gradual escalation")
            }

            Button(onClick = {
                scope.launch(Dispatchers.IO) {
                    appendLog("--- Trigger verification failure ---")
                    try {
                        val resultJson = client.decide(Scenarios.readCalendar())
                        val (origSigPrefix, tamperedSigPrefix, _) =
                            Scenarios.tamperedAttestation(resultJson)
                        appendLog("Original sig: $origSigPrefix...")
                        appendLog("Tampered sig: $tamperedSigPrefix...")
                        appendLog("Signature corrupted. Verification would reject this.")
                        appendLog("Run VerificationFailureRouteTest to confirm rejection.")

                        // Resolve the original decision so kernel state stays clean
                        val parsed = Json.parseToJsonElement(resultJson).jsonObject
                        val auditId = parsed["auditId"]?.jsonObject?.get("value")
                            ?.jsonPrimitive?.content
                        if (auditId != null) {
                            client.resolve(auditId, Scenarios.benignOutcome())
                        }
                    } catch (e: Exception) {
                        appendLog("ERROR: ${e.message}")
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Trigger verification failure")
            }

            Button(onClick = {
                scope.launch(Dispatchers.IO) {
                    appendLog("--- Snapshot ---")
                    try {
                        val snap = client.snapshot()
                        val parsed = Json.parseToJsonElement(snap).jsonObject
                        val gamma = parsed["gamma"]?.jsonPrimitive?.content ?: "?"
                        val warmup = parsed["warmupComplete"]?.jsonPrimitive?.content ?: "?"
                        appendLog("gamma=$gamma warmupComplete=$warmup")
                    } catch (e: Exception) {
                        appendLog("ERROR: ${e.message}")
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Get snapshot")
            }

            Spacer(Modifier.height(16.dp))
            Text("Log:", style = MaterialTheme.typography.titleSmall)
            Text(log, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
