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
                    Icon(Icons.Default.Warning, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text("${state.missingPermissions} Berechtigungen fehlen", Modifier.weight(1f))
                    TextButton(onClick = onOpenPermissions) { Text("Prüfen") }
                }
            }
        }

        // 1. App-Stopper
        StatusCard(icon = Icons.Default.StopCircle, title = "App-Stopper") {
            val count = state.inactiveApps?.size
            Text(
                when (count) {
                    null -> "Wird geprüft …"
                    0 -> "Keine inaktiven Apps gefunden"
                    1 -> "1 inaktive App gefunden"
                    else -> "$count inaktive Apps gefunden"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            val progress = state.stopProgress
            when {
                progress != null && !progress.finished -> {
                    Text("Stoppe ${progress.currentLabel} (${progress.current + 1}/${progress.total})")
                    LinearProgressIndicator(
                        progress = { if (progress.total == 0) 0f else progress.current.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                progress != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${progress.succeeded} Apps gestoppt" + if (progress.failed > 0) ", ${progress.failed} Fehler" else "",
                        Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::dismissStopResult) { Text("OK") }
                }
                state.stopMethod == null -> Text(
                    "Shizuku ist nicht aktiv – Apps können derzeit nicht beendet werden.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    onClick = viewModel::stopAll,
                    enabled = (count ?: 0) > 0 && state.stopMethod != null && progress?.finished != false,
                ) { Text("Alle stoppen") }
                OutlinedButton(onClick = onOpenAppStopper) { Text("Details") }
            }
        }

        // 2. Speicher
        StatusCard(icon = Icons.Default.SdStorage, title = "Speicher") {
            val storage = state.storage
            if (storage != null) {
                Text("${formatBytes(context, storage.freeBytes)} frei", style = MaterialTheme.typography.headlineSmall)
                LinearProgressIndicator(
                    progress = { 1f - storage.freeBytes.toFloat() / storage.totalBytes.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Text("von ${formatBytes(context, storage.totalBytes)}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                lastCleanup?.let { "Zuletzt ${formatBytes(context, it.freedBytes)} bereinigt" } ?: "Noch nichts bereinigt",
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(onClick = onStartScan, modifier = Modifier.padding(top = 8.dp)) { Text("Scan starten") }
        }

        // 3. Bluetooth
        StatusCard(icon = Icons.Default.Bluetooth, title = "Bluetooth-Automatik") {
            Text(
                when (bt.activeRules) {
                    0 -> "Keine aktiven Regeln"
                    1 -> "1 aktive Regel"
                    else -> "${bt.activeRules} aktive Regeln"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                if (bt.connectedNames.isEmpty()) "Kein Gerät verbunden" else "Verbunden: ${bt.connectedNames.joinToString()}",
            )
            OutlinedButton(onClick = onOpenAutomation, modifier = Modifier.padding(top = 8.dp)) { Text("Regeln verwalten") }
        }

        // 4. Wächter (Phase 2)
        StatusCard(icon = Icons.Default.Shield, title = "Wächter") {
            Text(
                battery?.let { "Akku ${it.levelPercent} % · ${"%.1f".format(it.temperatureCelsius)} °C" } ?: "Akku wird gelesen …",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                nightReport?.let { "Nacht-Standby: −${it.dropPercent} % in ${"%.1f".format(it.hours)} h" }
                    ?: "Noch kein Nacht-Standby-Bericht",
            )
            if (unseenEvents > 0) {
                Text(
                    if (unseenEvents == 1) "1 neues Sicherheitsereignis" else "$unseenEvents neue Sicherheitsereignisse",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onOpenGuard, modifier = Modifier.padding(top = 8.dp)) { Text("Wächter öffnen") }
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
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.size(8.dp))
            content()
        }
    }
}
