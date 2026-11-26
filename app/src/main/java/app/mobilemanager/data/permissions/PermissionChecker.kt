package app.mobilemanager.data.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import app.mobilemanager.data.forcestop.ForceStopEngine
import app.mobilemanager.data.shizuku.ShizukuManager
import app.mobilemanager.data.shizuku.ShizukuState
import app.mobilemanager.domain.model.GrantState
import app.mobilemanager.domain.model.PermissionKind
import app.mobilemanager.domain.model.PermissionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Prüft den Status aller Berechtigungen und liefert die passenden Settings-Intents. */
@Singleton
class PermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val forceStopEngine: ForceStopEngine,
) {
    fun statusList(): List<PermissionStatus> {
        shizukuManager.refresh()
        return listOf(
            status(
                PermissionKind.USAGE_ACCESS, "Nutzungszugriff",
                "Erkennt, wann Apps zuletzt im Vordergrund waren (App-Stopper).",
                hasUsageAccess(), required = true,
            ),
            mediaStatus(),
            status(
                PermissionKind.BLUETOOTH_CONNECT, "Bluetooth (Geräte in der Nähe)",
                "Erkennt Verbindungen zu Auto und Kopfhörern.",
                isGranted(Manifest.permission.BLUETOOTH_CONNECT), required = true,
            ),
            status(
                PermissionKind.OVERLAY, "Über anderen Apps einblenden",
                "Erlaubt das Starten von Spotify & Co. aus dem Hintergrund.",
                Settings.canDrawOverlays(context), required = true,
            ),
            status(
                PermissionKind.BATTERY_OPTIMIZATION, "Akku-Optimierung ignorieren",
                "Damit geplante Scans und Bluetooth-Regeln zuverlässig laufen.",
                isIgnoringBatteryOptimizations(), required = true,
            ),
            status(
                PermissionKind.POST_NOTIFICATIONS, "Benachrichtigungen",
                "Meldet Ergebnisse automatischer Aufräumvorgänge.",
                isGranted(Manifest.permission.POST_NOTIFICATIONS), required = false,
            ),
            shizukuStatus(),
            status(
                PermissionKind.ACCESSIBILITY, "Bedienungshilfe (Fallback)",
                "Nur nötig ohne Shizuku: tippt automatisch auf „Beenden erzwingen“. " +
                    "Bei per APK installierten Apps zuerst in den App-Infos ⋮ → " +
                    "„Eingeschränkte Einstellungen zulassen“ wählen.",
                forceStopEngine.isAccessibilityServiceEnabled(), required = false,
            ),
        )
    }

    fun isGranted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    @Suppress("DEPRECATION") // unsafeCheckOpNoThrow ist der einzige öffentliche Weg, den AppOp-Modus zu lesen
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            isGranted(Manifest.permission.PACKAGE_USAGE_STATS)
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun mediaAccessState(): GrantState = when {
        isGranted(Manifest.permission.READ_MEDIA_IMAGES) -> GrantState.GRANTED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> GrantState.PARTIAL
        else -> GrantState.DENIED
    }

    fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Settings-Intent für Berechtigungen, die nicht per Runtime-Dialog vergeben werden. */
    @SuppressLint("BatteryLife") // Sideloading-App, Play-Richtlinie nicht relevant
    fun settingsIntent(kind: PermissionKind): Intent? {
        val pkgUri = Uri.fromParts("package", context.packageName, null)
        return when (kind) {
            PermissionKind.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri)
            PermissionKind.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
            PermissionKind.BATTERY_OPTIMIZATION ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
            PermissionKind.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            PermissionKind.READ_MEDIA_IMAGES,
            PermissionKind.BLUETOOTH_CONNECT,
            PermissionKind.POST_NOTIFICATIONS,
            -> appDetailsIntent()
            PermissionKind.SHIZUKU -> null
        }
    }

    /** Fallback ohne Paket-URI (manche Seiten akzeptieren keine Daten-URI). */
    fun fallbackIntent(kind: PermissionKind): Intent? = when (kind) {
        PermissionKind.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        PermissionKind.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        PermissionKind.BATTERY_OPTIMIZATION -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else -> appDetailsIntent()
    }

    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    private fun mediaStatus(): PermissionStatus {
        val state = mediaAccessState()
        return PermissionStatus(
            kind = PermissionKind.READ_MEDIA_IMAGES,
            title = "Fotos & Bilder",
            description = "Findet Duplikate und alte Fotos ohne Personen.",
            state = state,
            stateLabel = when (state) {
                GrantState.GRANTED -> "Aktiv"
                GrantState.PARTIAL -> "Nur ausgewählte Fotos"
                GrantState.DENIED -> "Fehlt"
            },
            required = true,
        )
    }

    private fun shizukuStatus(): PermissionStatus {
        val state = shizukuManager.state.value
        return PermissionStatus(
            kind = PermissionKind.SHIZUKU,
            title = "Shizuku",
            description = "Führt „Beenden erzwingen“ ohne Root im Hintergrund aus.",
            state = when (state) {
                ShizukuState.READY -> GrantState.GRANTED
                ShizukuState.NO_PERMISSION -> GrantState.PARTIAL
                else -> GrantState.DENIED
            },
            stateLabel = when (state) {
                ShizukuState.READY -> "Aktiv"
                ShizukuState.NO_PERMISSION -> "Läuft – Berechtigung fehlt"
                ShizukuState.NOT_RUNNING -> "Dienst läuft nicht (nach Neustart in Shizuku starten)"
                ShizukuState.NOT_INSTALLED -> "Nicht installiert"
            },
            required = true,
        )
    }

    private fun status(
        kind: PermissionKind,
        title: String,
        description: String,
        granted: Boolean,
        required: Boolean,
    ) = PermissionStatus(
        kind = kind,
        title = title,
        description = description,
        state = if (granted) GrantState.GRANTED else GrantState.DENIED,
        stateLabel = if (granted) "Aktiv" else if (required) "Fehlt" else "Optional",
        required = required,
    )
}
