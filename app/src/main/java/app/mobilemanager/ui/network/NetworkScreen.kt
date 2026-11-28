package app.mobilemanager.ui.network

import android.content.Intent
import android.net.TrafficStats
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.apps.InstalledAppsRepository
import app.mobilemanager.data.network.NetworkPolicyController
import app.mobilemanager.data.network.NetworkPolicyRepository
import app.mobilemanager.data.network.NetworkPolicySettings
import app.mobilemanager.data.network.NetworkPolicyStatus
import app.mobilemanager.data.shizuku.ShizukuManager
import app.mobilemanager.data.shizuku.ShizukuState
import app.mobilemanager.data.tracker.TrackerBlocklistRepository
import app.mobilemanager.data.tracker.TrackerSettings
import app.mobilemanager.data.tracker.TrackerSettingsRepository
import app.mobilemanager.data.tracker.TrackerVpnState
import app.mobilemanager.data.work.WorkScheduler
import app.mobilemanager.domain.extras.PrivateDnsMode
import app.mobilemanager.domain.model.LaunchableApp
import app.mobilemanager.ui.components.AppPickerDialog
import app.mobilemanager.ui.components.AppSelectionRow
import app.mobilemanager.ui.components.SectionCard
import app.mobilemanager.ui.components.ShizukuRequirement
import app.mobilemanager.ui.components.formatBytes
import app.mobilemanager.ui.guard.ChoiceChips
import app.mobilemanager.ui.guard.GuardSubScreen
import app.mobilemanager.ui.guard.SwitchRow
import app.mobilemanager.ui.guard.relativeTime
import app.mobilemanager.ui.guard.startActivitySafely
import app.mobilemanager.ui.theme.StatusColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val repository: NetworkPolicyRepository,
    private val controller: NetworkPolicyController,
    private val shizuku: ShizukuManager,
    private val apps: InstalledAppsRepository,
    private val tracker: TrackerSettingsRepository,
    private val workScheduler: WorkScheduler,
) : ViewModel() {
    val settings: StateFlow<NetworkPolicySettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NetworkPolicySettings())
    val status: StateFlow<NetworkPolicyStatus> = controller.status
    val shizukuState: StateFlow<ShizukuState> = shizuku.state
    val trackerSettings: StateFlow<TrackerSettings> =
        tracker.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackerSettings())

    private val _apps = MutableStateFlow<List<LaunchableApp>>(emptyList())
    val launchable: StateFlow<List<LaunchableApp>> = _apps.asStateFlow()
    private val _usage = MutableStateFlow<Pair<Long?, Long?>>(null to null)
    val usageToday: StateFlow<Pair<Long?, Long?>> = _usage.asStateFlow()

    fun refresh() {
        shizuku.refresh()
        viewModelScope.launch { _apps.value = apps.getLaunchableApps() }
        viewModelScope.launch { _usage.value = controller.mobileBytesToday() to controller.wifiBytesToday() }
    }

    fun update(transform: (NetworkPolicySettings) -> NetworkPolicySettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun applyNow() {
        viewModelScope.launch { controller.apply() }
    }

    fun requestShizuku() = shizuku.requestPermission()
    fun shizukuIntent() = shizuku.launchShizukuAppIntent()

    fun setSubscriptions(urls: Set<String>) {
        viewModelScope.launch {
            tracker.setSubscriptions(urls)
            if (urls.isNotEmpty()) workScheduler.updateBlocklistsNow()
        }
    }

    fun updateBlocklistsNow() = workScheduler.updateBlocklistsNow()

    fun setDoh(url: String) {
        viewModelScope.launch { tracker.setDohUrl(url) }
    }
}

private val DNS_PROVIDERS = listOf(
    "dns.quad9.net" to "Quad9",
    "dns.mullvad.net" to "Mullvad",
    "one.one.one.one" to "Cloudflare",
    "dns.adguard-dns.com" to "AdGuard",
)

/** Phase 4.1: Netzwerk-Wächter – Firewall pro App, Kill-Switch, Privates DNS, WLAN-Warnung, Datenlimit. */
@Composable
fun NetworkScreen(onBack: () -> Unit, viewModel: NetworkViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val shizuku by viewModel.shizukuState.collectAsStateWithLifecycle()
    val apps by viewModel.launchable.collectAsStateWithLifecycle()
    val usage by viewModel.usageToday.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    var picker by remember { mutableStateOf<PickerTarget?>(null) }

    GuardSubScreen(title = "Netzwerk-Wächter", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShizukuRequirement(shizuku, viewModel::requestShizuku) { context.startActivitySafely(viewModel.shizukuIntent()) }

            SectionCard {
                Text("Aktuelles Netz: ${status.net.label}", style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        append(if (status.denied.isEmpty()) "Keine App gesperrt" else "${status.denied.size} Apps ohne Internet")
                        if (status.killSwitchActive) append(" · Kill-Switch aktiv")
                        if (status.lastApplied > 0) append(" · geprüft ${relativeTime(status.lastApplied)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                status.lastError?.let { Text(it, color = StatusColors.Denied, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = viewModel::applyNow) { Text("Jetzt anwenden") }
            }

            SectionCard {
                SwitchRow(
                    title = "Firewall pro App",
                    subtitle = "Sperrt Apps getrennt für WLAN und Mobilfunk – über die Android-eigene Netzsperre (Shizuku). " +
                        "Wirkt auch neben dem Tracker-Blocker oder einem fremden VPN.",
                    checked = s.firewallEnabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(firewallEnabled = v) } },
                )
                AppSelectionRow("Im WLAN sperren (z. B. Zuhause)", s.blockWifi.size, { picker = PickerTarget.WIFI })
                AppSelectionRow("Im Mobilfunk sperren (Unterwegs)", s.blockMobile.size, { picker = PickerTarget.MOBILE })
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SwitchRow(
                    title = "Screen-Off-Kill-Switch",
                    subtitle = "Sperrt die gewählten Apps, sobald das Display länger aus ist, und gibt sie beim Einschalten frei. " +
                        "Zuverlässig mit eingeschaltetem Echtzeit-Wächter.",
                    checked = s.killSwitchEnabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(killSwitchEnabled = v) } },
                    enabled = s.firewallEnabled,
                )
                ChoiceChips(
                    options = listOf(2 to "2 min", 5 to "5 min", 10 to "10 min", 30 to "30 min"),
                    selected = s.killSwitchMinutes,
                    onSelect = { v -> viewModel.update { it.copy(killSwitchMinutes = v) } },
                    enabled = s.firewallEnabled && s.killSwitchEnabled,
                )
                AppSelectionRow("Unwichtige Apps für den Kill-Switch", s.killSwitchPackages.size, { picker = PickerTarget.KILL })
            }

            SectionCard {
                SwitchRow(
                    title = "Privates DNS je Netz",
                    subtitle = "Schaltet „Privates DNS“ automatisch um, z. B. Mobilfunk verschlüsselt über Quad9, " +
                        "WLAN aus (damit der Router-Filter greift). Fester Anbieter umgeht den Tracker-Blocker.",
                    checked = s.privateDnsEnabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(privateDnsEnabled = v) } },
                )
                DnsChoice("WLAN", s.wifiDnsMode, s.wifiDnsHost, s.privateDnsEnabled) { mode, host ->
                    viewModel.update { it.copy(wifiDnsMode = mode, wifiDnsHost = host) }
                }
                DnsChoice("Mobilfunk", s.mobileDnsMode, s.mobileDnsHost, s.privateDnsEnabled) { mode, host ->
                    viewModel.update { it.copy(mobileDnsMode = mode, mobileDnsHost = host) }
                }
            }

            SectionCard {
                SwitchRow(
                    title = "Unsicheres-WLAN-Wächter",
                    subtitle = "Warnt bei offenen WLANs, veralteter WEP-Verschlüsselung und Anmeldeseiten (Captive Portal).",
                    checked = s.unsafeWifiWarning,
                    onCheckedChange = { v -> viewModel.update { it.copy(unsafeWifiWarning = v) } },
                )
            }

            SectionCard {
                Text("Datennutzung heute", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Mobilfunk: ${usage.first?.let { formatBytes(context, it) } ?: "–"} · WLAN: ${usage.second?.let { formatBytes(context, it) } ?: "–"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LiveRate()
                Text("Tageslimit Mobilfunk (Warnung)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                ChoiceChips(
                    options = listOf(0 to "Aus", 200 to "200 MB", 500 to "500 MB", 1024 to "1 GB", 2048 to "2 GB"),
                    selected = s.mobileDailyLimitMb,
                    onSelect = { v -> viewModel.update { it.copy(mobileDailyLimitMb = v) } },
                )
                if (usage.first == null) {
                    Text("Braucht den Nutzungszugriff (Berechtigungen).", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    picker?.let { target ->
        val (title, selected) = when (target) {
            PickerTarget.WIFI -> "Im WLAN sperren" to s.blockWifi
            PickerTarget.MOBILE -> "Im Mobilfunk sperren" to s.blockMobile
            PickerTarget.KILL -> "Kill-Switch: unwichtige Apps" to s.killSwitchPackages
        }
        AppPickerDialog(title, apps, selected, onDismiss = { picker = null }) { chosen ->
            viewModel.update {
                when (target) {
                    PickerTarget.WIFI -> it.copy(blockWifi = chosen)
                    PickerTarget.MOBILE -> it.copy(blockMobile = chosen)
                    PickerTarget.KILL -> it.copy(killSwitchPackages = chosen)
                }
            }
            picker = null
        }
    }
}

private enum class PickerTarget { WIFI, MOBILE, KILL }

@Composable
private fun DnsChoice(label: String, mode: PrivateDnsMode, host: String, enabled: Boolean, onChange: (PrivateDnsMode, String) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    ChoiceChips(
        options = PrivateDnsMode.entries.map { it to it.label },
        selected = mode,
        onSelect = { onChange(it, host.ifBlank { DNS_PROVIDERS.first().first }) },
        enabled = enabled,
    )
    if (mode == PrivateDnsMode.HOSTNAME) {
        ChoiceChips(
            options = DNS_PROVIDERS,
            selected = host,
            onSelect = { onChange(mode, it) },
            enabled = enabled,
        )
        var text by remember(host) { mutableStateOf(host) }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.trim().lowercase() },
            label = { Text("Anbieter-Hostname") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = { TextButton(onClick = { onChange(mode, text) }, enabled = text != host) { Text("OK") } },
        )
    }
}

/** Live-Rate aller Netze (TrafficStats), solange der Bildschirm offen ist. */
@Composable
private fun LiveRate() {
    val context = LocalContext.current
    var down by remember { mutableLongStateOf(0L) }
    var up by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTx = TrafficStats.getTotalTxBytes()
        while (true) {
            delay(1_000)
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            down = (rx - lastRx).coerceAtLeast(0)
            up = (tx - lastTx).coerceAtLeast(0)
            lastRx = rx
            lastTx = tx
        }
    }
    Text("Jetzt: ↓ ${formatBytes(context, down)}/s · ↑ ${formatBytes(context, up)}/s", style = MaterialTheme.typography.bodySmall)
}

/** Phase 4.1: Blocklisten-Abos und DNS-over-HTTPS – eingebettet im Tracker-Blocker. */
@Composable
fun TrackerAdvancedCard(viewModel: NetworkViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val t by viewModel.trackerSettings.collectAsStateWithLifecycle()
    val dohAnswered by TrackerVpnState.dohAnswered.collectAsStateWithLifecycle()
    val dohFallbacks by TrackerVpnState.dohFallbacks.collectAsStateWithLifecycle()
    val alwaysOn by TrackerVpnState.alwaysOn.collectAsStateWithLifecycle()
    val running by TrackerVpnState.running.collectAsStateWithLifecycle()
    var customUrl by remember { mutableStateOf("") }

    SectionCard {
        Text("Blocklisten abonnieren", style = MaterialTheme.typography.titleSmall)
        Text(
            "Gepflegte Listen werden wöchentlich per HTTPS geladen und lokal gespeichert – die einzige Verbindung, " +
                "die dafür nötig ist. ${if (t.subscriptionCount > 0) "${t.subscriptionCount} Domains" else ""}" +
                (if (t.subscriptionUpdated > 0) " · aktualisiert ${relativeTime(t.subscriptionUpdated)}" else ""),
            style = MaterialTheme.typography.bodySmall,
        )
        t.subscriptionError?.let { Text(it, color = StatusColors.Denied, style = MaterialTheme.typography.bodySmall) }
        TrackerBlocklistRepository.PRESETS.forEach { (name, url) ->
            SwitchRow(
                title = name,
                subtitle = null,
                checked = url in t.subscriptions,
                onCheckedChange = { on -> viewModel.setSubscriptions(if (on) t.subscriptions + url else t.subscriptions - url) },
            )
        }
        t.subscriptions.filter { url -> TrackerBlocklistRepository.PRESETS.none { it.second == url } }.forEach { url ->
            Row {
                Text(url, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(top = 12.dp))
                TextButton(onClick = { viewModel.setSubscriptions(t.subscriptions - url) }) { Text("Entfernen") }
            }
        }
        OutlinedTextField(
            value = customUrl,
            onValueChange = { customUrl = it.trim() },
            label = { Text("Eigene Liste (https://…)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(
                    onClick = {
                        viewModel.setSubscriptions(t.subscriptions + customUrl)
                        customUrl = ""
                    },
                    enabled = customUrl.startsWith("https://") && customUrl.length > 12,
                ) { Text("Hinzufügen") }
            },
        )
        if (t.subscriptions.isNotEmpty()) {
            OutlinedButton(onClick = viewModel::updateBlocklistsNow, modifier = Modifier.padding(top = 8.dp)) { Text("Jetzt aktualisieren") }
        }
    }

    SectionCard {
        Text("DNS-over-HTTPS", style = MaterialTheme.typography.titleSmall)
        Text(
            "Erlaubte Anfragen gehen verschlüsselt an den gewählten Anbieter statt unverschlüsselt an den DNS des Netzes. " +
                "Ist der Anbieter nicht erreichbar, fällt der Blocker auf den System-Resolver zurück.",
            style = MaterialTheme.typography.bodySmall,
        )
        ChoiceChips(
            options = listOf(
                "" to "Aus",
                "https://dns.quad9.net/dns-query" to "Quad9",
                "https://dns.mullvad.net/dns-query" to "Mullvad",
                "https://cloudflare-dns.com/dns-query" to "Cloudflare",
            ),
            selected = t.dohUrl,
            onSelect = viewModel::setDoh,
        )
        if (t.dohUrl.isNotEmpty() && running) {
            Text("Seit Start: $dohAnswered per DoH, $dohFallbacks Rückfälle", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (running) {
        SectionCard {
            Text("Durchgehend aktives VPN", style = MaterialTheme.typography.titleSmall)
            val (on, lockdown) = alwaysOn
            Text(
                when {
                    on && lockdown -> "Aktiv inkl. „Verbindungen ohne VPN blockieren“ – der Blocker lässt sich nicht einfach wegschalten."
                    on -> "Aktiv, aber „Verbindungen ohne VPN blockieren“ ist aus."
                    else -> "Nicht aktiv. Android kann den Blocker sonst nach Neustarts oder beim Beenden einfach weglassen."
                },
                color = if (on && lockdown) StatusColors.Granted else StatusColors.Partial,
                style = MaterialTheme.typography.bodySmall,
            )
            if (!(on && lockdown)) {
                AssistChip(onClick = { context.startActivitySafely(Intent(Settings.ACTION_VPN_SETTINGS)) }, label = { Text("VPN-Einstellungen öffnen") })
            }
        }
    }
}
