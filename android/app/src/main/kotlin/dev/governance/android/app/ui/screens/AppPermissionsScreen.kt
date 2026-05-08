package dev.governance.android.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.governance.android.app.R

/**
 * Per-app capability list. Apps the agent has used, each with
 * Full / Scoped / Denied segmented control and inline sub-action toggles.
 *
 * Persistence: writes to DataStore Preferences as JSON keyed by package name.
 * // PHASE2C-FOLLOWUP: Replace DataStore JSON with a proper Room database
 * // when the capability model becomes richer (per-action-kind scoping).
 */

enum class AppPermissionLevel { FULL, SCOPED, DENIED }

data class AppCapability(
    val packageName: String,
    val appName: String,
    val level: AppPermissionLevel = AppPermissionLevel.SCOPED,
    val readAllowed: Boolean = true,
    val writeAllowed: Boolean = true,
    val sendAllowed: Boolean = false,
    val deleteAllowed: Boolean = false,
)

@Composable
fun AppPermissionsScreen(
    apps: List<AppCapability>,
    onUpdate: (AppCapability) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(apps, key = { it.packageName }) { app ->
            AppPermissionRow(app, onUpdate)
        }
    }
}

@Composable
private fun AppPermissionRow(
    app: AppCapability,
    onUpdate: (AppCapability) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(app.appName, style = MaterialTheme.typography.titleMedium)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(12.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AppPermissionLevel.entries.forEachIndexed { index, level ->
                    SegmentedButton(
                        selected = app.level == level,
                        onClick = {
                            onUpdate(app.copy(level = level))
                            expanded = level == AppPermissionLevel.SCOPED
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            index, AppPermissionLevel.entries.size
                        ),
                    ) {
                        Text(
                            when (level) {
                                AppPermissionLevel.FULL -> stringResource(R.string.permissions_full)
                                AppPermissionLevel.SCOPED -> stringResource(R.string.permissions_scoped)
                                AppPermissionLevel.DENIED -> stringResource(R.string.permissions_denied)
                            },
                        )
                    }
                }
            }

            AnimatedVisibility(visible = app.level == AppPermissionLevel.SCOPED && expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    ScopeToggle("Read", app.readAllowed) { onUpdate(app.copy(readAllowed = it)) }
                    ScopeToggle("Write", app.writeAllowed) { onUpdate(app.copy(writeAllowed = it)) }
                    ScopeToggle("Send", app.sendAllowed) { onUpdate(app.copy(sendAllowed = it)) }
                    ScopeToggle("Delete", app.deleteAllowed) { onUpdate(app.copy(deleteAllowed = it)) }
                }
            }
        }
    }
}

@Composable
private fun ScopeToggle(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}
