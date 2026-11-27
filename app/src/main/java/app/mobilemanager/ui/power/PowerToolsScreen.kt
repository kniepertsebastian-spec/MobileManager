package app.mobilemanager.ui.power

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.apps.InstalledAppsRepository
import app.mobilemanager.data.power.CaffeineService
import app.mobilemanager.data.power.ManagedApp
import app.mobilemanager.data.power.PowerTools
import app.mobilemanager.data.power.TempPermissionGroup
import app.mobilemanager.data.protection.ProtectionRepository
import app.mobilemanager.data.protection.ProtectionSettings
import app.mobilemanager.data.shizuku.ShizukuManager
import app.mobilemanager.data.shizuku.ShizukuState
import app.mobilemanager.domain.model.LaunchableApp
import app.mobilemanager.ui.components.AppIcon
import app.mobilemanager.ui.components.SectionCard
import app.mobilemanager.ui.components.ShizukuRequirement
import app.mobilemanager.ui.guard.ChoiceChips
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

@HiltViewModel
class PowerToolsViewModel @Inject constructor(
    private val tools: PowerTools,
    private val shizuku: ShizukuManager,
    private val apps: InstalledAppsRepository,
    private val protection: ProtectionRepository,
) : ViewModel() {
    val shizukuState: StateFlow<ShizukuState> = shizuku.state
    val protectionSettings: StateFlow<ProtectionSettings> =
        protection.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProtectionSettings())
    private val _launchable = MutableStateFlow<List<LaunchableApp>>(emptyList())
    val launchable: StateFlow<List<LaunchableApp>> = _launchable.asStateFlow()
    private val _systemApps = MutableStateFlow<List<ManagedApp>?>(null)
    val systemApps: StateFlow<List<ManagedApp>?> = _systemApps.asStateFlow()
    private val _animation = MutableStateFlow<String?>(null)
    val animation: StateFlow<String?> = _animation.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() {
        shizuku.refresh()
        viewModelScope.launch {
            _launchable.value = apps.getLaunchableApps()
            if (shizuku.isReady) _animation.value = tools.animationScale()
        }
    }

    fun requestShizuku() = shizuku.requestPermission()
    fun shizukuIntent() = shizuku.launchShizukuAppIntent()

    fun loadSystemApps() {
        viewModelScope.launch { _systemApps.value = tools.systemApps() }
    }

    fun setAppEnabled(app: ManagedApp, enabled: Boolean) {
        viewModelScope.launch {
            val result = tools.setAppEnabled(app, enabled)
            if (!result.ok) _message.value = result.error
            _systemApps.value = tools.systemApps()
        }
    }

    suspend fun appOpMode(pkg: String, op: String) = tools.appOpMode(pkg, op)

    fun setAppOp(pkg: String, op: String, mode: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val result = tools.setAppOp(pkg, op, mode)
            if (!result.ok) _message.value = result.error
            onDone()
        }
    }

    fun grantTemporarily(pkg: String, group: TempPermissionGroup, minutes: Int) {
        viewModelScope.launch {
            _message.value = tools.grantTemporarily(pkg, group, minutes)
                ?: "${group.label} für ${tools.label(pkg)} freigegeben – wird nach $minutes Minuten automatisch entzogen."
        }
    }

    fun setAnimation(value: String) {
        viewModelScope.launch {
            tools.setAnimationScale(value)?.let { _message.value = it }
            _animation.value = tools.animationScale()
        }
    }

    fun setNightMode(mode: String) {
        viewModelScope.launch { tools.setNightMode(mode)?.let { _message.value = it } }
    }

    fun trimCaches() {
        viewModelScope.launch { _message.value = tools.trimCaches() ?: "App-Caches wurden geleert." }
    }

    fun setCaffeineMinutes(minutes: Int) {
        viewModelScope.launch { protection.update { it.copy(caffeineMinutes = minutes) } }
    }

    fun dismissMessage() {
        _message.value = null
    }
}

private val APP_OPS = listOf(
    "RUN_ANY_IN_BACKGROUND" to "Im Hintergrund laufen",
    "WAKE_LOCK" to "Gerät wach halten",
    "READ_CLIPBOARD" to "Zwischenablage lesen",
    "VIBRATE" to "Vibrieren",
)

/** Phase 4.4: Shizuku-Power-Tools und Koffein-Modus. */
@Composable
fun PowerToolsScreen(onBack: () -> Unit, viewModel: PowerToolsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val shizuku by viewModel.shizukuState.collectAsStateWithLifecycle()
    val apps by viewModel.launchable.collectAsStateWithLifecycle()
    val animation by viewModel.animation.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val protection by viewModel.protectionSettings.collectAsStateWithLifecycle()
    val caffeineUntil by CaffeineService.until.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    var appOpsFor by remember { mutableStateOf<LaunchableApp?>(null) }
    var pickFor by remember { mutableStateOf<PickPurpose?>(null) }
    var tempApp by remember { mutableStateOf<LaunchableApp?>(null) }
    var showBloat by remember { mutableStateOf(false) }
    val ready = shizuku == ShizukuState.READY

    GuardSubScreen(title = "Power-Tools", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShizukuRequirement(shizuku, viewModel::requestShizuku) { context.startActivitySafely(viewModel.shizukuIntent()) }
            if (shizuku == ShizukuState.NOT_RUNNING || shizuku == ShizukuState.NOT_INSTALLED) {
                SectionCard {
                    Text("Shizuku nach einem Neustart starten", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "1. Mit einem WLAN verbinden  2. Entwickleroptionen → „Kabelloses Debugging“ einschalten  " +
                            "3. In der Shizuku-App „Starten“ tippen. Danach läuft Shizuku bis zum nächsten Neustart.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { context.startActivitySafely(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }) {
                        Text("Entwickleroptionen öffnen")
                    }
                }
            }

            SectionCard {
                Text("Koffein-Modus", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Hält das Display an, ohne den Bildschirm-Timeout zu ändern. Auch als Schnelleinstellungs-Kachel „Koffein“.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ChoiceChips(
                    options = listOf(15 to "15 min", 30 to "30 min", 60 to "60 min"),
                    selected = protection.caffeineMinutes,
                    onSelect = viewModel::setCaffeineMinutes,
                )
                val active = caffeineUntil > System.currentTimeMillis()
                Button(onClick = {
                    if (active) CaffeineService.stop(context) else if (!CaffeineService.start(context, protection.caffeineMinutes)) {
                        context.startActivitySafely(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.fromParts("package", context.packageName, null)))
                    }
                }) { Text(if (active) "Beenden" else "Starten") }
            }

            SectionCard {
                Text("App-Ops (versteckte Rechte)", style = MaterialTheme.typography.titleSmall)
                Text("Hintergrundausführung, Wakelocks, Zwischenablage und Vibration pro App erlauben oder verbieten.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { pickFor = PickPurpose.APP_OPS }, enabled = ready) { Text("App wählen") }
            }

            SectionCard {
                Text("Rechte auf Zeit", style = MaterialTheme.typography.titleSmall)
                Text("Standort, Kamera oder Mikrofon für 15–60 Minuten freigeben – danach entzieht MobileManager sie automatisch.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { pickFor = PickPurpose.TEMP }, enabled = ready) { Text("App wählen") }
            }

            SectionCard {
                Text("Bloatware-Manager", style = MaterialTheme.typography.titleSmall)
                Text(
                    "System-Apps für deinen Nutzer deaktivieren und jederzeit wieder aktivieren (keine Deinstallation). " +
                        "Systemkritische Apps sind gesperrt. APK-Export findest du unter „APK-Backup“.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = {
                    viewModel.loadSystemApps()
                    showBloat = true
                }, enabled = ready) { Text("System-Apps anzeigen") }
            }

            SectionCard {
                Text("System-Tweaks", style = MaterialTheme.typography.titleSmall)
                Text("Animationen", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(
                    options = listOf("1" to "Normal", "0.5" to "0,5×", "0" to "Aus"),
                    selected = animation ?: "",
                    onSelect = viewModel::setAnimation,
                    enabled = ready,
                )
                Text("Dunkles Design", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("yes" to "Dunkel", "no" to "Hell", "auto" to "Automatisch").forEach { (mode, label) ->
                        OutlinedButton(onClick = { viewModel.setNightMode(mode) }, enabled = ready) { Text(label) }
                    }
                }
                OutlinedButton(onClick = viewModel::trimCaches, enabled = ready, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Alle App-Caches leeren")
                }
            }
        }
    }

    pickFor?.let { purpose ->
        SingleAppPicker(apps, onDismiss = { pickFor = null }) { app ->
            pickFor = null
            if (purpose == PickPurpose.APP_OPS) appOpsFor = app else tempApp = app
        }
    }
    appOpsFor?.let { app -> AppOpsDialog(app, viewModel) { appOpsFor = null } }
    tempApp?.let { app -> TempPermissionDialog(app, onDismiss = { tempApp = null }) { group, minutes ->
        viewModel.grantTemporarily(app.packageName, group, minutes)
        tempApp = null
    } }
    if (showBloat) BloatwareDialog(viewModel) { showBloat = false }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            text = { Text(text) },
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
        )
    }
}

private enum class PickPurpose { APP_OPS, TEMP }

@Composable
private fun SingleAppPicker(apps: List<LaunchableApp>, onDismiss: () -> Unit, onPick: (LaunchableApp) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) { apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("App suchen") },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(filtered, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.packageName, size = 36.dp)
                            Text(app.label, Modifier.padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Abbrechen") }
            }
        }
    }
}

@Composable
private fun AppOpsDialog(app: LaunchableApp, viewModel: PowerToolsViewModel, onDismiss: () -> Unit) {
    val modes = remember { mutableStateMapOf<String, String?>() }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(app.packageName, reload) {
        APP_OPS.forEach { (op, _) -> modes[op] = viewModel.appOpMode(app.packageName, op) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label) },
        text = {
            Column {
                APP_OPS.forEach { (op, label) ->
                    val mode = modes[op]
                    val allowed = mode == null || mode == "allow" || mode == "default" || mode == "foreground"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label)
                            Text(mode ?: "…", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = allowed, onCheckedChange = { on ->
                            viewModel.setAppOp(app.packageName, op, if (on) "allow" else "ignore") { reload++ }
                        })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } },
    )
}

@Composable
private fun TempPermissionDialog(app: LaunchableApp, onDismiss: () -> Unit, onGrant: (TempPermissionGroup, Int) -> Unit) {
    var group by remember { mutableStateOf(TempPermissionGroup.LOCATION) }
    var minutes by remember { mutableStateOf(15) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${app.label}: Rechte auf Zeit") },
        text = {
            Column {
                ChoiceChips(TempPermissionGroup.entries.map { it to it.label }, group, { group = it })
                ChoiceChips(listOf(15 to "15 min", 30 to "30 min", 60 to "60 min"), minutes, { minutes = it })
            }
        },
        confirmButton = { TextButton(onClick = { onGrant(group, minutes) }) { Text("Freigeben") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun BloatwareDialog(viewModel: PowerToolsViewModel, onDismiss: () -> Unit) {
    val apps by viewModel.systemApps.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("System-Apps", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Suchen") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = apps?.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
                if (list == null) Text("Lade …")
                LazyColumn(Modifier.weight(1f)) {
                    items(list.orEmpty(), key = { it.packageName }) { app ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    app.packageName + if (app.protected) " · geschützt" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Switch(
                                checked = app.enabled,
                                onCheckedChange = { viewModel.setAppEnabled(app, it) },
                                enabled = !app.protected || !app.enabled,
                            )
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Schließen") }
            }
        }
    }
}
