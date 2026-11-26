package app.mobilemanager.data.security

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserHandle
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import app.mobilemanager.data.guard.GuardSettingsRepository
import app.mobilemanager.domain.guard.FailedUnlockPolicy
import app.mobilemanager.domain.guard.SimPolicy
import app.mobilemanager.domain.guard.SimSnapshot
import app.mobilemanager.domain.model.SecurityEventType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SecurityEntryPoint {
    fun securityEvents(): SecurityEventRepository
    fun guardSettings(): GuardSettingsRepository
    fun simMonitor(): SimMonitor
    fun deviceAdmin(): DeviceAdminHelper
}

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** goAsync erlaubt ca. 10 s; großzügig darunter bleiben. */
private const val MAX_RECEIVER_RUNTIME_MS = 8_000L

private fun BroadcastReceiver.runAsync(context: Context, block: suspend (SecurityEntryPoint) -> Unit) {
    val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, SecurityEntryPoint::class.java)
    val pending = goAsync()
    receiverScope.launch {
        try {
            withTimeoutOrNull(MAX_RECEIVER_RUNTIME_MS) { block(entryPoint) }
        } finally {
            pending.finish()
        }
    }
}

/**
 * Meilenstein 2.1.1: Geräteadministrator mit `<watch-login />`. Android zählt die
 * Fehlversuche selbst (und setzt sie bei Erfolg zurück) – wir lesen den Zähler nur aus.
 */
class SecurityAdminReceiver : DeviceAdminReceiver() {

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        runAsync(context) { ep ->
            val attempts = ep.deviceAdmin().failedAttempts()
            val threshold = ep.guardSettings().current().failedUnlockThreshold
            if (FailedUnlockPolicy.shouldAlert(attempts, threshold)) {
                ep.securityEvents().record(
                    SecurityEventType.FAILED_UNLOCK,
                    "$attempts falsche Entsperrversuche",
                    "Auf dem Sperrbildschirm wurde $attempts-mal eine falsche PIN, ein falsches Muster oder Passwort eingegeben.",
                )
            }
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Ohne Geräteadministrator erkennt MobileManager keine falschen PIN-Eingaben mehr."

    override fun onDisabled(context: Context, intent: Intent) {
        runAsync(context) { ep ->
            ep.securityEvents().record(
                SecurityEventType.DEVICE_ADMIN_DISABLED,
                "Diebstahl-Erkennung deaktiviert",
                "Der Geräteadministrator von MobileManager wurde ausgeschaltet.",
            )
        }
    }
}

@Singleton
class DeviceAdminHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dpm get() = context.getSystemService(DevicePolicyManager::class.java)
    val component = ComponentName(context, SecurityAdminReceiver::class.java)

    fun isActive(): Boolean = dpm?.isAdminActive(component) == true

    fun failedAttempts(): Int = runCatching { dpm?.currentFailedPasswordAttempts ?: 0 }.getOrDefault(0)

    fun activationIntent(): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
        .putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "MobileManager liest nur den Zähler falscher Entsperrversuche aus, um dich bei einem Diebstahl zu warnen. " +
                "Vor dem Deinstallieren bitte in der App unter Wächter → Security deaktivieren.",
        )

    /** Deaktivieren direkt aus der App – sonst lässt sich die App nicht deinstallieren. */
    fun deactivate() {
        if (isActive()) dpm?.removeActiveAdmin(component)
    }
}

/**
 * Meilenstein 2.1.3: SIM-Überwachung ohne sensible Kennungen. Ausgelöst durch
 * CARRIER_CONFIG_CHANGED (vom Implicit-Broadcast-Verbot ausgenommen), Boot und den periodischen Check.
 */
@Singleton
class SimMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val guardSettings: GuardSettingsRepository,
    private val securityEvents: SecurityEventRepository,
) {
    private val mutex = Mutex()

    fun snapshot(): SimSnapshot {
        val tm = context.getSystemService(TelephonyManager::class.java)
        return SimSnapshot(
            state = tm?.simState ?: TelephonyManager.SIM_STATE_UNKNOWN,
            carrierId = tm?.simCarrierId ?: TelephonyManager.UNKNOWN_CARRIER_ID,
            subscriptionId = SubscriptionManager.getDefaultSubscriptionId(),
        )
    }

    fun describe(s: SimSnapshot): String = when (s.state) {
        TelephonyManager.SIM_STATE_ABSENT -> "Keine SIM-Karte"
        TelephonyManager.SIM_STATE_READY ->
            "SIM bereit (${context.getSystemService(TelephonyManager::class.java)?.simCarrierIdName ?: "Netz unbekannt"})"
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "SIM-PIN erforderlich"
        else -> "SIM wird geladen"
    }

    /** Aktuellen (stabilen) Zustand als "normal" übernehmen. */
    suspend fun adoptCurrentAsBaseline(): Boolean {
        val current = snapshot()
        if (!SimPolicy.isStable(current)) return false
        guardSettings.setSimBaseline(current.encode())
        return true
    }

    suspend fun check() = mutex.withLock {
        if (!guardSettings.current().simWatchEnabled) return@withLock
        val current = snapshot()
        val baseline = SimSnapshot.decode(guardSettings.simBaseline())
        if (baseline == null) {
            if (SimPolicy.isStable(current)) guardSettings.setSimBaseline(current.encode())
            return@withLock
        }
        if (!SimPolicy.isSuspicious(baseline, current)) return@withLock
        // Denselben Abweichungszustand nur einmal melden.
        if (guardSettings.simLastAlerted() == current.encode()) return@withLock
        guardSettings.setSimLastAlerted(current.encode())
        securityEvents.record(
            SecurityEventType.SIM_CHANGED,
            if (current.state == TelephonyManager.SIM_STATE_ABSENT) "SIM-Karte entfernt" else "Andere SIM-Karte erkannt",
            "Vorher: ${describe(baseline)}. Jetzt: ${describe(current)}.",
        )
    }
}

class SimChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED) return
        runAsync(context) { ep -> ep.simMonitor().check() }
    }
}

/**
 * Meilenstein 2.1.2: Neue App-Installationen. ACTION_PACKAGE_ADDED wird seit Android 8 nicht mehr
 * an Manifest-Receiver zugestellt – daher `getChangedPackages()` (periodisch und live im Wächter-Dienst).
 */
@Singleton
class PackageInstallMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val guardSettings: GuardSettingsRepository,
    private val securityEvents: SecurityEventRepository,
) {
    private val mutex = Mutex()
    private val pm: PackageManager get() = context.packageManager

    suspend fun check() = mutex.withLock {
        val now = System.currentTimeMillis()
        val checkpoint = guardSettings.packageCheckpoint()
        val changed = pm.getChangedPackages(checkpoint?.first ?: 0)
        if (checkpoint == null) {
            // Erster Lauf: nur den Stand merken, nichts melden.
            guardSettings.setPackageCheckpoint(changed?.sequenceNumber ?: 0, now)
            return@withLock
        }
        if (changed == null) return@withLock
        if (guardSettings.current().sideloadWatchEnabled) {
            changed.packageNames
                .filter { it != context.packageName }
                .forEach { pkg -> inspect(pkg, since = checkpoint.second) }
        }
        guardSettings.setPackageCheckpoint(changed.sequenceNumber, now)
    }

    private suspend fun inspect(packageName: String, since: Long) {
        val info = runCatching { pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)) }.getOrNull()
            ?: return // deinstalliert
        // Nur Neuinstallationen seit dem letzten Check, keine Updates.
        if (info.firstInstallTime < since || info.firstInstallTime != info.lastUpdateTime) return
        val appInfo = info.applicationInfo ?: return
        if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) return
        val source = runCatching { pm.getInstallSourceInfo(packageName) }.getOrNull()
        val installer = source?.installingPackageName ?: source?.initiatingPackageName
        if (installer in TRUSTED_INSTALLERS) return
        val label = appInfo.loadLabel(pm).toString()
        val installerLabel = installer?.let { name ->
            runCatching {
                pm.getApplicationInfo(name, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm).toString()
            }.getOrDefault(name)
        } ?: "unbekannt (z. B. per ADB)"
        securityEvents.record(
            SecurityEventType.SIDELOAD_INSTALL,
            "Neue App außerhalb des Play Stores: $label",
            "Paket: $packageName · Installiert über: $installerLabel",
        )
    }

    private companion object {
        val TRUSTED_INSTALLERS = setOf("com.android.vending")
    }
}
