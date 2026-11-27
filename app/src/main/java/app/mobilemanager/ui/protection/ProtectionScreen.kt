package app.mobilemanager.ui.protection

import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.backup.EncryptedBackup
import app.mobilemanager.data.guard.GuardSettingsRepository
import app.mobilemanager.data.protection.DeviceOwnerController
import app.mobilemanager.data.protection.ProtectionRepository
import app.mobilemanager.data.protection.ProtectionSettings
import app.mobilemanager.data.protection.ProtectionWatchdog
import app.mobilemanager.data.protection.SensorEvent
import app.mobilemanager.data.protection.SensorLog
import app.mobilemanager.data.tracker.TrackerVpnState
import app.mobilemanager.ui.components.SecretField
import app.mobilemanager.ui.components.SectionCard
import app.mobilemanager.ui.external.ActionActivity
import app.mobilemanager.ui.guard.GuardSubScreen
import app.mobilemanager.ui.guard.SwitchRow
import app.mobilemanager.ui.guard.relativeTime
import app.mobilemanager.ui.guard.startActivitySafely
import app.mobilemanager.ui.theme.StatusColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeviceOwnerState(
    val owner: Boolean = false,
    val uninstallBlocked: Boolean = false,
    val restrictions: Map<String, Boolean> = emptyMap(),
    val vpnLocked: Boolean = false,
)

@HiltViewModel
class ProtectionViewModel @Inject constructor(
    private val repository: ProtectionRepository,
    private val watchdog: ProtectionWatchdog,
    private val deviceOwner: DeviceOwnerController,
    private val sensorLog: SensorLog,
    private val backup: EncryptedBackup,
    private val guard: GuardSettingsRepository,
) : ViewModel() {
    val settings: StateFlow<ProtectionSettings> = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProtectionSettings())
    val sensorEvents: StateFlow<List<SensorEvent>> = sensorLog.events
    private val _problems = MutableStateFlow<List<ProtectionWatchdog.Problem>>(emptyList())
    val problems: StateFlow<List<ProtectionWatchdog.Problem>> = _problems.asStateFlow()
    private val _owner = MutableStateFlow(DeviceOwnerState())
    val owner: StateFlow<DeviceOwnerState> = _owner.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _realtime = MutableStateFlow(false)
    val realtimeEnabled: StateFlow<Boolean> = _realtime.asStateFlow()
    val setupCommand: String get() = deviceOwner.setupCommand

    fun refresh() {
        viewModelScope.launch {
            _problems.value = watchdog.problems()
            _realtime.value = guard.current().realtimeServiceEnabled
        }
        _owner.value = DeviceOwnerState(
            owner = deviceOwner.isDeviceOwner(),
            uninstallBlocked = deviceOwner.isUninstallBlocked(),
            restrictions = DeviceOwnerController.RESTRICTIONS.associate { (key, _) -> key to deviceOwner.hasRestriction(key) },
            vpnLocked = deviceOwner.alwaysOnVpnLocked(),
        )
    }

    fun update(transform: (ProtectionSettings) -> ProtectionSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun setUninstallBlocked(v: Boolean) = report(deviceOwner.setUninstallBlocked(v))
    fun setRestriction(key: String, v: Boolean) = report(deviceOwner.setRestriction(key, v))
    fun setVpnLocked(v: Boolean) = report(deviceOwner.setAlwaysOnVpn(v))

    private fun report(result: Result<Unit>) {
        result.exceptionOrNull()?.let { _message.value = "Fehlgeschlagen: ${it.message}" }
        refresh()
    }

    fun clearSensorLog() = sensorLog.clear()

    fun export(uri: Uri, password: String) {
        viewModelScope.launch {
            _message.value = backup.export(uri, password).fold(
                { "Sicherung mit $it Dateien gespeichert." },
                { "Sicherung fehlgeschlagen: ${it.message}" },
            )
        }
    }

    fun import(uri: Uri, password: String, onReady: () -> Unit) {
        viewModelScope.launch {
            backup.prepareRestore(uri, password).fold(
                { onReady() },
                { _message.value = "Wiederherstellen fehlgeschlagen: ${it.message}" },
            )
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}

/** Phase 4.3/4.5/4.6: Selbstschutz, Datenschutz-Wächter, Device-Owner-Härtung, externe Steuerung und Backup. */
@Composable
fun ProtectionScreen(onBack: () -> Unit, onOpenTamperLog: () -> Unit, viewModel: ProtectionViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val problems by viewModel.problems.collectAsStateWithLifecycle()
    val owner by viewModel.owner.collectAsStateWithLifecycle()
    val sensorEvents by viewModel.sensorEvents.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val realtime by viewModel.realtimeEnabled.collectAsStateWithLifecycle()
    val alwaysOn by TrackerVpnState.alwaysOn.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    var passwordFor by remember { mutableStateOf<Pair<Boolean, Uri>?>(null) }
    var restartPending by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { passwordFor = true to it }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { passwordFor = false to it }
    }

    GuardSubScreen(title = "Selbstschutz & Datenschutz", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                SwitchRow(
                    title = "Watchdog",
                    subtitle = "Prüft alle 15 Minuten, ob App-Sperre, Bedienungshilfe, Benachrichtigungszugriff, Tracker-VPN und " +
                        "Echtzeit-Wächter noch laufen, und protokolliert jede Unterbrechung (Alarm über deinen Kanal).",
                    checked = s.watchdogEnabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(watchdogEnabled = v) } },
                )
                if (problems.isEmpty()) {
                    Text("Alle eingeschalteten Schutzfunktionen laufen.", color = StatusColors.Granted, style = MaterialTheme.typography.bodySmall)
                } else {
                    problems.forEach { Text("• ${it.text}", color = StatusColors.Denied, style = MaterialTheme.typography.bodySmall) }
                }
                TextButton(onClick = onOpenTamperLog) { Text("Manipulations-Protokoll öffnen") }
            }

            SectionCard {
                Text("Selbstschutz-Checkliste", style = MaterialTheme.typography.titleSmall)
                CheckLine(
                    ok = alwaysOn.first && alwaysOn.second,
                    text = "„Durchgehend aktives VPN“ + „Verbindungen ohne VPN blockieren“ für den Tracker-Blocker",
                ) { context.startActivitySafely(Intent(Settings.ACTION_VPN_SETTINGS)) }
                Text(
                    "Deinstallationsschutz: Ist der Geräteadministrator (Wächter → Security) aktiv, lässt sich MobileManager erst nach " +
                        "dem Deaktivieren deinstallieren. Mit „Systembildschirme mitsperren“ in der App-Sperre ist auch dieser Weg geschützt.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            SectionCard {
                Text("Datenschutz-Wächter", style = MaterialTheme.typography.titleSmall)
                if (s.needsRealtime && !realtime) {
                    Text(
                        "Sensor-Protokoll, Zwischenablage und USB-Warnung starten den Echtzeit-Wächter automatisch.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SwitchRow(
                    title = "Update- & Berechtigungs-Diff",
                    subtitle = "Meldet, wenn ein App-Update neue kritische Rechte verlangt (Hintergrund-Standort, Overlay, Bedienungshilfe …).",
                    checked = s.permissionDiffEnabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(permissionDiffEnabled = v) } },
                )
                SwitchRow(
                    title = "Sensoren-Protokoll",
                    subtitle = "Hält fest, wann Kamera oder Mikrofon belegt werden, welche App im Vordergrund war und ob das Display aus war.",
                    checked = s.sensorLogEnabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(sensorLogEnabled = v) } },
                )
                SwitchRow(
                    title = "Zwischenablage bei Display aus leeren",
                    subtitle = "Schützt kopierte Passwörter und TANs. Zusätzlich in der App-Sperre: Leeren nach Verlassen gesperrter Apps.",
                    checked = s.clipboardClearOnScreenOff,
                    onCheckedChange = { v -> viewModel.update { it.copy(clipboardClearOnScreenOff = v) } },
                )
                SwitchRow(
                    title = "USB-Daten-Schutz",
                    subtitle = "Warnt, wenn das Gerät an einem USB-Datenanschluss hängt, während USB-Debugging aktiv ist (Juice-Jacking).",
                    checked = s.usbWarning,
                    onCheckedChange = { v -> viewModel.update { it.copy(usbWarning = v) } },
                )
                TextButton(onClick = { context.startActivitySafely(Intent(Settings.ACTION_PRIVACY_SETTINGS)) }) {
                    Text("Android-Datenschutz (Kamera-/Mikrofon-Schalter)")
                }
            }

            if (s.sensorLogEnabled || sensorEvents.isNotEmpty()) {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sensor-Zugriffe", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::clearSensorLog, enabled = sensorEvents.isNotEmpty()) { Text("Leeren") }
                    }
                    if (sensorEvents.isEmpty()) Text("Noch keine Zugriffe aufgezeichnet.", style = MaterialTheme.typography.bodySmall)
                    sensorEvents.take(30).forEach { e ->
                        Text(
                            "${relativeTime(e.time)} · ${e.sensor}${e.app?.let { " · Vordergrund: $it" } ?: ""}${if (e.screenOff) " · DISPLAY AUS" else ""}",
                            color = if (e.screenOff) StatusColors.Denied else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            SectionCard {
                SwitchRow(
                    title = "Externe Steuerung erlauben",
                    subtitle = "Launcher-Shortcuts, Tasker und NFC-Tags dürfen Kernaktionen auslösen. Aus = jede andere App wird abgewiesen.",
                    checked = s.externalControl,
                    onCheckedChange = { v -> viewModel.update { it.copy(externalControl = v) } },
                )
                if (s.externalControl) {
                    Text("Intents (Activity app.mobilemanager/.ui.external.ActionActivity):", style = MaterialTheme.typography.labelLarge)
                    SelectionContainer {
                        Text(
                            ActionActivity.ACTIONS.joinToString("\n") { ActionActivity.PREFIX + it },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            SectionCard {
                Text("Verschlüsseltes Komplett-Backup (.mmbackup)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Alle Einstellungen, Regeln, Launcher-Layouts, Blocklisten und Protokolle – AES-256 mit deinem Passwort. " +
                        "Die Geheimnisse der App-Sperre bleiben gerätegebunden und müssen neu eingerichtet werden.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("MobileManager-${java.time.LocalDate.now()}.mmbackup") }) { Text("Sichern") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Wiederherstellen") }
                }
            }

            DeviceOwnerCard(owner, viewModel)
        }
    }

    passwordFor?.let { (isExport, uri) ->
        PasswordDialog(
            title = if (isExport) "Passwort für die Sicherung" else "Passwort der Sicherung",
            confirmRepeat = isExport,
            onDismiss = { passwordFor = null },
        ) { password ->
            passwordFor = null
            if (isExport) viewModel.export(uri, password) else viewModel.import(uri, password) { restartPending = true }
        }
    }
    if (restartPending) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Bereit zum Wiederherstellen") },
            text = { Text("MobileManager beendet sich jetzt und übernimmt die Sicherung beim nächsten Start.") },
            confirmButton = { TextButton(onClick = { Process.killProcess(Process.myPid()) }) { Text("Jetzt beenden") } },
        )
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            text = { Text(text) },
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
        )
    }
}

@Composable
private fun CheckLine(ok: Boolean, text: String, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "!", color = if (ok) StatusColors.Granted else StatusColors.Partial, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (!ok) TextButton(onClick = onFix) { Text("Öffnen") }
    }
}

@Composable
private fun DeviceOwnerCard(owner: DeviceOwnerState, viewModel: ProtectionViewModel) {
    SectionCard {
        Text("Maximal-Härtung (Device Owner)", style = MaterialTheme.typography.titleSmall)
        if (!owner.owner) {
            Text(
                "Optional per einmaligem ADB-Befehl. Voraussetzung: kein Konto (Google & Co.) auf dem Gerät angemeldet – " +
                    "Android lehnt es sonst ab. Achtung: Ohne Werksreset lässt sich der Device-Owner-Status danach nur schwer entfernen.",
                style = MaterialTheme.typography.bodySmall,
            )
            SelectionContainer {
                Text(viewModel.setupCommand, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            return@SectionCard
        }
        Text("MobileManager ist Device Owner.", color = StatusColors.Granted, style = MaterialTheme.typography.bodySmall)
        OwnerSwitch("Deinstallation von MobileManager blockieren", owner.uninstallBlocked, viewModel::setUninstallBlocked)
        OwnerSwitch("Tracker-VPN systemseitig erzwingen (Lockdown)", owner.vpnLocked, viewModel::setVpnLocked)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        DeviceOwnerController.RESTRICTIONS.forEach { (key, label) ->
            OwnerSwitch(label, owner.restrictions[key] == true) { viewModel.setRestriction(key, it) }
        }
    }
}

@Composable
private fun OwnerSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PasswordDialog(title: String, confirmRepeat: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val valid = password.length >= EncryptedBackup.MIN_PASSWORD && (!confirmRepeat || password == repeat)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecretField("Passwort (mind. ${EncryptedBackup.MIN_PASSWORD} Zeichen)", { password = it })
                if (confirmRepeat) SecretField("Wiederholen", { repeat = it })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }, enabled = valid) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
