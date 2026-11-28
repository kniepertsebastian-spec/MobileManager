package app.mobilemanager.ui.appstopper

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.mobilemanager.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobilemanager.data.shizuku.ShizukuState
import app.mobilemanager.domain.model.ForceStopMethod
import app.mobilemanager.domain.model.InstalledApp
import app.mobilemanager.domain.model.StopProgress
import app.mobilemanager.ui.components.AppIcon
import app.mobilemanager.ui.components.formatAgo

private val THRESHOLD_OPTIONS = listOf(6, 12, 24, 48, 72)

@Composable
fun AppStopperScreen(
    onOpenPermissions: () -> Unit,
    viewModel: AppStopperViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reload() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { EngineBanner(state, onOpenPermissions) }
            if (!state.hasUsageAccess) {
                item {
                    WarningCard(
                        "Nutzungszugriff fehlt – ohne ihn kann nicht erkannt werden, wann Apps zuletzt genutzt wurden.",
                        "Berechtigen",
                        onOpenPermissions,
                    )
                }
            }
            item {
                Text("Inaktiv seit mindestens", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    THRESHOLD_OPTIONS.forEach { hours ->
                        FilterChip(
                            selected = state.thresholdHours == hours,
                            onClick = { viewModel.setThreshold(hours) },
                            label = { Text("${hours} h") },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("System-Apps anzeigen", Modifier.weight(1f))
                    Switch(checked = state.showSystemApps, onCheckedChange = viewModel::setShowSystemApps)
                }
            }
            item {
                Text(
                    "${state.candidates.size} inaktive Apps werden beim Aufräumen gestoppt",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (state.loading && state.apps.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            items(state.apps, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    isCandidate = app in state.candidates,
                    canStop = state.method != null,
                    onWhitelistChange = { viewModel.setWhitelisted(app, it) },
                    onStop = { viewModel.requestStopSingle(app) },
                )
            }
        }
        if (state.candidates.isNotEmpty() && state.method != null) {
            ExtendedFloatingActionButton(
                onClick = viewModel::requestCleanup,
                icon = { Icon(Icons.Default.CleaningServices, contentDescription = null) },
                text = { Text("Jetzt aufräumen (${state.candidates.size})") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    state.confirmStop?.let { targets ->
        ConfirmStopDialog(
            targets = targets,
            method = state.method,
            onConfirm = viewModel::confirmStop,
            onDismiss = viewModel::dismissConfirm,
        )
    }
    state.progress?.let { ProgressDialog(it, onDismiss = viewModel::dismissProgress) }
}

@Composable
private fun EngineBanner(state: AppStopperUiState, onOpenPermissions: () -> Unit) {
    when {
        state.method == ForceStopMethod.SHIZUKU -> Unit
        state.method == ForceStopMethod.ACCESSIBILITY -> WarningCard(
            "Shizuku ist nicht aktiv – es wird der Bedienungshilfe-Fallback genutzt (öffnet kurz die App-Infos).",
            "Shizuku einrichten",
            onOpenPermissions,
        )
        else -> WarningCard(
            when (state.shizukuState) {
                ShizukuState.NOT_INSTALLED -> "Shizuku ist nicht installiert. Ohne Shizuku (oder den Bedienungshilfe-Fallback) kann MobileManager keine Apps beenden."
                ShizukuState.NOT_RUNNING -> "Der Shizuku-Dienst läuft nicht – nach einem Neustart muss er in der Shizuku-App neu gestartet werden."
                ShizukuState.NO_PERMISSION -> "Shizuku läuft, aber MobileManager hat noch keine Berechtigung."
                ShizukuState.READY -> "Shizuku bereit."
            },
            "Beheben",
            onOpenPermissions,
        )
    }
}

@Composable
private fun WarningCard(text: String, action: String, onAction: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = stringResource(R.string.cd_warning))
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text(action) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppRow(
    app: InstalledApp,
    isCandidate: Boolean,
    canStop: Boolean,
    onWhitelistChange: (Boolean) -> Unit,
    onStop: () -> Unit,
) {
    val now = System.currentTimeMillis()
    ListItem(
        leadingContent = { AppIcon(app.packageName) },
        supportingContent = {
            Column {
                Text(formatAgo(app.lastUsed, now))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (app.isStopped) AssistChip(onClick = {}, label = { Text("Gestoppt") })
                    app.autoProtection?.let {
                        AssistChip(
                            onClick = {},
                            label = { Text("Geschützt: ${it.label}") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        )
                    }
                    if (isCandidate) AssistChip(onClick = {}, label = { Text("Wird gestoppt") })
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canStop && !app.isProtected && !app.isStopped) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.StopCircle, contentDescription = "Beenden erzwingen")
                    }
                }
                if (app.autoProtection == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Switch(checked = app.isWhitelisted, onCheckedChange = onWhitelistChange)
                        Text("Whitelist", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
    ) { Text(app.label) }
}

@Composable
private fun ConfirmStopDialog(
    targets: List<InstalledApp>,
    method: ForceStopMethod?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${targets.size} Apps beenden erzwingen?") },
        text = {
            Column {
                if (method == ForceStopMethod.ACCESSIBILITY) {
                    Text(
                        "Fallback-Modus: Für jede App öffnen sich kurz die App-Infos.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(targets, key = { it.packageName }) { app ->
                        ListItem(
                            leadingContent = { AppIcon(app.packageName, size = 32.dp) },
                            supportingContent = { Text(formatAgo(app.lastUsed)) },
                        ) { Text(app.label) }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = method != null) { Text("Beenden erzwingen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun ProgressDialog(progress: StopProgress, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (progress.finished) onDismiss() },
        title = { Text(if (progress.finished) "Fertig" else "Apps werden beendet …") },
        text = {
            Column {
                if (progress.finished) {
                    Text("${progress.succeeded} Apps gestoppt" + if (progress.failed > 0) ", ${progress.failed} fehlgeschlagen" else "")
                } else {
                    Text("${progress.current + 1}/${progress.total}: ${progress.currentLabel}")
                    LinearProgressIndicator(
                        progress = { if (progress.total == 0) 0f else progress.current.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (progress.finished) TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}
