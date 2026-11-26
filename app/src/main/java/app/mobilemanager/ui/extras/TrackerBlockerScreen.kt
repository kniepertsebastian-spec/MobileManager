package app.mobilemanager.ui.extras

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.apps.InstalledAppsRepository
import app.mobilemanager.data.local.TrackerCount
import app.mobilemanager.data.tracker.TrackerBlocklistRepository
import app.mobilemanager.data.tracker.TrackerSettings
import app.mobilemanager.data.tracker.TrackerSettingsRepository
import app.mobilemanager.data.tracker.TrackerStatsRepository
import app.mobilemanager.data.tracker.TrackerVpnController
import app.mobilemanager.data.tracker.TrackerVpnState
import app.mobilemanager.domain.extras.HostsParser
import app.mobilemanager.ui.components.AppIcon
import app.mobilemanager.ui.components.SectionCard
import app.mobilemanager.ui.guard.ChoiceChips
import app.mobilemanager.ui.guard.GuardSubScreen
import app.mobilemanager.ui.guard.SwitchRow
import app.mobilemanager.ui.theme.StatusColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VpnEnvironment(val otherVpn: Boolean = false, val privateDns: String? = null)

@HiltViewModel
class TrackerViewModel @Inject constructor(
    private val settingsRepository: TrackerSettingsRepository,
    private val blocklist: TrackerBlocklistRepository,
    private val stats: TrackerStatsRepository,
    private val vpn: TrackerVpnController,
    private val apps: InstalledAppsRepository,
) : ViewModel() {
    private fun <T> Flow<T>.state(initial: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val settings: StateFlow<TrackerSettings> = settingsRepository.settings.state(TrackerSettings())
    val running: StateFlow<Boolean> = TrackerVpnState.running
    val sessionBlocked: StateFlow<Int> = TrackerVpnState.sessionBlocked
    val sessionQueries: StateFlow<Int> = TrackerVpnState.sessionQueries
    val blockedToday: StateFlow<Int> = stats.blockedToday.state(0)
    val blockedWeek: StateFlow<Int> = stats.blockedWeek.state(0)
    val topToday: StateFlow<List<TrackerCount>> = stats.topAppsToday().state(emptyList())
    val topWeek: StateFlow<List<TrackerCount>> = stats.topAppsWeek().state(emptyList())
    val topDomains: StateFlow<List<TrackerCount>> = stats.topDomainsWeek().state(emptyList())
    val builtInCount: Int get() = blocklist.builtInCount

    private val _environment = MutableStateFlow(VpnEnvironment())
    val environment: StateFlow<VpnEnvironment> = _environment.asStateFlow()

    fun refresh() {
        _environment.value = VpnEnvironment(vpn.otherVpnActive(), vpn.privateDnsHostname())
    }

    fun label(packageName: String): String =
        if (packageName == TrackerStatsRepository.UNKNOWN_APP) "System / nicht zuordenbar" else apps.labelOf(packageName)

    /** @return Fehlermeldung oder `null`. */
    fun addDomain(input: String, allow: Boolean): String? {
        val domain = HostsParser.normalize(input) ?: return "Keine gültige Domain (z. B. tracker.example.com)"
        viewModelScope.launch { if (allow) settingsRepository.addAllowed(domain) else settingsRepository.addCustom(domain) }
        return null
    }

    fun removeCustom(domain: String) {
        viewModelScope.launch { settingsRepository.removeCustom(domain) }
    }

    fun removeAllowed(domain: String) {
        viewModelScope.launch { settingsRepository.removeAllowed(domain) }
    }

    fun import(uri: Uri, onResult: (String) -> Unit) {
        viewModelScope.launch {
            blocklist.import(uri)
                .onSuccess { onResult("$it Domains importiert") }
                .onFailure { onResult(it.message ?: "Import fehlgeschlagen") }
        }
    }

    fun clearImport() {
        viewModelScope.launch { blocklist.clearImport() }
    }

    fun clearStats() {
        viewModelScope.launch { stats.clear() }
    }
}

@Composable
fun TrackerBlockerScreen(
    onBack: () -> Unit,
    viewModel: TrackerViewModel = hiltViewModel(),
    extras: ExtrasViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val sessionBlocked by viewModel.sessionBlocked.collectAsStateWithLifecycle()
    val sessionQueries by viewModel.sessionQueries.collectAsStateWithLifecycle()
    val blockedToday by viewModel.blockedToday.collectAsStateWithLifecycle()
    val blockedWeek by viewModel.blockedWeek.collectAsStateWithLifecycle()
    val topToday by viewModel.topToday.collectAsStateWithLifecycle()
    val topWeek by viewModel.topWeek.collectAsStateWithLifecycle()
    val topDomains by viewModel.topDomains.collectAsStateWithLifecycle()
    val environment by viewModel.environment.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showWeek by remember { mutableStateOf(false) }
    var domainInput by remember { mutableStateOf("") }
    val toggle = rememberTrackerToggle(extras) {
        viewModel.refresh()
        scope.launch { snackbar.showSnackbar("Ein anderes VPN ist aktiv – Android erlaubt nur eines gleichzeitig.") }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.import(it) { msg -> scope.launch { snackbar.showSnackbar(msg) } } }
    }

    GuardSubScreen(title = "Tracker-Blocker", onBack = onBack) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionCard {
                    SwitchRow(
                        title = "Tracker-Blocker",
                        subtitle = when {
                            running -> "Aktiv · seit dem Start $sessionBlocked von $sessionQueries Anfragen blockiert"
                            settings.enabled -> "Wird gestartet …"
                            else -> "Richtet ein rein lokales VPN ein. Es werden keine Daten an fremde Server gesendet."
                        },
                        checked = settings.enabled,
                        onCheckedChange = toggle,
                    )
                    if (environment.otherVpn && !running) {
                        Text(
                            "Ein anderes VPN (z. B. WireGuard oder Firmen-VPN) ist aktiv. Android erlaubt nur ein VPN – " +
                                "der Tracker-Blocker bleibt deshalb aus.",
                            color = StatusColors.Denied,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    environment.privateDns?.let { host ->
                        Text(
                            "„Privates DNS“ ist fest auf $host eingestellt. Android schickt DNS-Anfragen dann verschlüsselt " +
                                "am VPN vorbei – für den Tracker-Blocker in den Netzwerk-Einstellungen auf „Automatisch“ stellen.",
                            color = StatusColors.Partial,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                SectionCard {
                    Row {
                        Stat("Heute", blockedToday, Modifier.weight(1f))
                        Stat("7 Tage", blockedWeek, Modifier.weight(1f))
                    }
                    Text("Rangliste", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    ChoiceChips(
                        options = listOf(false to "Heute", true to "7 Tage"),
                        selected = showWeek,
                        onSelect = { showWeek = it },
                    )
                    val list = if (showWeek) topWeek else topToday
                    if (list.isEmpty()) Text("Noch keine blockierten Anfragen.", style = MaterialTheme.typography.bodySmall)
                    list.forEach { entry -> AppCountRow(entry, viewModel.label(entry.name)) }
                    Text(
                        "Die Zuordnung zur App ist eine Schätzung: Viele DNS-Anfragen stellt Android stellvertretend für die App.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                if (topDomains.isNotEmpty()) {
                    SectionCard {
                        Text("Häufigste Tracker (7 Tage)", style = MaterialTheme.typography.titleSmall)
                        topDomains.forEach { entry ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${entry.blocked}", style = MaterialTheme.typography.labelLarge)
                                TextButton(onClick = {
                                    viewModel.addDomain(entry.name, allow = true)
                                    scope.launch { snackbar.showSnackbar("${entry.name} wird künftig erlaubt") }
                                }) { Text("Erlauben") }
                            }
                        }
                    }
                }

                SectionCard {
                    Text("Blockliste", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${viewModel.builtInCount} mitgelieferte Tracker-Domains" +
                            (settings.importedName?.let { " + ${settings.importedCount} aus „$it“" } ?: "") +
                            " + ${settings.customBlocked.size} eigene. Subdomains werden mitgesperrt.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) }) {
                            Text("Hosts-Datei importieren")
                        }
                        if (settings.importedName != null) TextButton(onClick = viewModel::clearImport) { Text("Import entfernen") }
                    }
                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = { domainInput = it },
                        singleLine = true,
                        label = { Text("Domain") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val error = viewModel.addDomain(domainInput, allow = false)
                            if (error == null) domainInput = "" else scope.launch { snackbar.showSnackbar(error) }
                        }) { Text("Sperren") }
                        OutlinedButton(onClick = {
                            val error = viewModel.addDomain(domainInput, allow = true)
                            if (error == null) domainInput = "" else scope.launch { snackbar.showSnackbar(error) }
                        }) { Text("Erlauben") }
                    }
                    settings.customBlocked.sorted().forEach { DomainChipRow(it, "gesperrt") { viewModel.removeCustom(it) } }
                    settings.allowed.sorted().forEach { DomainChipRow(it, "erlaubt") { viewModel.removeAllowed(it) } }
                }

                TextButton(onClick = viewModel::clearStats) { Text("Statistik löschen") }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text("$value", style = MaterialTheme.typography.headlineMedium)
        Text("blockiert", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AppCountRow(entry: TrackerCount, label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (entry.name != TrackerStatsRepository.UNKNOWN_APP) AppIcon(entry.name, size = 32.dp) else Spacer(Modifier.width(32.dp))
        Text(label, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${entry.blocked}", style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun DomainChipRow(domain: String, kind: String, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(domain, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(kind, style = MaterialTheme.typography.labelSmall)
        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Entfernen") }
    }
}
