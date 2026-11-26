package app.mobilemanager.data.guard

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
import app.mobilemanager.data.battery.BatteryGuard
import app.mobilemanager.data.battery.BatteryMonitor
import app.mobilemanager.data.extras.ExtrasCoordinator
import app.mobilemanager.data.bluetooth.BluetoothRepository
import app.mobilemanager.data.tracker.TrackerSettingsRepository
import app.mobilemanager.data.tracker.TrackerVpnController
import app.mobilemanager.data.security.PackageInstallMonitor
import app.mobilemanager.data.security.SimMonitor
import app.mobilemanager.data.work.AppNotifications
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch { packageMonitor.check() }
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
            IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch { batteryMonitor.observe().collect { batteryGuard.evaluate(it) } }
        scope.launch {
            packageMonitor.check()
            simMonitor.check()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, GuardService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardService::class.java))
        }
    }
}

/** Nach Neustart/Update: Echtzeit-Wächter wieder starten und SIM prüfen. */
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
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, BootEntryPoint::class.java)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (entryPoint.guardSettings().settings.first().realtimeServiceEnabled) GuardService.start(context)
                entryPoint.simMonitor().check()
                // Phase 3: Tracker-Blocker und Fokus-/Lautstärke-Dienst wiederherstellen.
                if (entryPoint.trackerSettings().current().enabled) entryPoint.trackerVpn().start()
                entryPoint.extrasCoordinator().sync()
                // Verbundene Bluetooth-Geräte nach dem Neustart abgleichen (Launcher-Profilwechsel).
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) entryPoint.bluetoothRepository().refreshConnectedDevices()
            } finally {
                pending.finish()
            }
        }
    }
}
