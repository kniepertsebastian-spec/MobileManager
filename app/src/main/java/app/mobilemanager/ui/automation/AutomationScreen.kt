package app.mobilemanager.ui.automation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberSliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobilemanager.domain.model.BluetoothRule
import app.mobilemanager.domain.model.EqProfile
import app.mobilemanager.domain.model.LaunchableApp
import app.mobilemanager.ui.components.AppIcon
import app.mobilemanager.ui.components.SectionCard
import kotlin.math.roundToInt

@Composable
fun AutomationScreen(
    onOpenPermissions: () -> Unit,
    viewModel: AutomationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.hasBluetoothPermission || !state.hasOverlayPermission) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        if (!state.hasBluetoothPermission) Text("Bluetooth-Berechtigung fehlt – Geräte können nicht erkannt werden.")
                        if (!state.hasOverlayPermission) {
                            Text("„Über anderen Apps einblenden“ fehlt – Android blockiert sonst den App-Start aus dem Hintergrund.")
                        }
                        TextButton(onClick = onOpenPermissions, modifier = Modifier.align(Alignment.End)) { Text("Berechtigen") }
                    }
                }
            }
        }
        if (state.hasBluetoothPermission && !state.bluetoothEnabled) {
            item { Text("Bluetooth ist ausgeschaltet.", color = MaterialTheme.colorScheme.error) }
        }

        item { Text("Regeln", style = MaterialTheme.typography.titleMedium) }
        if (rules.isEmpty()) {
            item { Text("Noch keine Regeln. Wähle unten ein gekoppeltes Gerät aus.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(rules, key = { it.id }) { rule ->
            RuleCard(
                rule = rule,
                isConnected = rule.deviceAddress in connected,
                appLabel = state.launchableApps.firstOrNull { it.packageName == rule.launchPackageName }?.label
                    ?: rule.launchPackageName,
                onToggle = { viewModel.setEnabled(rule, it) },
                onEdit = { viewModel.edit(rule) },
                onDelete = { viewModel.delete(rule) },
                onTest = { viewModel.test(rule) },
            )
        }

        item { Text("Gekoppelte Geräte", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (state.hasBluetoothPermission && state.pairedDevices.isEmpty()) {
            item { Text("Keine gekoppelten Geräte gefunden.") }
        }
        items(state.pairedDevices, key = { "device_" + it.address }) { device ->
            val isConnected = device.address in connected
            ListItem(
                modifier = Modifier.clickable { viewModel.newRule(device) },
                leadingContent = {
                    Icon(if (isConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth, contentDescription = null)
                },
                supportingContent = { Text(if (isConnected) "Verbunden · Tippen für neue Regel" else "Tippen für neue Regel") },
            ) { Text(device.name) }
        }
    }

    state.editing?.let { rule ->
        RuleEditorDialog(
            rule = rule,
            apps = state.launchableApps,
            onChange = viewModel::updateEditing,
            onSave = viewModel::saveEditing,
            onDismiss = viewModel::cancelEditing,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleCard(
    rule: BluetoothRule,
    isConnected: Boolean,
    appLabel: String?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth, contentDescription = null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(rule.deviceName, style = MaterialTheme.typography.titleMedium)
                if (isConnected) Text("Verbunden", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Switch(checked = rule.isEnabled, onCheckedChange = onToggle)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            Text("Lautstärke: ${rule.targetVolume?.let { "$it %" } ?: "unverändert"}")
            Text("App: ${appLabel ?: "–"}")
            Text("EQ: ${EqProfile.byId(rule.eqProfileId)?.label ?: "aus"}")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onTest) { Icon(Icons.Default.PlayArrow, contentDescription = "Jetzt testen") }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Bearbeiten") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Löschen") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RuleEditorDialog(
    rule: BluetoothRule,
    apps: List<LaunchableApp>,
    onChange: (BluetoothRule) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(rule.deviceName) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Lautstärke
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = rule.targetVolume != null,
                        onCheckedChange = { onChange(rule.copy(targetVolume = if (it) 60 else null)) },
                    )
                    Text("Medienlautstärke setzen" + (rule.targetVolume?.let { ": $it %" } ?: ""))
                }
                if (rule.targetVolume != null) {
                    // value, steps (5-%-Schritte), valueRange
                    val sliderState = rememberSliderState(rule.targetVolume.toFloat(), 19, 0f..100f)
                    Slider(
                        state = sliderState,
                        onValueChange = {
                            sliderState.value = it
                            onChange(rule.copy(targetVolume = it.roundToInt()))
                        },
                    )
                }

                // App
                Text("App starten", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = apps.firstOrNull { it.packageName == rule.launchPackageName }?.label
                            ?: rule.launchPackageName ?: "Keine",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        DropdownMenuItem(
                            text = { Text("Keine") },
                            onClick = {
                                onChange(rule.copy(launchPackageName = null))
                                expanded = false
                            },
                        )
                        apps.forEach { app ->
                            DropdownMenuItem(
                                leadingIcon = { AppIcon(app.packageName, size = 24.dp) },
                                text = { Text(app.label) },
                                onClick = {
                                    onChange(rule.copy(launchPackageName = app.packageName))
                                    expanded = false
                                },
                            )
                        }
                    }
                }

                // EQ
                Text("Equalizer-Profil", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = rule.eqProfileId == null,
                        onClick = { onChange(rule.copy(eqProfileId = null)) },
                        label = { Text("Aus") },
                    )
                    EqProfile.entries.forEach { profile ->
                        FilterChip(
                            selected = rule.eqProfileId == profile.id,
                            onClick = { onChange(rule.copy(eqProfileId = profile.id)) },
                            label = { Text(profile.label) },
                        )
                    }
                }
                Text(
                    "Der eingebaute EQ wirkt global (Audio-Session 0) und wird nicht von jedem Gerät unterstützt.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { Button(onClick = onSave) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
