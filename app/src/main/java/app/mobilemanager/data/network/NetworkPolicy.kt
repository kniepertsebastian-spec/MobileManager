package app.mobilemanager.data.network

import app.mobilemanager.di.AppCoroutines
import android.app.PendingIntent
import android.app.usage.NetworkStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.mobilemanager.MainActivity
import app.mobilemanager.data.shizuku.ShizukuManager
import app.mobilemanager.data.work.AppNotifications
import app.mobilemanager.domain.extras.DataLimitPolicy
import app.mobilemanager.domain.extras.FirewallPolicy
import app.mobilemanager.domain.extras.NetType
import app.mobilemanager.domain.extras.PrivateDnsMode
import app.mobilemanager.domain.extras.ShellCommandPolicy
import app.mobilemanager.domain.extras.WifiSafetyPolicy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private val Context.networkStore by preferencesDataStore(name = "network_policy")

/** Phase 4.1: Einstellungen des Netzwerk-Wächters. */
data class NetworkPolicySettings(
    val firewallEnabled: Boolean = false,
    val blockWifi: Set<String> = emptySet(),
    val blockMobile: Set<String> = emptySet(),
    val killSwitchEnabled: Boolean = false,
    val killSwitchMinutes: Int = 10,
    val killSwitchPackages: Set<String> = emptySet(),
    val privateDnsEnabled: Boolean = false,
    val wifiDnsMode: PrivateDnsMode = PrivateDnsMode.UNCHANGED,
    val wifiDnsHost: String = "",
    val mobileDnsMode: PrivateDnsMode = PrivateDnsMode.UNCHANGED,
    val mobileDnsHost: String = "",
    val unsafeWifiWarning: Boolean = false,
    val mobileDailyLimitMb: Int = 0,
) {
    /** Braucht irgendein Teil die Netzwechsel-Überwachung? */
    val needsWatching: Boolean
        get() = firewallEnabled || privateDnsEnabled || unsafeWifiWarning

    val needsScreenWatch: Boolean get() = firewallEnabled && killSwitchEnabled && killSwitchPackages.isNotEmpty()
}

@Singleton
class NetworkPolicyRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val FIREWALL = booleanPreferencesKey("firewall_enabled")
        val BLOCK_WIFI = stringSetPreferencesKey("block_wifi")
        val BLOCK_MOBILE = stringSetPreferencesKey("block_mobile")
        val KILL = booleanPreferencesKey("kill_switch")
        val KILL_MINUTES = intPreferencesKey("kill_minutes")
        val KILL_PACKAGES = stringSetPreferencesKey("kill_packages")
        val DNS = booleanPreferencesKey("private_dns")
        val WIFI_DNS_MODE = stringPreferencesKey("wifi_dns_mode")
        val WIFI_DNS_HOST = stringPreferencesKey("wifi_dns_host")
        val MOBILE_DNS_MODE = stringPreferencesKey("mobile_dns_mode")
        val MOBILE_DNS_HOST = stringPreferencesKey("mobile_dns_host")
        val UNSAFE_WIFI = booleanPreferencesKey("unsafe_wifi")
        val DATA_LIMIT = intPreferencesKey("mobile_limit_mb")
        val APPLIED_DENIED = stringSetPreferencesKey("applied_denied")
        val LIMIT_WARNED_DAY = intPreferencesKey("limit_warned_day")
    }

    val settings: Flow<NetworkPolicySettings> = context.networkStore.data.map(::fromPrefs)

    suspend fun current(): NetworkPolicySettings = settings.first()

    suspend fun update(transform: (NetworkPolicySettings) -> NetworkPolicySettings) {
        context.networkStore.edit { p ->
            val n = transform(fromPrefs(p))
            p[Keys.FIREWALL] = n.firewallEnabled
            p[Keys.BLOCK_WIFI] = n.blockWifi
            p[Keys.BLOCK_MOBILE] = n.blockMobile
            p[Keys.KILL] = n.killSwitchEnabled
            p[Keys.KILL_MINUTES] = n.killSwitchMinutes
            p[Keys.KILL_PACKAGES] = n.killSwitchPackages
            p[Keys.DNS] = n.privateDnsEnabled
            p[Keys.WIFI_DNS_MODE] = n.wifiDnsMode.name
            p[Keys.WIFI_DNS_HOST] = n.wifiDnsHost
            p[Keys.MOBILE_DNS_MODE] = n.mobileDnsMode.name
            p[Keys.MOBILE_DNS_HOST] = n.mobileDnsHost
            p[Keys.UNSAFE_WIFI] = n.unsafeWifiWarning
            p[Keys.DATA_LIMIT] = n.mobileDailyLimitMb
        }
    }

    /** Pakete, die MobileManager gerade über die System-Firewall sperrt (für das spätere Freigeben). */
    suspend fun appliedDenied(): Set<String> = context.networkStore.data.first()[Keys.APPLIED_DENIED] ?: emptySet()

    suspend fun setAppliedDenied(packages: Set<String>) = context.networkStore.edit { it[Keys.APPLIED_DENIED] = packages }

    suspend fun limitWarnedDay(): Int = context.networkStore.data.first()[Keys.LIMIT_WARNED_DAY] ?: 0

    suspend fun setLimitWarnedDay(day: Int) = context.networkStore.edit { it[Keys.LIMIT_WARNED_DAY] = day }

    private fun fromPrefs(p: Preferences) = NetworkPolicySettings(
        firewallEnabled = p[Keys.FIREWALL] ?: false,
        blockWifi = p[Keys.BLOCK_WIFI] ?: emptySet(),
        blockMobile = p[Keys.BLOCK_MOBILE] ?: emptySet(),
        killSwitchEnabled = p[Keys.KILL] ?: false,
        killSwitchMinutes = p[Keys.KILL_MINUTES] ?: 10,
        killSwitchPackages = p[Keys.KILL_PACKAGES] ?: emptySet(),
        privateDnsEnabled = p[Keys.DNS] ?: false,
        wifiDnsMode = p[Keys.WIFI_DNS_MODE].toDnsMode(),
        wifiDnsHost = p[Keys.WIFI_DNS_HOST].orEmpty(),
        mobileDnsMode = p[Keys.MOBILE_DNS_MODE].toDnsMode(),
        mobileDnsHost = p[Keys.MOBILE_DNS_HOST].orEmpty(),
        unsafeWifiWarning = p[Keys.UNSAFE_WIFI] ?: false,
        mobileDailyLimitMb = p[Keys.DATA_LIMIT] ?: 0,
    )

    private fun String?.toDnsMode() = PrivateDnsMode.entries.firstOrNull { it.name == this } ?: PrivateDnsMode.UNCHANGED
}

/** Zustand für die Oberfläche. */
data class NetworkPolicyStatus(
    val net: NetType = NetType.NONE,
    val denied: Set<String> = emptySet(),
    val killSwitchActive: Boolean = false,
    val lastError: String? = null,
    val lastApplied: Long = 0,
)

/**
 * Phase 4.1: setzt Firewall, Kill-Switch und Privates DNS über Shizuku um.
 *
 * Die Firewall nutzt die Android-eigene Sperrkette „chain3“ (`cmd connectivity set-package-networking-enabled`):
 * Sie wirkt systemweit, unabhängig vom Tracker-VPN und auch neben einem fremden VPN. Android vergisst die Liste
 * beim Neustart – MobileManager setzt sie beim Start, bei jedem Netzwechsel und im 15-Minuten-Check neu.
 */
@Singleton
class NetworkPolicyController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: NetworkPolicyRepository,
    private val shizuku: ShizukuManager,
    private val notifications: AppNotifications,
) {
    private val scope = AppCoroutines.scope("NetworkPolicy", Dispatchers.Default)
    private val mutex = Mutex()
    private val cm get() = context.getSystemService(ConnectivityManager::class.java)

    private val _status = MutableStateFlow(NetworkPolicyStatus())
    val status: StateFlow<NetworkPolicyStatus> = _status.asStateFlow()

    @Volatile private var killSwitchActive = false
    @Volatile private var firewallSynced = false
    private var killJob: Job? = null
    private var watching = false
    private var screenRegistered = false
    private var warnedNetwork: Network? = null

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            checkWifiSafety(network, caps)
            onNetworkChanged()
        }

        override fun onLost(network: Network) = onNetworkChanged()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON -> onScreenOn()
            }
        }
    }

    init {
        // Shizuku startet nach einem Neustart oft erst später – dann die Regeln sofort nachziehen.
        scope.launch {
            shizuku.state.collect { state ->
                if (state == app.mobilemanager.data.shizuku.ShizukuState.READY && !firewallSynced) {
                    val s = repository.current()
                    if (s.firewallEnabled || s.privateDnsEnabled || repository.appliedDenied().isNotEmpty()) apply()
                }
            }
        }
    }

    fun currentNetType(): NetType {
        val manager = cm ?: return NetType.NONE
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return NetType.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetType.MOBILE
            else -> NetType.NONE
        }
    }

    fun requestApply() {
        scope.launch { apply() }
    }

    private var pendingApply: Job? = null

    /** Capability-Änderungen kommen sehr häufig (Signalstärke) – nur bei echtem Netzartwechsel, entprellt, handeln. */
    private fun onNetworkChanged() {
        if (currentNetType() == _status.value.net && _status.value.lastApplied != 0L) return
        pendingApply?.cancel()
        pendingApply = scope.launch {
            delay(NETWORK_DEBOUNCE_MS)
            apply()
        }
    }

    /** Setzt den Sollzustand für das aktuelle Netz um. @return Fehlermeldung oder `null`. */
    suspend fun apply(): String? = mutex.withLock {
        val s = repository.current()
        val net = currentNetType()
        val protected = setOf(context.packageName, ShizukuManager.SHIZUKU_PACKAGE)
        val target = if (s.firewallEnabled) {
            FirewallPolicy.denied(net, s.blockWifi, s.blockMobile, killSwitchActive, s.killSwitchPackages, protected)
        } else {
            emptySet()
        }
        val applied = repository.appliedDenied()
        var error: String? = null
        // Nach einem Neustart ist die System-Liste leer, die gespeicherte aber nicht: einmal pro Prozess komplett setzen.
        if (target != applied || (target.isNotEmpty() && !firewallSynced)) {
            error = applyFirewall(applied, target)
            if (error == null) firewallSynced = true
        }
        if (s.privateDnsEnabled && error == null) error = applyPrivateDns(s, net)
        _status.value = NetworkPolicyStatus(net, if (error == null) target else applied, killSwitchActive, error, System.currentTimeMillis())
        syncWatching(s)
        error
    }

    private suspend fun applyFirewall(applied: Set<String>, target: Set<String>): String? {
        if (!shizuku.isReady) return "Shizuku ist nicht aktiv – Firewall-Regeln können nicht gesetzt werden"
        if (target.isNotEmpty()) {
            shizuku.exec("cmd", "connectivity", "set-chain3-enabled", "true").error?.let { return "Firewall-Kette: $it" }
        }
        // Nach einem Neustart ist die Liste leer: Sollzustand immer komplett setzen, nicht nur den Unterschied.
        val (_, release) = FirewallPolicy.diff(applied, target)
        val stillDenied = applied.toMutableSet()
        var error: String? = null
        for (pkg in target) {
            val result = shizuku.exec("cmd", "connectivity", "set-package-networking-enabled", "false", pkg)
            if (result.ok) stillDenied += pkg else if (!isUninstalled(pkg)) error = "$pkg: ${result.error}"
        }
        for (pkg in release) {
            val result = shizuku.exec("cmd", "connectivity", "set-package-networking-enabled", "true", pkg)
            if (result.ok || isUninstalled(pkg)) stillDenied -= pkg else error = "$pkg: ${result.error}"
        }
        if (target.isEmpty() && stillDenied.isEmpty()) shizuku.exec("cmd", "connectivity", "set-chain3-enabled", "false")
        repository.setAppliedDenied(stillDenied)
        return error
    }

    private fun isUninstalled(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isFailure

    private suspend fun applyPrivateDns(s: NetworkPolicySettings, net: NetType): String? {
        val (mode, host) = when (net) {
            NetType.WIFI -> s.wifiDnsMode to s.wifiDnsHost
            NetType.MOBILE -> s.mobileDnsMode to s.mobileDnsHost
            NetType.NONE -> return null
        }
        if (mode == PrivateDnsMode.UNCHANGED) return null
        if (!shizuku.isReady) return "Shizuku ist nicht aktiv – Privates DNS kann nicht umgeschaltet werden"
        val value = when (mode) {
            PrivateDnsMode.OFF -> "off"
            PrivateDnsMode.AUTOMATIC -> "opportunistic"
            PrivateDnsMode.HOSTNAME -> "hostname"
            PrivateDnsMode.UNCHANGED -> return null
        }
        if (mode == PrivateDnsMode.HOSTNAME) {
            val clean = host.trim().lowercase()
            if (!ShellCommandPolicy.isHostname(clean)) return "Ungültiger DNS-Anbieter: $host"
            if (currentSetting("private_dns_specifier") != clean) {
                shizuku.exec("settings", "put", "global", "private_dns_specifier", clean).error?.let { return it }
            }
        }
        if (currentSetting("private_dns_mode") == value) return null
        return shizuku.exec("settings", "put", "global", "private_dns_mode", value).error
    }

    private suspend fun currentSetting(key: String): String? =
        shizuku.exec("settings", "get", "global", key).takeIf { it.ok }?.output?.trim()

    /** Hält Netz- und Display-Beobachtung nur aktiv, solange eine Funktion sie braucht. */
    private fun syncWatching(s: NetworkPolicySettings) {
        val manager = cm ?: return
        if (s.needsWatching && !watching) {
            runCatching { manager.registerDefaultNetworkCallback(defaultCallback) }.onSuccess { watching = true }
            registerWakeIntent(manager, true)
        } else if (!s.needsWatching && watching) {
            runCatching { manager.unregisterNetworkCallback(defaultCallback) }
            registerWakeIntent(manager, false)
            watching = false
        }
        if (s.needsScreenWatch && !screenRegistered) {
            ContextCompat.registerReceiver(
                context,
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            screenRegistered = true
        } else if (!s.needsScreenWatch && screenRegistered) {
            runCatching { context.unregisterReceiver(screenReceiver) }
            screenRegistered = false
            if (killSwitchActive) onScreenOn()
        }
    }

    /**
     * Weckt den App-Prozess, wenn ein WLAN oder Mobilfunknetz verfügbar wird – auch wenn MobileManager gerade
     * nicht läuft. Solange der Prozess lebt, übernimmt der Standardnetz-Callback auch Netzverluste.
     */
    private fun registerWakeIntent(manager: ConnectivityManager, register: Boolean) {
        val intent = PendingIntent.getBroadcast(
            context,
            WAKE_REQUEST,
            Intent(context, NetworkWakeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        runCatching {
            if (register) {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                manager.registerNetworkCallback(request, intent)
            } else {
                manager.unregisterNetworkCallback(intent)
            }
        }
    }

    private fun onScreenOff() {
        scope.launch {
            val s = repository.current()
            if (!s.needsScreenWatch) return@launch
            killJob?.cancel()
            killJob = scope.launch {
                delay(s.killSwitchMinutes.coerceAtLeast(1) * 60_000L)
                val power = context.getSystemService(PowerManager::class.java)
                if (power?.isInteractive == false) {
                    killSwitchActive = true
                    apply()
                }
            }
        }
    }

    private fun onScreenOn() {
        killJob?.cancel()
        killJob = null
        if (killSwitchActive) {
            killSwitchActive = false
            requestApply()
        }
    }

    private fun checkWifiSafety(network: Network, caps: NetworkCapabilities) {
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || network == warnedNetwork) return
        scope.launch {
            if (!repository.current().unsafeWifiWarning) return@launch
            val info = caps.transportInfo as? WifiInfo ?: return@launch
            val warning = WifiSafetyPolicy.warning(
                info.currentSecurityType,
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            ) ?: return@launch
            warnedNetwork = network
            notifications.showInfo(AppNotifications.ID_UNSAFE_WIFI, "Unsicheres WLAN", warning, MainActivity.DESTINATION_NETWORK)
        }
    }

    /** Mobile Daten seit Mitternacht in Bytes (NetworkStatsManager, braucht den Nutzungszugriff). */
    fun mobileBytesToday(): Long? = bytesToday(NetworkCapabilities.TRANSPORT_CELLULAR)

    fun wifiBytesToday(): Long? = bytesToday(NetworkCapabilities.TRANSPORT_WIFI)

    private fun bytesToday(transport: Int): Long? = runCatching {
        val nsm = context.getSystemService(NetworkStatsManager::class.java)
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Die Netzwerktyp-Werte von NetworkStatsManager entsprechen den Transport-Konstanten (Mobil = 0, WLAN = 1).
        val bucket = nsm.querySummaryForDevice(transport, null, start, System.currentTimeMillis())
        bucket.rxBytes + bucket.txBytes
    }.getOrNull()

    /** Aus dem 15-Minuten-Check: Regeln erneuern und das mobile Tageslimit prüfen. */
    suspend fun periodicCheck() {
        val s = repository.current()
        if (s.firewallEnabled || s.privateDnsEnabled) apply()
        if (s.mobileDailyLimitMb > 0) {
            val used = mobileBytesToday() ?: return
            val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE).toInt()
            if (DataLimitPolicy.shouldWarn(used, s.mobileDailyLimitMb, repository.limitWarnedDay(), today)) {
                repository.setLimitWarnedDay(today)
                notifications.showInfo(
                    AppNotifications.ID_DATA_LIMIT,
                    "Mobiles Datenlimit erreicht",
                    "Heute schon ${used / (1024 * 1024)} MB über Mobilfunk – Limit: ${s.mobileDailyLimitMb} MB.",
                    MainActivity.DESTINATION_NETWORK,
                )
            }
        }
    }

    /** Beim App-Start: Beobachtung wieder aufnehmen, falls eine Funktion aktiv ist. */
    fun start() {
        scope.launch {
            val s = repository.current()
            if (s.needsWatching || repository.appliedDenied().isNotEmpty()) apply()
        }
    }

    private companion object {
        const val WAKE_REQUEST = 4101
        const val NETWORK_DEBOUNCE_MS = 1_500L
    }
}

/** Wird vom System bei einem neuen Netz gesendet (siehe [NetworkPolicyController.registerWakeIntent]). */
class NetworkWakeReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WakeEntryPoint {
        fun networkPolicy(): NetworkPolicyController
    }

    override fun onReceive(context: Context, intent: Intent) {
        val controller = EntryPointAccessors.fromApplication(context.applicationContext, WakeEntryPoint::class.java).networkPolicy()
        val pending = goAsync()
        AppCoroutines.scope("NetworkPolicy", Dispatchers.Default).launch {
            try {
                controller.apply()
            } finally {
                pending.finish()
            }
        }
    }
}
