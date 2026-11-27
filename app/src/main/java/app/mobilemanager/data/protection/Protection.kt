package app.mobilemanager.data.protection

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.mobilemanager.MainActivity
import app.mobilemanager.data.extras.AppSwitchAccessibilityService
import app.mobilemanager.data.extras.ForegroundAppTracker
import app.mobilemanager.data.guard.GuardSettingsRepository
import app.mobilemanager.data.locker.LockerRepository
import app.mobilemanager.data.permissions.PermissionChecker
import app.mobilemanager.data.security.SecurityAdminReceiver
import app.mobilemanager.data.security.SecurityEventRepository
import app.mobilemanager.data.tracker.TrackerSettingsRepository
import app.mobilemanager.data.tracker.TrackerVpnState
import app.mobilemanager.data.work.AppNotifications
import app.mobilemanager.domain.extras.PermissionDiffPolicy
import app.mobilemanager.domain.model.SecurityEventType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.protectionStore by preferencesDataStore(name = "protection_settings")

/** Phase 4.3/4.5/4.6: Selbstschutz, Datenschutz-Wächter und externe Steuerung. */
data class ProtectionSettings(
    val watchdogEnabled: Boolean = false,
    val permissionDiffEnabled: Boolean = false,
    val sensorLogEnabled: Boolean = false,
    val clipboardClearOnScreenOff: Boolean = false,
    val usbWarning: Boolean = false,
    val externalControl: Boolean = false,
    val caffeineMinutes: Int = 30,
) {
    /** Diese Wächter brauchen den Echtzeit-Dienst (Live-Broadcasts). */
    val needsRealtime: Boolean get() = sensorLogEnabled || clipboardClearOnScreenOff || usbWarning
}

@Singleton
class ProtectionRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val WATCHDOG = booleanPreferencesKey("watchdog")
        val PERMISSION_DIFF = booleanPreferencesKey("permission_diff")
        val SENSOR_LOG = booleanPreferencesKey("sensor_log")
        val CLIPBOARD = booleanPreferencesKey("clipboard_screen_off")
        val USB = booleanPreferencesKey("usb_warning")
        val EXTERNAL = booleanPreferencesKey("external_control")
        val CAFFEINE = intPreferencesKey("caffeine_minutes")
        val REPORTED = stringSetPreferencesKey("watchdog_reported")
    }

    val settings: Flow<ProtectionSettings> = context.protectionStore.data.map(::fromPrefs)

    suspend fun current(): ProtectionSettings = settings.first()

    suspend fun update(transform: (ProtectionSettings) -> ProtectionSettings) {
        context.protectionStore.edit { p ->
            val n = transform(fromPrefs(p))
            p[Keys.WATCHDOG] = n.watchdogEnabled
            p[Keys.PERMISSION_DIFF] = n.permissionDiffEnabled
            p[Keys.SENSOR_LOG] = n.sensorLogEnabled
            p[Keys.CLIPBOARD] = n.clipboardClearOnScreenOff
            p[Keys.USB] = n.usbWarning
            p[Keys.EXTERNAL] = n.externalControl
            p[Keys.CAFFEINE] = n.caffeineMinutes
        }
    }

    suspend fun reportedProblems(): Set<String> = context.protectionStore.data.first()[Keys.REPORTED] ?: emptySet()

    suspend fun setReportedProblems(value: Set<String>) = context.protectionStore.edit { it[Keys.REPORTED] = value }

    private fun fromPrefs(p: Preferences) = ProtectionSettings(
        watchdogEnabled = p[Keys.WATCHDOG] ?: false,
        permissionDiffEnabled = p[Keys.PERMISSION_DIFF] ?: false,
        sensorLogEnabled = p[Keys.SENSOR_LOG] ?: false,
        clipboardClearOnScreenOff = p[Keys.CLIPBOARD] ?: false,
        usbWarning = p[Keys.USB] ?: false,
        externalControl = p[Keys.EXTERNAL] ?: false,
        caffeineMinutes = p[Keys.CAFFEINE] ?: 30,
    )
}

/**
 * Phase 4.3: gegenseitiger Watchdog. Prüft im 15-Minuten-Takt (und beim Start des Echtzeit-Wächters), ob alle
 * eingeschalteten Schutzfunktionen noch laufen. Neue Probleme landen im Manipulations-Protokoll; behobene werden
 * still zurückgesetzt, damit jede Unterbrechung genau einmal gemeldet wird.
 */
@Singleton
class ProtectionWatchdog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ProtectionRepository,
    private val locker: LockerRepository,
    private val tracker: TrackerSettingsRepository,
    private val guard: GuardSettingsRepository,
    private val permissions: PermissionChecker,
    private val notificationRepository: app.mobilemanager.data.notifications.NotificationRepository,
    private val securityEvents: SecurityEventRepository,
) {
    data class Problem(val key: String, val text: String)

    suspend fun problems(): List<Problem> = buildList {
        val lock = locker.current()
        if (lock.active && !AppSwitchAccessibilityService.isEnabled(context) && !permissions.hasUsageAccess()) {
            add(Problem("a11y", "App-Sperre aktiv, aber weder Bedienungshilfe noch Nutzungszugriff erlaubt"))
        } else if (lock.active && !AppSwitchAccessibilityService.isEnabled(context)) {
            add(Problem("a11y_soft", "App-Sperre läuft nur über Nutzungsdaten – Bedienungshilfe wurde deaktiviert"))
        }
        if (lock.enabled && lock.hideNotifications && !notificationRepository.isListenerEnabled()) {
            add(Problem("listener", "Benachrichtigungszugriff entzogen – Inhalte gesperrter Apps sind sichtbar"))
        }
        if (tracker.current().enabled && !TrackerVpnState.running.value) {
            add(Problem("vpn", "Tracker-Blocker ist eingeschaltet, das VPN läuft aber nicht"))
        }
        if (guard.current().realtimeServiceEnabled && !isGuardServiceRunning()) {
            add(Problem("guard", "Echtzeit-Wächter wurde beendet"))
        }
    }

    private fun isGuardServiceRunning(): Boolean = app.mobilemanager.data.guard.GuardService.running

    suspend fun check() {
        if (!repository.current().watchdogEnabled) return
        val current = problems()
        val reported = repository.reportedProblems()
        val fresh = current.filter { it.key !in reported }
        fresh.forEach { problem ->
            securityEvents.record(SecurityEventType.PROTECTION_INTERRUPTED, "Schutzfunktion unterbrochen", problem.text)
        }
        repository.setReportedProblems(current.map { it.key }.toSet())
    }
}

/** Phase 4.3 Stufe 2: Maximal-Härtung, nur wenn MobileManager per ADB als Device Owner eingerichtet wurde. */
@Singleton
class DeviceOwnerController @Inject constructor(@ApplicationContext private val context: Context) {
    private val dpm get() = context.getSystemService(DevicePolicyManager::class.java)
    private val admin get() = ComponentName(context, SecurityAdminReceiver::class.java)

    val setupCommand: String get() = "adb shell dpm set-device-owner ${context.packageName}/${SecurityAdminReceiver::class.java.name}"

    fun isDeviceOwner(): Boolean = dpm?.isDeviceOwnerApp(context.packageName) == true

    fun isUninstallBlocked(): Boolean = runCatching { dpm.isUninstallBlocked(admin, context.packageName) }.getOrDefault(false)

    fun setUninstallBlocked(blocked: Boolean): Result<Unit> = runCatching {
        dpm.setUninstallBlocked(admin, context.packageName, blocked)
    }

    fun hasRestriction(key: String): Boolean = runCatching { dpm.getUserRestrictions(admin).getBoolean(key) }.getOrDefault(false)

    fun setRestriction(key: String, enabled: Boolean): Result<Unit> = runCatching {
        if (enabled) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key)
    }

    fun alwaysOnVpnLocked(): Boolean = runCatching {
        dpm.getAlwaysOnVpnPackage(admin) == context.packageName && dpm.isAlwaysOnVpnLockdownEnabled(admin)
    }.getOrDefault(false)

    /** Erzwingt das Tracker-VPN systemseitig („Verbindungen ohne VPN blockieren“). */
    fun setAlwaysOnVpn(locked: Boolean): Result<Unit> = runCatching {
        dpm.setAlwaysOnVpnPackage(admin, if (locked) context.packageName else null, locked)
    }

    companion object {
        val RESTRICTIONS = listOf(
            UserManager.DISALLOW_SAFE_BOOT to "Abgesicherten Modus blockieren",
            UserManager.DISALLOW_APPS_CONTROL to "„Beenden erzwingen“ & „Daten löschen“ für alle Apps sperren",
            UserManager.DISALLOW_DEBUGGING_FEATURES to "USB-Debugging sperren (beendet auch Shizuku!)",
        )
    }
}

/**
 * Phase 4.5: Update- & Berechtigungs-Diff. Merkt sich pro App die angeforderten kritischen Rechte und meldet,
 * wenn ein Update neue hinzufügt (z. B. Standort im Hintergrund oder eine Bedienungshilfe).
 */
@Singleton
class PermissionDiffMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ProtectionRepository,
    private val securityEvents: SecurityEventRepository,
) {
    private val file get() = File(context.filesDir, "permission_snapshot.json")
    private val mutex = Mutex()

    suspend fun check() = mutex.withLock {
        if (!repository.current().permissionDiffEnabled) return@withLock
        val now = withContext(Dispatchers.IO) { snapshot() }
        val before = withContext(Dispatchers.IO) { load() }
        if (before != null) {
            PermissionDiffPolicy.added(before, now).forEach { (pkg, perms) ->
                securityEvents.record(
                    SecurityEventType.PERMISSION_ADDED,
                    "${label(pkg)}: neue Berechtigungen",
                    "Nach einem Update verlangt die App zusätzlich: ${perms.joinToString { CRITICAL[it] ?: it }}",
                )
            }
        }
        withContext(Dispatchers.IO) { save(now) }
    }

    private fun snapshot(): Map<String, Set<String>> {
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of((PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES).toLong())
        return pm.getInstalledPackages(flags)
            .filter { it.applicationInfo?.flags?.and(android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != context.packageName }
            .associate { it.packageName to critical(it) }
    }

    private fun critical(info: PackageInfo): Set<String> = buildSet {
        info.requestedPermissions?.filter { it in CRITICAL }?.let(::addAll)
        info.services?.forEach { service ->
            when (service.permission) {
                "android.permission.BIND_ACCESSIBILITY_SERVICE" -> add(ACCESSIBILITY)
                "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" -> add(NOTIFICATION_LISTENER)
                "android.permission.BIND_DEVICE_ADMIN" -> add(DEVICE_ADMIN)
            }
        }
    }

    private fun load(): Map<String, Set<String>>? = runCatching {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        json.keys().asSequence().associateWith { key ->
            val array = json.getJSONArray(key)
            (0 until array.length()).map(array::getString).toSet()
        }
    }.getOrNull()

    private fun save(data: Map<String, Set<String>>) {
        val json = JSONObject()
        data.forEach { (pkg, perms) -> json.put(pkg, JSONArray(perms.toList())) }
        file.writeText(json.toString())
    }

    private fun label(pkg: String) = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString()
    }.getOrDefault(pkg)

    companion object {
        private const val ACCESSIBILITY = "accessibility_service"
        private const val NOTIFICATION_LISTENER = "notification_listener"
        private const val DEVICE_ADMIN = "device_admin"
        val CRITICAL = mapOf(
            "android.permission.ACCESS_BACKGROUND_LOCATION" to "Standort im Hintergrund",
            "android.permission.SYSTEM_ALERT_WINDOW" to "Über anderen Apps einblenden",
            "android.permission.CAMERA" to "Kamera",
            "android.permission.RECORD_AUDIO" to "Mikrofon",
            "android.permission.READ_SMS" to "SMS lesen",
            "android.permission.READ_CONTACTS" to "Kontakte",
            "android.permission.READ_CALL_LOG" to "Anrufliste",
            "android.permission.REQUEST_INSTALL_PACKAGES" to "Apps installieren",
            "android.permission.MANAGE_EXTERNAL_STORAGE" to "Alle Dateien",
            "android.permission.QUERY_ALL_PACKAGES" to "Alle Apps sehen",
            ACCESSIBILITY to "Bedienungshilfe",
            NOTIFICATION_LISTENER to "Benachrichtigungszugriff",
            DEVICE_ADMIN to "Geräteadministrator",
        )
    }
}

/** Ein Eintrag im Sensor-Protokoll. */
data class SensorEvent(val time: Long, val sensor: String, val app: String?, val screenOff: Boolean)

/**
 * Phase 4.5: Sensoren-Protokoll. Android verrät normalen Apps nicht, *welche* App Kamera oder Mikrofon nutzt;
 * MobileManager hält deshalb fest, wann der Sensor belegt wurde, welche App gerade im Vordergrund war und ob
 * das Display aus war (verdächtig). Nur lokal, die letzten 200 Einträge.
 */
@Singleton
class SensorLog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val foreground: ForegroundAppTracker,
) {
    private val file get() = File(context.filesDir, "sensor_log.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val _events = MutableStateFlow<List<SensorEvent>>(emptyList())
    val events: StateFlow<List<SensorEvent>> = _events.asStateFlow()
    private var started = false
    private var recording = false

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) = log("Kamera $cameraId")
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val active = configs.isNotEmpty()
            if (active && !recording) log("Mikrofon")
            recording = active
        }
    }

    init {
        scope.launch { _events.value = load() }
    }

    fun start() {
        if (started) return
        started = true
        runCatching { context.getSystemService(CameraManager::class.java)?.registerAvailabilityCallback(cameraCallback, main) }
        runCatching { context.getSystemService(AudioManager::class.java)?.registerAudioRecordingCallback(recordingCallback, main) }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.getSystemService(CameraManager::class.java)?.unregisterAvailabilityCallback(cameraCallback) }
        runCatching { context.getSystemService(AudioManager::class.java)?.unregisterAudioRecordingCallback(recordingCallback) }
    }

    private fun log(sensor: String) {
        val screenOff = context.getSystemService(PowerManager::class.java)?.isInteractive == false
        val event = SensorEvent(System.currentTimeMillis(), sensor, foreground.currentPackage, screenOff)
        // Mehrere Kameras melden sich beim Öffnen gleichzeitig – innerhalb von 3 s nur ein Eintrag je Sensorart.
        val last = _events.value.firstOrNull()
        if (last != null && event.time - last.time < 3_000 && last.sensor.take(6) == sensor.take(6)) return
        _events.value = (listOf(event) + _events.value).take(MAX_EVENTS)
        scope.launch { save(_events.value) }
    }

    fun clear() {
        _events.value = emptyList()
        scope.launch { file.delete() }
    }

    private fun load(): List<SensorEvent> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            SensorEvent(o.getLong("t"), o.getString("s"), o.optString("a").ifEmpty { null }, o.optBoolean("o"))
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<SensorEvent>) {
        val array = JSONArray()
        list.forEach { e -> array.put(JSONObject().put("t", e.time).put("s", e.sensor).put("a", e.app ?: "").put("o", e.screenOff)) }
        runCatching { file.writeText(array.toString()) }
    }

    private companion object {
        const val MAX_EVENTS = 200
    }
}

/** Phase 4.5: USB-Daten-Schutz – Warnung, wenn das Gerät an USB hängt und ADB aktiv ist (Juice-Jacking). */
@Singleton
class UsbGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifications: AppNotifications,
) {
    fun onPowerConnected() {
        val battery = ContextCompat.registerReceiver(
            context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        if (plugged != BatteryManager.BATTERY_PLUGGED_USB) return
        val adb = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        if (!adb) return
        notifications.showInfo(
            AppNotifications.ID_USB,
            "USB-Debugging an USB-Anschluss aktiv",
            "Das Gerät hängt an einem USB-Datenanschluss, während USB-Debugging eingeschaltet ist. " +
                "An fremden Ladestationen oder PCs besser ausschalten oder ein reines Ladekabel nutzen.",
            MainActivity.DESTINATION_PROTECTION,
        )
    }
}
