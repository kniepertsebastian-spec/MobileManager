package app.mobilemanager.di

import android.content.Context
import android.content.Intent
import app.mobilemanager.MainActivity
import app.mobilemanager.data.apps.InstalledAppsRepository
import app.mobilemanager.data.extras.AppSwitchAccessibilityService
import app.mobilemanager.data.extras.ExtrasSettingsRepository
import app.mobilemanager.data.network.NetworkPolicyController
import app.mobilemanager.data.network.NetworkPolicyRepository
import app.mobilemanager.data.power.CaffeineService
import app.mobilemanager.data.power.PowerTools
import app.mobilemanager.data.protection.ProtectionRepository
import app.mobilemanager.data.tracker.TrackerSettingsRepository
import app.mobilemanager.data.tracker.TrackerVpnController
import app.mobilemanager.data.work.WorkScheduler
import app.mobilemanager.launcher.data.QuickAction
import dagger.hilt.android.qualifiers.ApplicationContext
import app.mobilemanager.data.bluetooth.BluetoothRepository
import app.mobilemanager.data.settings.SettingsRepository
import app.mobilemanager.launcher.data.LauncherHostBridge
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Verbindet das Launcher-Modul mit Phase 1: Bluetooth-Verbindungen und Nutzungsdaten. */
@Singleton
class LauncherHostBridgeImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val bluetooth: BluetoothRepository,
    private val apps: InstalledAppsRepository,
    private val extras: ExtrasSettingsRepository,
    private val tracker: TrackerSettingsRepository,
    private val vpn: TrackerVpnController,
    private val network: NetworkPolicyRepository,
    private val networkController: NetworkPolicyController,
    private val workScheduler: WorkScheduler,
    private val powerTools: PowerTools,
    private val protection: ProtectionRepository,
) : LauncherHostBridge {
    override val connectedBluetoothAddresses: Flow<Set<String>> =
        settings.connectedBluetoothDevices.map { it.keys }.distinctUntilChanged()

    override fun pairedDevices(): List<Pair<String, String>> = bluetooth.pairedDevices().map { it.address to it.name }

    override fun lastUsedByPackage(): Map<String, Long> = apps.lastUsedMap()

    override suspend fun refreshConnectedBluetooth() = bluetooth.refreshConnectedDevices()

    /* ---------- Phase 4.7 ---------- */

    override val focusModeActive: Flow<Boolean> = extras.settings.map { it.focusEnabled }.distinctUntilChanged()

    override fun performGlobalAction(action: Int): Boolean = AppSwitchAccessibilityService.performGlobal(action)

    override fun quickActions(): List<QuickAction> = listOf(
        QuickAction("vpn_on", "Tracker-Blocker einschalten", listOf("vpn an", "vpn ein", "tracker an", "werbung blocken")),
        QuickAction("vpn_off", "Tracker-Blocker ausschalten", listOf("vpn aus", "tracker aus")),
        QuickAction("firewall_on", "Firewall einschalten", listOf("firewall an", "firewall ein")),
        QuickAction("firewall_off", "Firewall ausschalten", listOf("firewall aus")),
        QuickAction("focus_on", "Fokus-Bremse einschalten", listOf("fokus an", "fokus ein", "konzentration")),
        QuickAction("focus_off", "Fokus-Bremse ausschalten", listOf("fokus aus")),
        QuickAction("stop_apps", "Inaktive Apps beenden", listOf("apps stoppen", "app-stopper", "beenden")),
        QuickAction("trim_caches", "Alle App-Caches leeren", listOf("cache leeren", "cache", "speicher frei")),
        QuickAction("caffeine", "Koffein-Modus umschalten", listOf("koffein", "display an", "wach bleiben")),
        screen("open_locker", "App-Sperre öffnen", listOf("sperre", "2fa", "app-sperre"), MainActivity.DESTINATION_LOCKER),
        screen("open_network", "Netzwerk-Wächter öffnen", listOf("netzwerk", "firewall", "dns", "datenlimit"), MainActivity.DESTINATION_NETWORK),
        screen("open_protection", "Selbstschutz öffnen", listOf("selbstschutz", "watchdog", "backup", "sensor"), MainActivity.DESTINATION_PROTECTION),
        screen("open_power", "Power-Tools öffnen", listOf("power", "bloatware", "appops", "animation"), MainActivity.DESTINATION_POWER_TOOLS),
        screen("open_tracker", "Tracker-Blocker öffnen", listOf("tracker", "blockliste", "doh"), MainActivity.DESTINATION_TRACKER),
        screen("open_cleaner", "Cleaner öffnen", listOf("cleaner", "aufräumen", "duplikate", "fotos"), MainActivity.DESTINATION_CLEANER),
    )

    private fun screen(id: String, label: String, keywords: List<String>, destination: String) = QuickAction(
        id, label, keywords,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_DESTINATION, destination)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    override suspend fun runQuickAction(id: String): String = when (id) {
        "vpn_on" -> if (vpn.prepareIntent() != null || vpn.otherVpnActive()) {
            "Bitte den Tracker-Blocker einmal in MobileManager einschalten (VPN-Freigabe)"
        } else {
            tracker.setEnabled(true)
            vpn.start()
            "Tracker-Blocker an"
        }
        "vpn_off" -> {
            tracker.setEnabled(false)
            vpn.stop()
            "Tracker-Blocker aus"
        }
        "firewall_on", "firewall_off" -> {
            network.update { it.copy(firewallEnabled = id == "firewall_on") }
            networkController.apply() ?: if (id == "firewall_on") "Firewall an" else "Firewall aus"
        }
        "focus_on", "focus_off" -> {
            extras.update { it.copy(focusEnabled = id == "focus_on") }
            if (id == "focus_on") "Fokus-Bremse an" else "Fokus-Bremse aus"
        }
        "stop_apps" -> {
            workScheduler.runAppStopperNow()
            "Inaktive Apps werden beendet"
        }
        "trim_caches" -> powerTools.trimCaches() ?: "App-Caches geleert"
        "caffeine" -> when {
            CaffeineService.isActive -> {
                CaffeineService.stop(context)
                "Koffein-Modus aus"
            }
            CaffeineService.start(context, protection.current().caffeineMinutes) -> "Koffein-Modus an"
            else -> "Koffein braucht „Über anderen Apps einblenden“"
        }
        else -> "Unbekannte Aktion"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LauncherBridgeModule {
    @Binds
    abstract fun bindLauncherHostBridge(impl: LauncherHostBridgeImpl): LauncherHostBridge
}
