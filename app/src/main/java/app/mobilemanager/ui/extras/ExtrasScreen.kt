package app.mobilemanager.ui.extras

import android.app.Activity
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hardware
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.extras.ExtrasSettings
import app.mobilemanager.data.extras.ExtrasSettingsRepository
import app.mobilemanager.data.tracker.TrackerSettings
import app.mobilemanager.data.tracker.TrackerSettingsRepository
import app.mobilemanager.data.tracker.TrackerStatsRepository
import app.mobilemanager.data.tracker.TrackerVpnController
import app.mobilemanager.data.tracker.TrackerVpnState
import app.mobilemanager.launcher.LauncherComponents
import app.mobilemanager.ui.guard.GuardSubScreen
import app.mobilemanager.ui.guard.startActivitySafely
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LauncherState(val enabled: Boolean = false, val isDefault: Boolean = false)

@HiltViewModel
class ExtrasViewModel @Inject constructor(
    private val application: Application,
    private val extras: ExtrasSettingsRepository,
    private val tracker: TrackerSettingsRepository,
    private val vpn: TrackerVpnController,
    stats: TrackerStatsRepository,
) : AndroidViewModel(application) {

    val settings: StateFlow<ExtrasSettings> = extras.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExtrasSettings())
    val trackerSettings: StateFlow<TrackerSettings> = tracker.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackerSettings())
    val trackerRunning: StateFlow<Boolean> = TrackerVpnState.running
    val blockedToday: StateFlow<Int> = stats.blockedToday.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _launcher = MutableStateFlow(LauncherState())
    val launcher: StateFlow<LauncherState> = _launcher.asStateFlow()

    fun refresh() {
        _launcher.value = LauncherState(LauncherComponents.isEnabled(application), LauncherComponents.isDefaultHome(application))
    }

    fun setLauncherEnabled(enabled: Boolean) {
        LauncherComponents.setEnabled(application, enabled)
        refresh()
    }

    fun update(transform: (ExtrasSettings) -> ExtrasSettings) {
        viewModelScope.launch { extras.update(transform) }
    }

    fun vpnConsentIntent() = vpn.prepareIntent()

    fun otherVpnActive() = vpn.otherVpnActive()

    fun setTrackerEnabled(enabled: Boolean) {
        viewModelScope.launch { tracker.setEnabled(enabled) }
    }
}

/**
 * Schaltet den Tracker-Blocker ein: holt bei Bedarf die einmalige VPN-Zustimmung des Systems.
 * @return Funktion, die den Wunschzustand setzt; [onBlocked] meldet ein bereits aktives fremdes VPN.
 */
@Composable
fun rememberTrackerToggle(viewModel: ExtrasViewModel, onBlocked: () -> Unit): (Boolean) -> Unit {
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.setTrackerEnabled(true)
    }
    return remember(viewModel) {
        { enabled ->
            when {
                !enabled -> viewModel.setTrackerEnabled(false)
                viewModel.otherVpnActive() -> onBlocked()
                else -> viewModel.vpnConsentIntent()?.let(consent::launch) ?: viewModel.setTrackerEnabled(true)
            }
        }
    }
}

@Composable
fun ExtrasScreen(
    onBack: () -> Unit,
    onOpenTracker: () -> Unit,
    onOpenFocus: () -> Unit,
    onOpenVolume: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenLocker: () -> Unit,
    onOpenProtection: () -> Unit,
    onOpenPowerTools: () -> Unit,
    viewModel: ExtrasViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val tracker by viewModel.trackerSettings.collectAsStateWithLifecycle()
    val running by viewModel.trackerRunning.collectAsStateWithLifecycle()
    val blockedToday by viewModel.blockedToday.collectAsStateWithLifecycle()
    val launcher by viewModel.launcher.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val toggleTracker = rememberTrackerToggle(viewModel, onBlocked = onOpenTracker)

    GuardSubScreen(title = "Launcher & Extras", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Jedes Modul lässt sich einzeln ein- und ausschalten. Ausgeschaltet läuft nichts im Hintergrund.",
                style = MaterialTheme.typography.bodySmall,
            )
            ExtraCard(
                icon = Icons.Default.Home,
                title = "Eigener Launcher",
                status = when {
                    !launcher.enabled -> "Aus"
                    launcher.isDefault -> "Aktiv als Startbildschirm"
                    else -> "Bereit – noch nicht als Standard festgelegt"
                },
                detail = "Homescreen mit Ordnen-Funktion und Profilen (Arbeit, Auto, Fokus)",
                checked = launcher.enabled,
                onCheckedChange = viewModel::setLauncherEnabled,
                onClick = if (launcher.enabled) {
                    { context.startActivitySafely(LauncherComponents.settingsIntent(context)) }
                } else null,
            )
            NavCard(Icons.Default.Lock, "App-Sperre (2FA)", "Biometrie plus PIN, Einmalcode oder NFC-Tag – auch für Einstellungen & Installer", onOpenLocker)
            NavCard(Icons.Default.Router, "Netzwerk-Wächter", "Firewall pro App, Screen-Off-Kill-Switch, Privates DNS je Netz, WLAN-Warnung, Datenlimit", onOpenNetwork)
            NavCard(Icons.Default.Shield, "Selbstschutz & Datenschutz", "Watchdog, Berechtigungs-Diff, Sensor-Protokoll, Device Owner, Backup, externe Steuerung", onOpenProtection)
            NavCard(Icons.Default.Build, "Power-Tools", "App-Ops, Bloatware, Rechte auf Zeit, Animationen, Koffein-Modus (Shizuku)", onOpenPowerTools)
            ExtraCard(
                icon = Icons.Default.VpnLock,
                title = "Tracker-Blocker",
                status = when {
                    running -> "Aktiv · heute $blockedToday blockiert"
                    tracker.enabled -> "Wird gestartet …"
                    else -> "Aus"
                },
                detail = "Lokales VPN filtert Werbe- und Tracking-Domains – ohne fremden Server",
                checked = tracker.enabled,
                onCheckedChange = toggleTracker,
                onClick = onOpenTracker,
            )
            ExtraCard(
                icon = Icons.Default.HourglassTop,
                title = "Fokus-Bremse",
                status = if (settings.focusEnabled) "${settings.focusPackages.size} Apps gebremst" else "Aus",
                detail = "Atempause mit Countdown vor Zeitfressern wie Instagram oder TikTok",
                checked = settings.focusEnabled,
                onCheckedChange = { v -> viewModel.update { it.copy(focusEnabled = v) } },
                onClick = onOpenFocus,
            )
            ExtraCard(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                title = "Lautstärke pro App",
                status = if (settings.volumeEnabled) "Aktiv" else "Aus",
                detail = "Eigene Medienlautstärke je App, danach zurück zum vorherigen Wert",
                checked = settings.volumeEnabled,
                onCheckedChange = { v -> viewModel.update { it.copy(volumeEnabled = v) } },
                onClick = onOpenVolume,
            )
            ExtraCard(
                icon = Icons.Default.Hardware,
                title = "Hardware-Schnellcheck",
                status = if (settings.diagnosticsEnabled) "Lautsprecher-Reiniger & Display-Test" else "Ausgeblendet",
                detail = "Läuft nur, solange du ihn geöffnet hast",
                checked = settings.diagnosticsEnabled,
                onCheckedChange = { v -> viewModel.update { it.copy(diagnosticsEnabled = v) } },
                onClick = if (settings.diagnosticsEnabled) onOpenDiagnostics else null,
            )
            ExtraCard(
                icon = Icons.Default.Archive,
                title = "APK-Backup",
                status = if (settings.apkBackupEnabled) "Documents/${app.mobilemanager.data.extras.ApkBackupRepository.BACKUP_DIR}" else "Ausgeblendet",
                detail = "Sichert installierte Apps als APK – Rettung bei schlechten Updates",
                checked = settings.apkBackupEnabled,
                onCheckedChange = { v -> viewModel.update { it.copy(apkBackupEnabled = v) } },
                onClick = if (settings.apkBackupEnabled) onOpenBackup else null,
            )
        }
    }
}

/** Phase 4: Modul mit eigener Seite (Schalter dort, damit Sperre & Strict Mode nicht umgangen werden). */
@Composable
private fun NavCard(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun ExtraCard(
    icon: ImageVector,
    title: String,
    status: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
            if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
