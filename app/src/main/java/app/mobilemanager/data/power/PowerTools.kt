package app.mobilemanager.data.power

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.content.ComponentName
import android.view.View
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.mobilemanager.MainActivity
import app.mobilemanager.data.apps.ProtectedAppsProvider
import app.mobilemanager.data.shizuku.ShellResult
import app.mobilemanager.data.shizuku.ShizukuManager
import app.mobilemanager.data.work.AppNotifications
import app.mobilemanager.data.work.WorkScheduler
import app.mobilemanager.domain.extras.ShellCommandPolicy
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Eine (System-)App für den Bloatware-Manager. */
data class ManagedApp(val packageName: String, val label: String, val system: Boolean, val enabled: Boolean, val protected: Boolean)

/** Gruppen für „Rechte auf Zeit“. */
enum class TempPermissionGroup(val label: String, val permissions: List<String>) {
    LOCATION("Standort", listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")),
    CAMERA("Kamera", listOf("android.permission.CAMERA")),
    MICROPHONE("Mikrofon", listOf("android.permission.RECORD_AUDIO")),
}

/**
 * Phase 4.4: Shizuku-Power-Tools. Alle Befehle laufen über die Erlaubtliste des Shell-Dienstes
 * ([ShellCommandPolicy]); zusätzlich schützt [ProtectedAppsProvider] systemkritische Apps vor dem Deaktivieren.
 */
@Singleton
class PowerTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizuku: ShizukuManager,
    private val protectedApps: ProtectedAppsProvider,
    private val workScheduler: WorkScheduler,
) {
    private val pm: PackageManager get() = context.packageManager

    fun label(pkg: String): String = runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)

    /* ---------- App-Ops ---------- */

    /** Aktueller Modus einer App-Op, z. B. „allow“ oder „ignore“ (Ausgabe von `cmd appops get`). */
    suspend fun appOpMode(pkg: String, op: String): String? {
        val result = shizuku.exec("cmd", "appops", "get", pkg, op)
        if (!result.ok) return null
        return MODE.find(result.output)?.groupValues?.get(1) ?: if (result.output.contains("No operations")) "default" else null
    }

    suspend fun setAppOp(pkg: String, op: String, mode: String): ShellResult = shizuku.exec("cmd", "appops", "set", pkg, op, mode)

    /* ---------- Bloatware ---------- */

    suspend fun systemApps(): List<ManagedApp> = withContext(Dispatchers.IO) {
        val protectedSet = protectedApps.resolve().keys + CORE_PACKAGES + context.packageName
        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()))
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            .map { info ->
                ManagedApp(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString(),
                    system = true,
                    enabled = info.enabled,
                    protected = info.packageName in protectedSet || CORE_PREFIXES.any { info.packageName.startsWith(it) },
                )
            }
            .sortedWith(compareBy({ it.enabled }, { it.label.lowercase() }))
    }

    /** Deaktiviert für Nutzer 0 (umkehrbar, keine Deinstallation). */
    suspend fun setAppEnabled(app: ManagedApp, enabled: Boolean): ShellResult {
        if (!enabled && app.protected) return ShellResult(-2, "", "Geschützte App – Deaktivieren gesperrt")
        return if (enabled) {
            shizuku.exec("pm", "enable", "--user", "0", app.packageName)
        } else {
            shizuku.exec("pm", "disable-user", "--user", "0", app.packageName)
        }
    }

    /* ---------- Rechte auf Zeit ---------- */

    /** Vergibt die Berechtigungen der Gruppe, die die App deklariert, und plant den Entzug. */
    suspend fun grantTemporarily(pkg: String, group: TempPermissionGroup, minutes: Int): String? {
        val declared = runCatching {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                .requestedPermissions?.toSet().orEmpty()
        }.getOrDefault(emptySet())
        val permissions = group.permissions.filter { it in declared }
        if (permissions.isEmpty()) return "${label(pkg)} fragt diese Berechtigung gar nicht an"
        for (permission in permissions) {
            shizuku.exec("pm", "grant", pkg, permission).error?.let { return it }
        }
        workScheduler.scheduleRevoke(pkg, permissions, minutes)
        return null
    }

    suspend fun revokeNow(pkg: String, permissions: List<String>): String? {
        for (permission in permissions.filter { it in ShellCommandPolicy.TEMP_PERMISSIONS }) {
            val result = shizuku.exec("pm", "revoke", pkg, permission)
            if (!result.ok && runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess) return result.error
        }
        return null
    }

    /* ---------- System-Tweaks ---------- */

    suspend fun animationScale(): String? = shizuku.exec("settings", "get", "global", "window_animation_scale")
        .takeIf { it.ok }?.output?.trim()?.removeSuffix(".0")?.ifEmpty { "1" }?.let { if (it == "null") "1" else it }

    suspend fun setAnimationScale(value: String): String? {
        for (key in ShellCommandPolicy.ANIMATION_KEYS) {
            shizuku.exec("settings", "put", "global", key, value).error?.let { return it }
        }
        return null
    }

    suspend fun setNightMode(mode: String): String? = shizuku.exec("cmd", "uimode", "night", mode).error

    /** Lässt das System App-Caches verwerfen, bis (theoretisch) 999 GB frei sind – also alle löschbaren Caches. */
    suspend fun trimCaches(): String? = shizuku.exec("pm", "trim-caches", "999G").error

    companion object {
        private val MODE = Regex(":\\s*(allow|ignore|deny|default|foreground)")

        /** Nie deaktivierbar – sonst droht ein Bootloop oder ein unbedienbares Gerät. */
        val CORE_PACKAGES = setOf(
            "android", "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.shell",
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.permissioncontroller",
            "com.android.providers.settings", "com.android.providers.telephony", "com.android.providers.media.module",
            "com.google.android.providers.media.module", "com.android.networkstack", "com.google.android.networkstack",
            "com.android.bluetooth", "com.google.android.bluetooth", "com.android.nfc", "com.android.se",
            "com.google.android.packageinstaller", "com.android.packageinstaller", "com.android.server.telecom",
            "com.google.android.ext.services", "com.android.inputdevices", "com.android.keychain",
            "com.google.android.apps.nexuslauncher", "com.android.launcher3", "com.google.android.inputmethod.latin",
            "com.google.android.dialer", "com.android.emergency", "com.google.android.cellbroadcastreceiver",
            "com.android.wifi.resources", "com.google.android.wifi.resources", "com.android.location.fused",
        )
        private val CORE_PREFIXES = listOf("com.android.providers.", "com.android.internal.", "com.google.android.overlay.", "com.android.systemui.")
    }
}

/**
 * Phase 4.4: Koffein-Modus. Hält das Display für die gewählte Zeit an, ohne den globalen Bildschirm-Timeout zu
 * ändern: ein unsichtbares 1-Pixel-Overlay mit FLAG_KEEP_SCREEN_ON (keine Wakelock-Berechtigung nötig).
 */
@AndroidEntryPoint
class CaffeineService : Service() {

    @Inject lateinit var notifications: AppNotifications

    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private val stopRunnable = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val minutes = intent?.getIntExtra(EXTRA_MINUTES, 30) ?: 30
        val until = System.currentTimeMillis() + minutes * 60_000L
        ServiceCompat.startForeground(
            this,
            AppNotifications.ID_CAFFEINE,
            notifications.serviceNotification("Koffein-Modus", "Display bleibt $minutes Minuten an", MainActivity.DESTINATION_POWER_TOOLS),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (overlay == null && !showOverlay()) {
            stopSelf()
            return START_NOT_STICKY
        }
        activeUntil.value = until
        main.removeCallbacks(stopRunnable)
        main.postDelayed(stopRunnable, minutes * 60_000L)
        requestTileUpdate(this)
        return START_NOT_STICKY
    }

    private fun showOverlay(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        val view = View(this)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        )
        return runCatching { getSystemService(WindowManager::class.java).addView(view, params) }
            .onSuccess { overlay = view }
            .isSuccess
    }

    override fun onDestroy() {
        main.removeCallbacks(stopRunnable)
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
        activeUntil.value = 0
        requestTileUpdate(this)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.mobilemanager.caffeine.STOP"
        private const val EXTRA_MINUTES = "minutes"
        private val activeUntil = MutableStateFlow(0L)
        val until: StateFlow<Long> = activeUntil.asStateFlow()

        val isActive: Boolean get() = activeUntil.value > System.currentTimeMillis()

        /** @return false, wenn die Overlay-Berechtigung fehlt. */
        fun start(context: Context, minutes: Int): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            ContextCompat.startForegroundService(context, Intent(context, CaffeineService::class.java).putExtra(EXTRA_MINUTES, minutes))
            return true
        }

        fun stop(context: Context) {
            if (isActive) runCatching { context.startService(Intent(context, CaffeineService::class.java).setAction(ACTION_STOP)) }
        }

        private fun requestTileUpdate(context: Context) {
            runCatching { TileService.requestListeningState(context, ComponentName(context, app.mobilemanager.data.tiles.CaffeineTileService::class.java)) }
        }
    }
}
