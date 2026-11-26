package app.mobilemanager.ui.guard

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.apps.InstalledAppsRepository
import app.mobilemanager.data.guard.GuardSettings
import app.mobilemanager.data.local.TrackerCount
import app.mobilemanager.data.tracker.TrackerStatsRepository
import app.mobilemanager.data.tracker.TrackerVpnState
import app.mobilemanager.ui.extras.AppCountRow
import app.mobilemanager.data.guard.GuardSettingsRepository
import app.mobilemanager.data.security.DeviceAdminHelper
import app.mobilemanager.data.security.LocationHelper
import app.mobilemanager.data.security.SecurityEventRepository
import app.mobilemanager.data.security.SimMonitor
import app.mobilemanager.domain.guard.SimSnapshot
import app.mobilemanager.domain.model.AlertState
import app.mobilemanager.domain.model.SecurityEvent
import app.mobilemanager.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class SecurityUiState(
    val adminActive: Boolean = false,
    val currentSim: String = "",
    val hasLocation: Boolean = false,
    val hasBackgroundLocation: Boolean = false,
)

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val guardSettings: GuardSettingsRepository,
    private val securityEvents: SecurityEventRepository,
    private val deviceAdmin: DeviceAdminHelper,
    private val simMonitor: SimMonitor,
    private val location: LocationHelper,
    trackerStats: TrackerStatsRepository,
    private val apps: InstalledAppsRepository,
) : ViewModel() {

    /** Meilenstein 3.3.3: Rangliste der Apps mit den meisten blockierten Tracker-Anfragen heute. */
    val trackerTop: StateFlow<List<TrackerCount>> =
        trackerStats.topAppsToday(5).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trackerRunning: StateFlow<Boolean> = TrackerVpnState.running

    fun appLabel(packageName: String): String =
        if (packageName == TrackerStatsRepository.UNKNOWN_APP) "System / nicht zuordenbar" else apps.labelOf(packageName)

    val settings: StateFlow<GuardSettings> =
        guardSettings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuardSettings())
    val events: StateFlow<List<SecurityEvent>> =
        securityEvents.events.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val simBaseline: StateFlow<String?> = guardSettings.simBaselineFlow
        .map { SimSnapshot.decode(it)?.let(simMonitor::describe) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _state = MutableStateFlow(SecurityUiState())
    val state: StateFlow<SecurityUiState> = _state.asStateFlow()

    val activationIntent: Intent get() = deviceAdmin.activationIntent()

    fun refresh() {
        _state.value = SecurityUiState(
            adminActive = deviceAdmin.isActive(),
            currentSim = simMonitor.describe(simMonitor.snapshot()),
            hasLocation = location.hasPermission(),
            hasBackgroundLocation = location.hasBackgroundPermission(),
        )
        viewModelScope.launch { securityEvents.markAllSeen() }
    }

    fun deactivateAdmin() {
        deviceAdmin.deactivate()
        // Android entfernt den Admin asynchron.
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            refresh()
        }
    }

    fun setThreshold(value: Int) = update { it.copy(failedUnlockThreshold = value) }
    fun setSideloadWatch(enabled: Boolean) = update { it.copy(sideloadWatchEnabled = enabled) }
    fun setAttachLocation(enabled: Boolean) = update { it.copy(attachLocation = enabled) }

    fun setSimWatch(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) simMonitor.adoptCurrentAsBaseline()
            guardSettings.update { it.copy(simWatchEnabled = enabled) }
        }
    }

    fun adoptSim() {
        viewModelScope.launch { simMonitor.adoptCurrentAsBaseline() }
    }

    fun clearLog() {
        viewModelScope.launch { securityEvents.clear() }
    }

    private fun update(transform: (GuardSettings) -> GuardSettings) {
        viewModelScope.launch { guardSettings.update(transform) }
    }
}

@Composable
fun SecurityScreen(
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenTracker: () -> Unit,
    viewModel: SecurityViewModel = hiltViewModel(),
) {
    val trackerTop by viewModel.trackerTop.collectAsStateWithLifecycle()
    val trackerRunning by viewModel.trackerRunning.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val baseline by viewModel.simBaseline.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    GuardSubScreen(
        title = "Security & Diebstahl",
        onBack = onBack,
        actions = {
            IconButton(onClick = { confirmClear = true }, enabled = events.isNotEmpty()) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Protokoll leeren")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DeviceAdminCard(
                    active = state.adminActive,
                    onActivate = { context.startActivitySafely(viewModel.activationIntent) },
                    onDeactivate = viewModel::deactivateAdmin,
                )
            }
            item {
                SectionCard {
                    Text("Falsche Entsperrversuche", style = MaterialTheme.typography.titleMedium)
                    Text("Alarm nach so vielen Fehlversuchen in Folge:", style = MaterialTheme.typography.bodySmall)
                    ChoiceChips(
                        options = listOf(2 to "2×", 3 to "3×", 5 to "5×", 10 to "10×"),
                        selected = settings.failedUnlockThreshold,
                        onSelect = viewModel::setThreshold,
                        enabled = state.adminActive,
                    )
                    SwitchRow(
                        title = "Standort anhängen",
                        subtitle = when {
                            !state.hasLocation -> "Standort-Berechtigung fehlt"
                            !state.hasBackgroundLocation -> "Für Alarme im Hintergrund „Immer zulassen“ wählen"
                            else -> "Google-Maps-Link zum letzten bekannten Standort"
                        },
                        checked = settings.attachLocation,
                        onCheckedChange = viewModel::setAttachLocation,
                    )
                    if (settings.attachLocation && !state.hasBackgroundLocation) {
                        TextButton(onClick = onOpenPermissions) { Text("Standort erlauben") }
                    }
                }
            }
            item {
                SectionCard {
                    Text("Tracker heute", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (trackerRunning) "Apps mit den meisten blockierten Tracker-Anfragen" else "Tracker-Blocker ist aus",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    trackerTop.forEach { entry -> AppCountRow(entry, viewModel.appLabel(entry.name)) }
                    TextButton(onClick = onOpenTracker) { Text(if (trackerRunning) "Details" else "Tracker-Blocker einrichten") }
                }
            }
            item {
                SectionCard {
                    SwitchRow(
                        title = "Sideloading erkennen",
                        subtitle = "Warnt bei neuen Apps, die nicht aus dem Google Play Store stammen.",
                        checked = settings.sideloadWatchEnabled,
                        onCheckedChange = viewModel::setSideloadWatch,
                    )
                    SwitchRow(
                        title = "SIM-Wechsel erkennen",
                        subtitle = "Alarm, wenn die SIM entfernt oder getauscht wird. Jetzt: ${state.currentSim}",
                        checked = settings.simWatchEnabled,
                        onCheckedChange = viewModel::setSimWatch,
                    )
                    if (settings.simWatchEnabled) {
                        Text(
                            "Als normal gespeichert: ${baseline ?: "–"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = viewModel::adoptSim) { Text("Aktuellen Zustand als normal übernehmen") }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Security-Log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenAlerts) { Text("Alarm-Versand") }
                }
            }
            if (events.isEmpty()) {
                item { Text("Noch keine Sicherheitsereignisse.", style = MaterialTheme.typography.bodyMedium) }
            }
            items(events, key = { it.id }) { event -> EventCard(event, onOpenMap = { link ->
                context.startActivitySafely(Intent(Intent.ACTION_VIEW, link.toUri()))
            }) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Protokoll leeren?") },
            text = { Text("Alle ${events.size} Einträge werden endgültig gelöscht.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.clearLog()
                    confirmClear = false
                }) { Text("Leeren") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun DeviceAdminCard(active: Boolean, onActivate: () -> Unit, onDeactivate: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (active) "Geräteadministrator aktiv" else "Geräteadministrator nicht aktiv",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "MobileManager nutzt als Geräteadministrator nur das Recht, falsche Entsperrversuche zu zählen. " +
                    "Android zählt PIN, Muster und Passwort – Fingerabdruck-Fehlversuche nicht.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Wichtig: Solange der Geräteadministrator aktiv ist, lässt sich MobileManager nicht deinstallieren. " +
                    "Deaktiviere ihn vorher hier (oder unter Einstellungen → Sicherheit → Apps zur Geräteverwaltung).",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (active) {
                OutlinedButton(onClick = onDeactivate, modifier = Modifier.padding(top = 8.dp)) { Text("Deaktivieren") }
            } else {
                Button(onClick = onActivate, modifier = Modifier.padding(top = 8.dp)) { Text("Aktivieren") }
            }
        }
    }
}

@Composable
private fun EventCard(event: SecurityEvent, onOpenMap: (String) -> Unit) {
    SectionCard {
        Text(event.title, style = MaterialTheme.typography.titleSmall)
        Text(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(event.timestamp)) +
                " · " + event.type.label,
            style = MaterialTheme.typography.labelMedium,
        )
        if (event.details.isNotBlank()) Text(event.details, style = MaterialTheme.typography.bodySmall)
        Text(
            event.alertState.label,
            style = MaterialTheme.typography.labelMedium,
            color = when (event.alertState) {
                AlertState.SENT -> MaterialTheme.colorScheme.primary
                AlertState.FAILED, AlertState.NOT_CONFIGURED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 4.dp),
        )
        event.mapsLink?.let { link -> TextButton(onClick = { onOpenMap(link) }) { Text("Standort in Karten öffnen") } }
    }
}
