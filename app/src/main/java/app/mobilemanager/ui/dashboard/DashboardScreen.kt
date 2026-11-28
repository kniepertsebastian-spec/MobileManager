package app.mobilemanager.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.mobilemanager.R
import app.mobilemanager.ui.components.formatBytes

@Composable
fun DashboardScreen(
    onOpenAppStopper: () -> Unit,
    onStartScan: () -> Unit,
    onOpenAutomation: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenGuard: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lastCleanup by viewModel.lastCleanup.collectAsStateWithLifecycle()
    val bt by viewModel.bluetoothSummary.collectAsStateWithLifecycle()
    val battery by viewModel.battery.collectAsStateWithLifecycle()
    val nightReport by viewModel.nightReport.collectAsStateWithLifecycle()
    val unseenEvents by viewModel.unseenSecurityEvents.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.missingPermissions > 0) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = stringResource(R.string.cd_warning))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        pluralStringResource(R.plurals.dashboard_missing_permissions, state.missingPermissions, state.missingPermissions),
                        Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenPermissions) { Text(stringResource(R.string.dashboard_check)) }
                }
            }
        }

        // 1. App-Stopper
        StatusCard(icon = Icons.Default.StopCircle, title = stringResource(R.string.nav_app_stopper)) {
            val count = state.inactiveApps?.size
            Text(
                when (count) {
                    null -> stringResource(R.string.dashboard_checking)
                    0 -> stringResource(R.string.dashboard_no_inactive)
                    else -> pluralStringResource(R.plurals.dashboard_inactive_apps, count, count)
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            val progress = state.stopProgress
            when {
                progress != null && !progress.finished -> {
                    Text(stringResource(R.string.dashboard_stopping, progress.currentLabel, progress.current + 1, progress.total))
                    LinearProgressIndicator(
                        progress = { if (progress.total == 0) 0f else progress.current.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                progress != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.dashboard_stopped, progress.succeeded, progress.succeeded) +
                            if (progress.failed > 0) pluralStringResource(R.plurals.dashboard_stop_errors, progress.failed, progress.failed) else "",
                        Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::dismissStopResult) { Text(stringResource(R.string.action_ok)) }
                }
                state.stopMethod == null -> Text(
                    stringResource(R.string.dashboard_shizuku_inactive),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    onClick = viewModel::stopAll,
                    enabled = (count ?: 0) > 0 && state.stopMethod != null && progress?.finished != false,
                ) { Text(stringResource(R.string.dashboard_stop_all)) }
                OutlinedButton(onClick = onOpenAppStopper) { Text(stringResource(R.string.action_details)) }
            }
        }

        // 2. Speicher
        StatusCard(icon = Icons.Default.SdStorage, title = stringResource(R.string.dashboard_storage)) {
            val storage = state.storage
            if (storage != null) {
                Text(stringResource(R.string.dashboard_free, formatBytes(context, storage.freeBytes)), style = MaterialTheme.typography.headlineSmall)
                LinearProgressIndicator(
                    progress = { 1f - storage.freeBytes.toFloat() / storage.totalBytes.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Text(stringResource(R.string.dashboard_of_total, formatBytes(context, storage.totalBytes)), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                lastCleanup?.let { stringResource(R.string.dashboard_last_cleanup, formatBytes(context, it.freedBytes)) }
                    ?: stringResource(R.string.dashboard_no_cleanup),
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(onClick = onStartScan, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.dashboard_start_scan)) }
        }

        // 3. Bluetooth
        StatusCard(icon = Icons.Default.Bluetooth, title = stringResource(R.string.dashboard_bluetooth)) {
            Text(
                if (bt.activeRules == 0) {
                    stringResource(R.string.dashboard_no_rules)
                } else {
                    pluralStringResource(R.plurals.dashboard_active_rules, bt.activeRules, bt.activeRules)
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                if (bt.connectedNames.isEmpty()) {
                    stringResource(R.string.dashboard_no_device)
                } else {
                    stringResource(R.string.dashboard_connected, bt.connectedNames.joinToString())
                },
            )
            OutlinedButton(onClick = onOpenAutomation, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.dashboard_manage_rules)) }
        }

        // 4. Wächter (Phase 2)
        StatusCard(icon = Icons.Default.Shield, title = stringResource(R.string.nav_guard)) {
            Text(
                battery?.let { stringResource(R.string.dashboard_battery, it.levelPercent, it.temperatureCelsius) }
                    ?: stringResource(R.string.dashboard_battery_reading),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                nightReport?.let { stringResource(R.string.dashboard_night_report, it.dropPercent, it.hours) }
                    ?: stringResource(R.string.dashboard_no_night_report),
            )
            if (unseenEvents > 0) {
                Text(
                    pluralStringResource(R.plurals.dashboard_security_events, unseenEvents, unseenEvents),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onOpenGuard, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.dashboard_open_guard)) }
        }
    }
}

@Composable
private fun StatusCard(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
            Spacer(Modifier.size(8.dp))
            content()
        }
    }
}
