package app.mobilemanager.ui.guard

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.FolderDelete
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.alerts.AlertConfigStore
import app.mobilemanager.data.battery.BatteryMonitor
import app.mobilemanager.data.guard.GuardSettingsRepository
import app.mobilemanager.data.notifications.NotificationRepository
import app.mobilemanager.data.security.DeviceAdminHelper
import app.mobilemanager.data.security.SecurityEventRepository
import app.mobilemanager.data.tracker.TrackerStatsRepository
import app.mobilemanager.data.tracker.TrackerVpnState
import app.mobilemanager.domain.model.AlertConfig
import app.mobilemanager.domain.model.BatteryStatus
import app.mobilemanager.domain.model.NightReport
import app.mobilemanager.domain.model.SecurityEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class GuardHubViewModel @Inject constructor(
    batteryMonitor: BatteryMonitor,
    guardSettings: GuardSettingsRepository,
    securityEvents: SecurityEventRepository,
    alertConfig: AlertConfigStore,
    private val deviceAdmin: DeviceAdminHelper,
    private val notificationRepository: NotificationRepository,
    trackerStats: TrackerStatsRepository,
) : ViewModel() {
    private fun <T> Flow<T>.state(initial: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val battery: StateFlow<BatteryStatus?> = batteryMonitor.observe().state(null)
    val nightReport: StateFlow<NightReport?> = guardSettings.nightReport.state(null)
    val events: StateFlow<List<SecurityEvent>> = securityEvents.events.state(emptyList())
    val unseen: StateFlow<Int> = securityEvents.unseenCount.state(0)
    val alert: StateFlow<AlertConfig> = alertConfig.config.state(AlertConfig())
    val expiredCount: StateFlow<Int?> = guardSettings.expiredFilesCount.state(null)
    val pendingDigest = notificationRepository.pendingDigest.state(emptyList())
    val trackerRunning: StateFlow<Boolean> = TrackerVpnState.running
    val trackerToday: StateFlow<Int> = trackerStats.blockedToday.state(0)

    private val _adminActive = MutableStateFlow(deviceAdmin.isActive())
    val adminActive: StateFlow<Boolean> = _adminActive.asStateFlow()
    private val _listenerEnabled = MutableStateFlow(notificationRepository.isListenerEnabled())
    val listenerEnabled: StateFlow<Boolean> = _listenerEnabled.asStateFlow()

    fun refresh() {
        _adminActive.value = deviceAdmin.isActive()
        _listenerEnabled.value = notificationRepository.isListenerEnabled()
    }
}

@Composable
fun GuardHubScreen(
    onOpenSecurity: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenAudit: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenExpiredReview: () -> Unit,
    onOpenTracker: () -> Unit,
    viewModel: GuardHubViewModel = hiltViewModel(),
) {
    val trackerRunning by viewModel.trackerRunning.collectAsStateWithLifecycle()
    val trackerToday by viewModel.trackerToday.collectAsStateWithLifecycle()
    val battery by viewModel.battery.collectAsStateWithLifecycle()
    val nightReport by viewModel.nightReport.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val unseen by viewModel.unseen.collectAsStateWithLifecycle()
    val alert by viewModel.alert.collectAsStateWithLifecycle()
    val expired by viewModel.expiredCount.collectAsStateWithLifecycle()
    val pendingDigest by viewModel.pendingDigest.collectAsStateWithLifecycle()
    val adminActive by viewModel.adminActive.collectAsStateWithLifecycle()
    val listenerEnabled by viewModel.listenerEnabled.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HubCard(
            icon = Icons.Default.GppMaybe,
            title = "Security & Diebstahl",
            headline = when {
                unseen > 0 -> "$unseen neue Ereignisse"
                events.isEmpty() -> "Keine Ereignisse"
                else -> "Letztes: ${events.first().title}"
            },
            detail = if (adminActive) "PIN-Überwachung aktiv" else "PIN-Überwachung aus – Geräteadministrator fehlt",
            warning = !adminActive || unseen > 0,
            onClick = onOpenSecurity,
        )
        HubCard(
            icon = Icons.Default.Campaign,
            title = "Alarm-Versand",
            headline = if (alert.isConfigured) alert.channel.label else "Nicht eingerichtet",
            detail = if (alert.isConfigured) "Alarme werden auch offline zuverlässig nachgesendet" else
                "E-Mail, Telegram oder ntfy einrichten, damit Alarme dich erreichen",
            warning = !alert.isConfigured,
            onClick = onOpenAlerts,
        )
        HubCard(
            icon = Icons.Default.BatteryChargingFull,
            title = "Akku & Temperatur",
            headline = battery?.let { "${it.levelPercent} % · ${"%.1f".format(it.temperatureCelsius)} °C" + if (it.isPlugged) " · lädt" else "" }
                ?: "Wird gelesen …",
            detail = nightReport?.let { "Letzte Nacht: −${it.dropPercent} % in ${"%.1f".format(it.hours)} h" }
                ?: "Noch kein Nacht-Bericht",
            warning = false,
            onClick = onOpenBattery,
        )
        HubCard(
            icon = Icons.Default.VpnLock,
            title = "Tracker-Blocker",
            headline = if (trackerRunning) "Aktiv · heute $trackerToday blockiert" else "Aus",
            detail = "Lokales VPN gegen Werbe- und Tracking-Domains, Rangliste pro App",
            warning = false,
            onClick = onOpenTracker,
        )
        HubCard(
            icon = Icons.Default.Policy,
            title = "Berechtigungs-Audit",
            headline = "Datenkraken aufspüren",
            detail = "Apps mit Kamera, Mikrofon, Standort & Co., die du kaum nutzt",
            warning = false,
            onClick = onOpenAudit,
        )
        HubCard(
            icon = Icons.Default.NotificationsActive,
            title = "Benachrichtigungen",
            headline = if (listenerEnabled) "Archiv aktiv" else "Benachrichtigungszugriff fehlt",
            detail = if (pendingDigest.isEmpty()) "7-Tage-Archiv & Digest" else "${pendingDigest.size} Meldungen im nächsten Digest",
            warning = !listenerEnabled,
            onClick = onOpenNotifications,
        )
        HubCard(
            icon = Icons.Default.FolderDelete,
            title = "Alte Screenshots & Downloads",
            headline = when (val count = expired) {
                null -> "Noch nicht geprüft"
                0 -> "Nichts Altes gefunden"
                else -> "$count alte Dateien"
            },
            detail = "Wöchentliche Prüfung · Tippen zum Durchsehen",
            warning = (expired ?: 0) > 0,
            onClick = onOpenExpiredReview,
        )
    }
}

@Composable
private fun HubCard(
    icon: ImageVector,
    title: String,
    headline: String,
    detail: String,
    warning: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (warning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(headline, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
