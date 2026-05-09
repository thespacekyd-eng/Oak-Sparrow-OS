package dev.governance.android.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.governance.android.app.R
import dev.governance.core.AuditRecord
import dev.governance.core.GovernanceSnapshot
import dev.governance.core.SystemEventRecord
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.Locale

/**
 * Technical detail screen. The only place kernel vocabulary surfaces.
 * Sections: gamma trajectory, attestation chain, calibrator state,
 * system events, capability registry.
 */
@Composable
fun TechnicalDetailScreen(
    snapshot: GovernanceSnapshot?,
    records: List<AuditRecord>,
    systemEvents: List<SystemEventRecord>,
    chainVerified: Boolean,
    chainProblemTime: String?,
    expandSystemEvents: Boolean = false,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Gamma trajectory chart
        item {
            SectionHeader(stringResource(R.string.technical_gamma_trajectory))
            GammaChart(
                gammaValues = records.map { it.decision.gamma },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
            )
        }

        // Attestation chain
        item {
            SectionHeader(stringResource(R.string.technical_attestation_chain))
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (chainVerified) stringResource(R.string.technical_all_verified)
                    else stringResource(R.string.technical_verification_problem, chainProblemTime ?: "unknown"),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (chainVerified) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }
        }

        // Calibrator state
        item {
            SectionHeader(stringResource(R.string.technical_calibrator))
            if (snapshot != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        MonoRow(stringResource(R.string.technical_mode),
                            if (snapshot.warmupComplete) stringResource(R.string.technical_steady)
                            else stringResource(R.string.technical_warmup))
                        MonoRow(stringResource(R.string.technical_current_gamma),
                            String.format(Locale.ROOT, "%.2f", snapshot.gamma))
                        MonoRow(
                            "Signing algorithm",
                            snapshot.signingAlgorithm,
                            valueColor = if (snapshot.signingAlgorithm != "Ed25519")
                                MaterialTheme.colorScheme.error
                            else null,
                        )
                        // Envelope: stacked vertically because the description is long
                        Text(stringResource(R.string.technical_envelope),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp))
                        Text(snapshot.referenceEnvelopeDescription,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }

        // System events (collapsible)
        item {
            var expanded by remember { mutableStateOf(expandSystemEvents) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.technical_system_events),
                    style = MaterialTheme.typography.titleMedium,
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    systemEvents.takeLast(50).reversed().forEach { event ->
                        SystemEventRow(event)
                    }
                }
            }
        }
    }
}

@Composable
private fun GammaChart(gammaValues: List<Double>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline

    Card(modifier = modifier) {
        Canvas(modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)) {
            if (gammaValues.isEmpty()) return@Canvas
            val w = size.width
            val h = size.height

            // HOLD threshold dashed line at 0.5
            val holdY = h * (1f - 0.5f)
            drawLine(
                color = outline,
                start = Offset(0f, holdY),
                end = Offset(w, holdY),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                strokeWidth = 1f,
            )

            // Gamma line
            val step = if (gammaValues.size > 1) w / (gammaValues.size - 1) else 0f
            for (i in 0 until gammaValues.size - 1) {
                val x1 = i * step
                val y1 = h * (1f - gammaValues[i].toFloat().coerceIn(0f, 1f))
                val x2 = (i + 1) * step
                val y2 = h * (1f - gammaValues[i + 1].toFloat().coerceIn(0f, 1f))
                drawLine(primary, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2f)
            }

            // Inflection dots
            gammaValues.forEachIndexed { i, g ->
                val x = i * step
                val y = h * (1f - g.toFloat().coerceIn(0f, 1f))
                if (i > 0 && i < gammaValues.size - 1) {
                    val prev = gammaValues[i - 1]
                    val next = gammaValues[i + 1]
                    if ((g > prev && g > next) || (g < prev && g < next)) {
                        drawCircle(primary, radius = 4f, center = Offset(x, y))
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemEventRow(event: SystemEventRecord) {
    val time = event.timestamp
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .let { "%02d:%02d".format(it.hour, it.minute) }
    val severityColor = when (event.severity) {
        SystemEventRecord.Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        SystemEventRecord.Severity.WARN -> Color(0xFFFF9800)
        SystemEventRecord.Severity.ERROR -> MaterialTheme.colorScheme.error
    }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(time, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            Text(event.kind, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = severityColor)
        }
        Text(event.message, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 0.dp, top = 2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonoRow(label: String, value: String, valueColor: Color? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = valueColor ?: Color.Unspecified,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
}
