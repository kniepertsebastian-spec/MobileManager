package app.mobilemanager.ui.locker

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.nfc.NfcAdapter
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.apps.InstalledAppsRepository
import app.mobilemanager.data.bluetooth.BluetoothRepository
import app.mobilemanager.data.extras.AppSwitchAccessibilityService
import app.mobilemanager.data.locker.LockerController
import app.mobilemanager.data.locker.LockerRepository
import app.mobilemanager.data.locker.LockerSettings
import app.mobilemanager.data.permissions.PermissionChecker
import app.mobilemanager.domain.extras.LockerCrypto
import app.mobilemanager.domain.extras.LockerPolicy
import app.mobilemanager.domain.extras.SecondFactor
import app.mobilemanager.domain.model.LaunchableApp
import app.mobilemanager.domain.model.PairedDevice
import app.mobilemanager.domain.model.PermissionKind
import app.mobilemanager.ui.components.AppPickerDialog
import app.mobilemanager.ui.components.AppSelectionRow
import app.mobilemanager.ui.components.SecretField
import app.mobilemanager.ui.components.SectionCard
import app.mobilemanager.ui.guard.ChoiceChips
import app.mobilemanager.ui.guard.GuardSubScreen
import app.mobilemanager.ui.guard.SwitchRow
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

data class LockerEnvironment(
    val accessibility: Boolean = false,
    val usageAccess: Boolean = false,
    val overlay: Boolean = false,
    val nfcAvailable: Boolean = false,
    val unlocked: Boolean = false,
)

@HiltViewModel
class LockerViewModel @Inject constructor(
    private val application: android.app.Application,
    private val repository: LockerRepository,
    private val controller: LockerController,
    private val apps: InstalledAppsRepository,
    private val bluetooth: BluetoothRepository,
    private val permissions: PermissionChecker,
) : ViewModel() {
    /** `null` = noch nicht geladen. Sonst stünde kurz die ungeschützte Standard-Ansicht (Sperre „aus“) auf dem Schirm. */
    val loadedSettings: StateFlow<LockerSettings?> = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val settings: LockerSettings get() = loadedSettings.value ?: LockerSettings()
    private val _apps = MutableStateFlow<List<LaunchableApp>>(emptyList())
    val launchable: StateFlow<List<LaunchableApp>> = _apps.asStateFlow()
    private val _env = MutableStateFlow(LockerEnvironment())
    val environment: StateFlow<LockerEnvironment> = _env.asStateFlow()
    private val _paired = MutableStateFlow<List<PairedDevice>>(emptyList())
    val paired: StateFlow<List<PairedDevice>> = _paired.asStateFlow()
    private val _recovery = MutableStateFlow<List<String>?>(null)
    val recoveryCodes: StateFlow<List<String>?> = _recovery.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() {
        viewModelScope.launch { _apps.value = apps.getLaunchableApps() }
        _paired.value = bluetooth.pairedDevices()
        _env.value = LockerEnvironment(
            accessibility = AppSwitchAccessibilityService.isEnabled(application),
            usageAccess = permissions.hasUsageAccess(),
            overlay = Settings.canDrawOverlays(application),
            nfcAvailable = NfcAdapter.getDefaultAdapter(application) != null,
            unlocked = controller.isUnlocked(LockerController.SETTINGS_KEY),
        )
    }

    /** Schutz der Einstellungen: bei aktiver Sperre erst nach Entsperren änderbar. */
    fun needsAuth(s: LockerSettings) = s.enabled && !controller.isUnlocked(LockerController.SETTINGS_KEY)

    fun update(transform: (LockerSettings) -> LockerSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    /** Ausschalten mit Strict Mode: erst Wartezeit anfordern, dann innerhalb von 10 Minuten bestätigen. */
    fun setEnabled(enabled: Boolean) {
        val s = settings
        if (enabled) {
            // Wer gerade einschaltet, soll nicht sofort aus den Einstellungen ausgesperrt werden.
            controller.unlock(LockerController.SETTINGS_KEY)
            update { it.copy(enabled = true, disableRequestedAt = 0) }
            if (s.recoveryCodesLeft == 0) newRecoveryCodes()
            return
        }
        val now = System.currentTimeMillis()
        if (LockerPolicy.canDisable(s.cooldownMinutes, s.disableRequestedAt, now)) {
            update { it.copy(enabled = false, disableRequestedAt = 0) }
        } else if (s.disableRequestedAt == 0L || now > s.disableRequestedAt + (s.cooldownMinutes + 10) * 60_000L) {
            update { it.copy(disableRequestedAt = now) }
            _message.value = "Strict Mode: Ausschalten ist in ${s.cooldownMinutes} Minuten für 10 Minuten möglich."
        } else {
            _message.value = "Strict Mode: Wartezeit läuft noch."
        }
    }

    fun setPin(pin: String) {
        viewModelScope.launch {
            repository.setPin(pin)
            repository.update { it.copy(secondFactor = SecondFactor.PIN) }
            if (settings.recoveryCodesLeft == 0) newRecoveryCodes()
            _message.value = "App-PIN gespeichert"
        }
    }

    /** Neues TOTP-Geheimnis wird erst nach erfolgreicher Code-Bestätigung gespeichert. */
    fun confirmTotp(secret: ByteArray, code: String): Boolean {
        if (!LockerCrypto.verifyTotp(secret, code, System.currentTimeMillis())) return false
        viewModelScope.launch {
            repository.setTotpSecret(secret)
            repository.update { it.copy(secondFactor = SecondFactor.TOTP) }
            if (settings.recoveryCodesLeft == 0) newRecoveryCodes()
            _message.value = "Authenticator eingerichtet"
        }
        return true
    }

    fun saveNfc(tagId: ByteArray) {
        viewModelScope.launch {
            repository.setNfcTag(tagId)
            repository.update { it.copy(secondFactor = SecondFactor.NFC) }
            if (settings.recoveryCodesLeft == 0) newRecoveryCodes()
            _message.value = "NFC-Tag angelernt"
        }
    }

    fun newRecoveryCodes() {
        viewModelScope.launch { _recovery.value = repository.newRecoveryCodes() }
    }

    fun dismissRecovery() {
        _recovery.value = null
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun settingsIntent(kind: PermissionKind) = permissions.settingsIntent(kind)
}

/** Phase 4.2/4.3: Einstellungen der 2FA-App-Sperre. */
@Composable
fun LockerScreen(onBack: () -> Unit, viewModel: LockerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val loaded by viewModel.loadedSettings.collectAsStateWithLifecycle()
    val apps by viewModel.launchable.collectAsStateWithLifecycle()
    val env by viewModel.environment.collectAsStateWithLifecycle()
    val paired by viewModel.paired.collectAsStateWithLifecycle()
    val recovery by viewModel.recoveryCodes.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var setup by rememberSaveable { mutableStateOf<SecondFactor?>(null) }
    val s = loaded
    // Geschützt, solange nicht entsperrt – auch nach Process Death mit wiederhergestellten Dialog-Zuständen.
    val locked = s == null || (viewModel.needsAuth(s) && !env.unlocked)
    LaunchedEffect(locked) {
        if (locked) {
            showPicker = false
            setup = null
        }
    }

    GuardSubScreen(title = "App-Sperre (2FA)", onBack = onBack) { padding ->
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@GuardSubScreen
        }
        if (locked) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Die Einstellungen der App-Sperre sind geschützt.", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { context.startActivity(LockActivity.intent(context, LockerController.SETTINGS_KEY)) }) { Text("Entsperren") }
            }
            return@GuardSubScreen
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                SwitchRow(
                    title = "App-Sperre",
                    subtitle = "Gesperrte Apps verlangen die System-Biometrie und optional einen zweiten Faktor.",
                    checked = s.enabled,
                    onCheckedChange = viewModel::setEnabled,
                )
                if (s.cooldownMinutes > 0 && s.disableRequestedAt > 0) {
                    val readyAt = s.disableRequestedAt + s.cooldownMinutes * 60_000L
                    Text(
                        "Ausschalten angefordert – möglich ab ${android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(readyAt))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.Partial,
                    )
                }
                AppSelectionRow("Gesperrte Apps", s.packages.size, { showPicker = true })
                SwitchRow(
                    title = "Systembildschirme mitsperren",
                    subtitle = "Android-Einstellungen und Paket-Installer (App-Info, Bedienungshilfen, Deinstallieren) " +
                        "brauchen dann ebenfalls die Freigabe – Schutz gegen das Abschalten von MobileManager.",
                    checked = s.lockSystemScreens,
                    onCheckedChange = { v ->
                        if (v || LockerPolicy.canDisable(s.cooldownMinutes, s.disableRequestedAt, System.currentTimeMillis())) {
                            viewModel.update { it.copy(lockSystemScreens = v) }
                        } else {
                            viewModel.setEnabled(false)
                        }
                    },
                )
                SwitchRow(
                    title = "MobileManager mitsperren",
                    subtitle = "Einstellungen & Regeln nur nach Freigabe änderbar.",
                    checked = s.lockSelf,
                    onCheckedChange = { v -> viewModel.update { it.copy(lockSelf = v) } },
                )
            }

            DetectionCard(env, s.checkAllWindows) { kind -> context.startActivitySafely(viewModel.settingsIntent(kind)) }

            SectionCard {
                Text("Zweiter Faktor", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Faktor 1 ist immer die System-Biometrie (Fingerabdruck/Gesicht, mit Geräte-PIN als Ausweg). " +
                        "Zwei Biometrien getrennt zu erzwingen erlaubt Android nicht – daher Wissen oder Besitz als Faktor 2.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SecondFactor.entries.forEach { factor ->
                    val ready = when (factor) {
                        SecondFactor.NONE -> true
                        SecondFactor.PIN -> s.hasPin
                        SecondFactor.TOTP -> s.hasTotp
                        SecondFactor.NFC -> s.hasNfc
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(
                            selected = s.secondFactor == factor,
                            onClick = { if (ready) viewModel.update { it.copy(secondFactor = factor) } else setup = factor },
                            enabled = factor != SecondFactor.NFC || env.nfcAvailable,
                        )
                        Text(factor.label, Modifier.weight(1f))
                        if (factor != SecondFactor.NONE && (factor != SecondFactor.NFC || env.nfcAvailable)) {
                            TextButton(onClick = { setup = factor }) { Text(if (ready) "Neu einrichten" else "Einrichten") }
                        }
                    }
                }
                Text(
                    "Recovery-Codes übrig: ${s.recoveryCodesLeft}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.recoveryCodesLeft == 0 && s.secondFactor != SecondFactor.NONE) StatusColors.Denied else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = viewModel::newRecoveryCodes) { Text("Neue Recovery-Codes erzeugen") }
            }

            SectionCard {
                Text("Vertrauter Kontext", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Ist eines dieser Bluetooth-Geräte verbunden (z. B. deine Smartwatch), reicht Faktor 1. Unterwegs ohne Uhr " +
                        "wird automatisch der zweite Faktor verlangt.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (paired.isEmpty()) Text("Keine gekoppelten Geräte (oder Bluetooth-Berechtigung fehlt).", style = MaterialTheme.typography.bodySmall)
                paired.forEach { device ->
                    val checked = device.address in s.trustedBluetooth
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { v ->
                                viewModel.update { it.copy(trustedBluetooth = if (v) it.trustedBluetooth + device.address else it.trustedBluetooth - device.address) }
                            },
                        )
                        Text(device.name)
                    }
                }
            }

            SectionCard {
                Text("Freigabe gilt", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(
                    options = listOf(1 to "1 min", 5 to "5 min", 15 to "15 min", 30 to "30 min", 0 to "Bis Display aus"),
                    selected = s.unlockMinutes,
                    onSelect = { v -> viewModel.update { it.copy(unlockMinutes = v) } },
                )
                Text("Beim Ausschalten des Displays verfallen alle Freigaben.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SwitchRow(
                    title = "Benachrichtigungen gesperrter Apps ausblenden",
                    subtitle = "Inhalte erscheinen nicht in der Statusleiste, sondern nur im Archiv von MobileManager (Benachrichtigungszugriff nötig).",
                    checked = s.hideNotifications,
                    onCheckedChange = { v -> viewModel.update { it.copy(hideNotifications = v) } },
                )
                SwitchRow(
                    title = "Split-Screen & Pop-up-Fenster prüfen",
                    subtitle = "Prüft alle sichtbaren App-Fenster statt nur des Hauptfensters (nur mit Bedienungshilfe).",
                    checked = s.checkAllWindows,
                    onCheckedChange = { v -> viewModel.update { it.copy(checkAllWindows = v) } },
                    enabled = env.accessibility,
                )
                Text("Zwischenablage nach Verlassen einer gesperrten App leeren", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(
                    options = listOf(0 to "Nie", 30 to "30 s", 60 to "60 s"),
                    selected = s.clearClipboardSeconds,
                    onSelect = { v -> viewModel.update { it.copy(clearClipboardSeconds = v) } },
                )
            }

            SectionCard {
                Text("Strict Mode (gegen Impuls-Umgehung)", style = MaterialTheme.typography.titleSmall)
                Text("Ausschalten der Sperre erst nach einer Wartezeit.", style = MaterialTheme.typography.bodySmall)
                ChoiceChips(
                    options = listOf(0 to "Aus", 5 to "5 min", 15 to "15 min", 60 to "1 h"),
                    selected = s.cooldownMinutes,
                    onSelect = { v ->
                        // Verkürzen ist selbst eine Umgehung – nur erhöhen ist sofort möglich.
                        if (v >= s.cooldownMinutes || LockerPolicy.canDisable(s.cooldownMinutes, s.disableRequestedAt, System.currentTimeMillis())) {
                            viewModel.update { it.copy(cooldownMinutes = v) }
                        } else {
                            viewModel.setEnabled(false)
                        }
                    },
                )
                Text("Manipulations-Protokoll nach Fehlversuchen", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(
                    options = listOf(3 to "3", 5 to "5", 10 to "10", 0 to "Nie"),
                    selected = s.alertAfterFailures,
                    onSelect = { v -> viewModel.update { it.copy(alertAfterFailures = v) } },
                )
            }
        }
    }

    if (s == null || locked) return

    if (showPicker) {
        AppPickerDialog("Gesperrte Apps", apps, s.packages, onDismiss = { showPicker = false }) { chosen ->
            viewModel.update { it.copy(packages = chosen) }
            showPicker = false
        }
    }
    when (setup) {
        SecondFactor.PIN -> PinSetupDialog(onDismiss = { setup = null }) { pin ->
            viewModel.setPin(pin)
            setup = null
        }
        SecondFactor.TOTP -> TotpSetupDialog(onDismiss = { setup = null }, onConfirm = viewModel::confirmTotp) { setup = null }
        SecondFactor.NFC -> NfcSetupDialog(onDismiss = { setup = null }) { id ->
            viewModel.saveNfc(id)
            setup = null
        }
        else -> Unit
    }
    recovery?.let { codes ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Recovery-Codes") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Jeder Code ersetzt einmal den zweiten Faktor. Sicher notieren – sie werden nur jetzt angezeigt.")
                    SelectionContainer {
                        Text(codes.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissRecovery) { Text("Notiert") } },
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
private fun DetectionCard(env: LockerEnvironment, allWindows: Boolean, open: (PermissionKind) -> Unit) {
    SectionCard {
        Text("Erkennung", style = MaterialTheme.typography.titleSmall)
        val (text, color) = when {
            env.accessibility -> "Bedienungshilfe aktiv – Sperre greift sofort${if (allWindows) " inkl. Split-Screen" else ""}." to StatusColors.Granted
            env.usageAccess -> "Nur Nutzungsdaten – ca. 1 s Verzögerung. Für sofortiges Sperren die Bedienungshilfe aktivieren." to StatusColors.Partial
            else -> "Weder Bedienungshilfe noch Nutzungszugriff – die Sperre kann Apps nicht erkennen." to StatusColors.Denied
        }
        Text(text, color = color, style = MaterialTheme.typography.bodySmall)
        if (!env.overlay) {
            Text(
                "Ohne „Über anderen Apps einblenden“ blitzt die gesperrte App kurz auf, bevor der Sperrbildschirm erscheint.",
                color = StatusColors.Partial,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!env.accessibility) TextButton(onClick = { open(PermissionKind.APP_SWITCH_ACCESSIBILITY) }) { Text("Bedienungshilfe") }
            if (!env.overlay) TextButton(onClick = { open(PermissionKind.OVERLAY) }) { Text("Overlay erlauben") }
        }
    }
}

@Composable
private fun PinSetupDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val valid = pin.length >= 4 && pin == repeat
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App-PIN festlegen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mindestens 4 Ziffern, nicht die Geräte-PIN verwenden. Gespeichert wird nur ein gesalzener PBKDF2-Hash.")
                SecretField("PIN", { pin = it }, digitsOnly = true, maxLength = 12)
                SecretField("Wiederholen", { repeat = it }, digitsOnly = true, maxLength = 12)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(pin) }, enabled = valid) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun TotpSetupDialog(onDismiss: () -> Unit, onConfirm: (ByteArray, String) -> Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val secret = remember { LockerCrypto.newTotpSecret() }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Authenticator einrichten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("In Aegis, Google Authenticator & Co. „Schlüssel eingeben“ wählen und diesen Schlüssel eintragen (zeitbasiert, 6 Stellen):")
                SelectionContainer {
                    Text(LockerCrypto.base32(secret).chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = {
                    val clip = ClipData.newPlainText("otpauth", LockerCrypto.otpauthUri(secret)).apply {
                        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                    }
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                }) { Text("otpauth-Link kopieren") }
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it.filter(Char::isDigit).take(6)
                        error = false
                    },
                    label = { Text("Angezeigten Code eingeben") },
                    singleLine = true,
                    isError = error,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (error) Text("Code passt nicht – Uhrzeit prüfen und erneut versuchen.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (onConfirm(secret, code)) onDone() else error = true }, enabled = code.length == 6) { Text("Bestätigen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun NfcSetupDialog(onDismiss: () -> Unit, onTag: (ByteArray) -> Unit) {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val adapter = activity?.let { NfcAdapter.getDefaultAdapter(it) }
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        adapter?.enableReaderMode(activity, { tag -> activity.runOnUiThread { onTag(tag.id) } }, flags, null)
        onDispose { adapter?.disableReaderMode(activity) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NFC-Tag anlernen") },
        text = {
            Text(
                "Halte jetzt den NFC-Tag oder YubiKey an die Rückseite. Gespeichert wird nur ein Hash der Tag-Kennung. " +
                    "Tipp: einen zweiten Tag als Reserve nutzen geht nicht – dafür gibt es die Recovery-Codes.",
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
        modifier = Modifier.fillMaxWidth(),
    )
}
