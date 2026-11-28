package app.mobilemanager.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.mobilemanager.MainActivity
import app.mobilemanager.data.network.NetworkPolicyController
import app.mobilemanager.data.power.PowerTools
import app.mobilemanager.data.protection.PermissionDiffMonitor
import app.mobilemanager.data.protection.ProtectionWatchdog
import app.mobilemanager.data.tracker.TrackerBlocklistRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Phase 4: 15-Minuten-Check für Watchdog, Berechtigungs-Diff, Firewall-Regeln und Datenlimit. */
@HiltWorker
class ProtectionCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val watchdog: ProtectionWatchdog,
    private val permissionDiff: PermissionDiffMonitor,
    private val networkPolicy: NetworkPolicyController,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = guardedWork(runAttemptCount) {
        runCatching { networkPolicy.periodicCheck() }
        runCatching { permissionDiff.check() }
        runCatching { watchdog.check() }
        return Result.success()
    }
}

/** Phase 4.1: abonnierte Blocklisten aktualisieren (wöchentlich bzw. auf Knopfdruck, nur mit Internet). */
@HiltWorker
class BlocklistUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val blocklist: TrackerBlocklistRepository,
    private val notifications: AppNotifications,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = guardedWork(runAttemptCount) {
        val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val result = blocklist.updateSubscriptions(http)
        if (result.isFailure) {
            if (runAttemptCount < 3) return Result.retry()
            notifications.showInfo(
                AppNotifications.ID_BLOCKLIST,
                "Blocklisten-Update fehlgeschlagen",
                result.exceptionOrNull()?.message ?: "Unbekannter Fehler",
                MainActivity.DESTINATION_TRACKER,
            )
        }
        return Result.success()
    }
}

/** Phase 4.4: „Rechte auf Zeit“ – entzieht die vorübergehend erteilten Berechtigungen wieder. */
@HiltWorker
class TempPermissionRevokeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val powerTools: PowerTools,
    private val notifications: AppNotifications,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = guardedWork(runAttemptCount) {
        val pkg = inputData.getString(KEY_PACKAGE) ?: return Result.failure()
        val permissions = inputData.getNullableStringArray(KEY_PERMISSIONS)?.filterNotNull().orEmpty()
        val error = powerTools.revokeNow(pkg, permissions)
        if (error != null) {
            // Shizuku läuft gerade nicht – später erneut versuchen und Bescheid geben.
            if (runAttemptCount == 0) {
                notifications.showInfo(
                    AppNotifications.ID_TEMP_PERMISSION,
                    "Berechtigung konnte nicht entzogen werden",
                    "${powerTools.label(pkg)}: $error. MobileManager versucht es erneut, sobald Shizuku läuft.",
                    MainActivity.DESTINATION_POWER_TOOLS,
                )
            }
            return Result.retry()
        }
        return Result.success()
    }

    companion object {
        const val KEY_PACKAGE = "package"
        const val KEY_PERMISSIONS = "permissions"
    }
}
