package app.mobilemanager.data.guard

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.mobilemanager.data.apps.AppVisuals
import app.mobilemanager.data.battery.BatteryGuard
import app.mobilemanager.data.battery.BatteryMonitor
import app.mobilemanager.data.extras.ExtrasCoordinator
import app.mobilemanager.data.bluetooth.BluetoothRepository
import app.mobilemanager.data.tracker.TrackerSettingsRepository
import app.mobilemanager.data.tracker.TrackerVpnController
import app.mobilemanager.data.security.PackageInstallMonitor
import app.mobilemanager.data.security.SimMonitor
import app.mobilemanager.data.work.AppNotifications
import app.mobilemanager.data.work.WorkScheduler
import app.mobilemanager.di.AppCoroutines
import app.mobilemanager.di.ApplicationScope
import app.mobilemanager.MainActivity
import app.mobilemanager.data.locker.LockerController
import app.mobilemanager.data.network.NetworkPolicyController
import app.mobilemanager.data.protection.PermissionDiffMonitor
import app.mobilemanager.data.protection.ProtectionRepository
import app.mobilemanager.data.protection.ProtectionWatchdog
import app.mobilemanager.data.protection.SensorLog
import app.mobilemanager.data.protection.UsbGuard
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Optionaler Echtzeit-Wächter (Vordergrunddienst mit dauerhafter, stiller Benachrichtigung).
 * Hört live auf Akku-Änderungen und neue App-Installationen – ohne ihn übernimmt der
 * 15-Minuten-Check die Aufgaben mit Verzögerung.
 */
@AndroidEntryPoint
class GuardService : Service() {

    @Inject lateinit var batteryMonitor: BatteryMonitor
    @Inject lateinit var batteryGuard: BatteryGuard
    @Inject lateinit var packageMonitor: PackageInstallMonitor
    @Inject lateinit var simMonitor: SimMonitor
    @Inject lateinit var notifications: AppNotifications
    @Inject lateinit var protection: ProtectionRepository
    @Inject lateinit var permissionDiff: PermissionDiffMonitor
    @Inject lateinit var sensorLog: SensorLog
    @Inject lateinit var usbGuard: UsbGuard
    @Inject lateinit var watchdog: ProtectionWatchdog
    @Inject lateinit var networkPolicy: NetworkPolicyController

    private val scope = AppCoroutines.scope("GuardService")

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.data?.schemeSpecificPart?.let(AppVisuals::invalidate)
            scope.launch {
                if (intent.action == Intent.ACTION_PACKAGE_ADDED) packageMonitor.check()
                // Phase 4.5: Updates auf neue kritische Berechtigungen prüfen.
                permissionDiff.check()
            }
        }
    }

    /** Phase 4.5: Zwischenablage bei Display aus leeren, USB-Warnung beim Anstecken. */
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch {
                val s = protection.current()
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> if (s.clipboardClearOnScreenOff) LockerController.clearClipboard(this@GuardService)
                    Intent.ACTION_POWER_CONNECTED -> if (s.usbWarning) usbGuard.onPowerConnected()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this,
            AppNotifications.ID_GUARD_SERVICE,
            notifications.guardServiceNotification(),
            // Vordergrunddienst-Typen sind erst ab Android 14 Pflicht.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        ContextCompat.registerReceiver(
            this,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            systemReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_POWER_CONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        running = true
        scope.launch { batteryMonitor.observe().collect { batteryGuard.evaluate(it) } }
        scope.launch {
            packageMonitor.check()
            simMonitor.check()
            permissionDiff.check()
            watchdog.check()
        }
        // Phase 4.5: Sensor-Protokoll folgt dem Schalter.
        scope.launch {
            protection.settings.map { it.sensorLogEnabled }.distinctUntilChanged().collect { on ->
                if (on) sensorLog.start() else sensorLog.stop()
            }
        }
        // Phase 4.1: hält den Prozess für Netzwechsel und Screen-Off-Kill-Switch am Leben.
        networkPolicy.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        unregisterReceiver(packageReceiver)
        unregisterReceiver(systemReceiver)
        sensorLog.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Läuft der Dienst in diesem Prozess? (für den Watchdog) */
        @Volatile var running = false
            private set

        /** @return `false`, wenn Android den Start verweigert (z. B. Vordergrunddienst aus dem Hintergrund). */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, GuardService::class.java))
            true
        } catch (_: ForegroundServiceStartNotAllowedException) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardService::class.java))
        }
    }
}

/**
 * Nach Neustart/Update: Echtzeit-Wächter wieder starten und SIM prüfen. Jeder Schritt läuft für sich (ein Fehler
 * reißt die anderen nicht mit), alles zusammen ist auf [MAX_RUNTIME_MS] begrenzt – `goAsync()` erlaubt nur ~10 s.
 */
class GuardBootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BootEntryPoint {
        fun guardSettings(): GuardSettingsRepository
        fun simMonitor(): SimMonitor
        fun trackerSettings(): TrackerSettingsRepository
        fun trackerVpn(): TrackerVpnController
        fun extrasCoordinator(): ExtrasCoordinator
        fun bluetoothRepository(): BluetoothRepository
        fun networkPolicy(): NetworkPolicyController
        fun protection(): ProtectionRepository
        fun notifications(): AppNotifications
        fun workScheduler(): WorkScheduler
        @ApplicationScope fun appScope(): CoroutineScope
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, BootEntryPoint::class.java)
        val pending = goAsync()
        entryPoint.appScope().launch {
            try {
                withTimeoutOrNull(MAX_RUNTIME_MS) { restore(context, intent, entryPoint) }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun restore(context: Context, intent: Intent, ep: BootEntryPoint) {
        step {
            if (ep.guardSettings().settings.first().realtimeServiceEnabled || ep.protection().current().needsRealtime) {
                if (!GuardService.start(context)) {
                    // Ohne Dienst übernimmt der 15-Minuten-Check – und der Nutzer erfährt, dass er nachhelfen muss.
                    ep.workScheduler().ensureGuardFallback()
                    ep.notifications().showInfo(
                        AppNotifications.ID_BOOT_RESTORE,
                        "Dienst nach Neustart erneut aktivieren",
                        "Android hat den Echtzeit-Wächter nach dem Neustart nicht starten lassen. Bis du MobileManager einmal öffnest, " +
                            "prüft er nur alle 15 Minuten.",
                        MainActivity.DESTINATION_SECURITY,
                    )
                }
            }
        }
        step { ep.simMonitor().check() }
        // Phase 3: Tracker-Blocker und Fokus-/Lautstärke-Dienst wiederherstellen.
        step {
            if (ep.trackerSettings().current().enabled && !ep.trackerVpn().start()) {
                ep.notifications().showInfo(
                    AppNotifications.ID_BOOT_RESTORE_VPN,
                    "Tracker-Blocker nach Neustart erneut aktivieren",
                    "Android hat das VPN nach dem Neustart nicht starten lassen. Tipp: In den Android-Einstellungen unter " +
                        "Netzwerk › VPN › MobileManager „Durchgehend aktives VPN“ einschalten – dann startet Android es selbst.",
                    MainActivity.DESTINATION_TRACKER,
                )
            }
        }
        step { ep.extrasCoordinator().sync() }
        // Verbundene Bluetooth-Geräte nach dem Neustart abgleichen (Launcher-Profilwechsel).
        step { if (intent.action == Intent.ACTION_BOOT_COMPLETED) ep.bluetoothRepository().refreshConnectedDevices() }
        // Phase 4.1: Android vergisst die Firewall-Liste beim Neustart.
        step { ep.networkPolicy().start() }
    }

    private suspend inline fun step(block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // nächster Schritt
        }
    }

    private companion object {
        const val MAX_RUNTIME_MS = 8_000L
    }
}
