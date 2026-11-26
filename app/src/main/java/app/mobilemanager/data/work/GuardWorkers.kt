package app.mobilemanager.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.mobilemanager.data.battery.BatteryGuard
import app.mobilemanager.data.battery.BatteryMonitor
import app.mobilemanager.data.battery.NightStandbyReporter
import app.mobilemanager.data.guard.GuardSettingsRepository
import app.mobilemanager.data.local.NotificationEntryDao
import app.mobilemanager.data.media.ExpiredFilesRepository
import app.mobilemanager.data.notifications.NotificationRepository
import app.mobilemanager.data.security.PackageInstallMonitor
import app.mobilemanager.data.security.SecurityEventRepository
import app.mobilemanager.data.security.SimMonitor
import app.mobilemanager.domain.model.ExpiredFileKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** 15-Minuten-Check: neue Apps (Sideloading), SIM-Status, Akku-Temperatur/Ladestand. */
@HiltWorker
class GuardCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val packageMonitor: PackageInstallMonitor,
    private val simMonitor: SimMonitor,
    private val batteryMonitor: BatteryMonitor,
    private val batteryGuard: BatteryGuard,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { packageMonitor.check() }
        runCatching { simMonitor.check() }
        batteryMonitor.current()?.let { runCatching { batteryGuard.evaluate(it) } }
        return Result.success()
    }
}

/**
 * Offline-Warteschlange (Meilenstein 2.2.3): läuft erst mit Internet. Im Flugmodus wartet
 * WorkManager automatisch und sendet, sobald WLAN/Mobilfunk wieder da ist.
 */
@HiltWorker
class AlertSendWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val securityEvents: SecurityEventRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val eventId = inputData.getLong(KEY_EVENT_ID, -1)
        if (eventId < 0) return Result.failure()
        val finalAttempt = runAttemptCount >= MAX_ATTEMPTS
        return when {
            securityEvents.deliver(eventId, finalAttempt) -> Result.success()
            finalAttempt -> Result.failure()
            else -> Result.retry()
        }
    }

    companion object {
        const val KEY_EVENT_ID = "event_id"
        const val MAX_ATTEMPTS = 10
    }
}

/** Wöchentlich: alte Screenshots und Downloads melden. */
@HiltWorker
class ExpiredFilesWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: ExpiredFilesRepository,
    private val settings: GuardSettingsRepository,
    private val notifications: AppNotifications,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val files = repository.findExpired(settings.current().expiredFilesAgeDays)
        settings.setExpiredFilesCount(files.size)
        val screenshots = files.count { repository.kindOf(it) == ExpiredFileKind.SCREENSHOT }
        val downloads = files.size - screenshots
        if (files.isNotEmpty()) notifications.showExpiredFiles(screenshots, downloads)
        return Result.success()
    }
}

/** Täglich: Archiv-Einträge älter als 7 Tage löschen. */
@HiltWorker
class NotificationCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: NotificationRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        repository.purgeOld()
        return Result.success()
    }
}

/** Zu den Digest-Uhrzeiten: eine Sammel-Benachrichtigung mit allen abgefangenen Meldungen. */
@HiltWorker
class DigestWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: NotificationEntryDao,
    private val settings: GuardSettingsRepository,
    private val notifications: AppNotifications,
    private val scheduler: WorkScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pending = dao.pendingDigest()
        if (pending.isNotEmpty()) {
            notifications.showDigest(pending.map { "${it.appLabel}: ${it.title.ifBlank { it.text }}" })
            dao.markDelivered(pending.map { it.id })
        }
        scheduler.scheduleDigest(settings.current())
        return Result.success()
    }
}

/** Nachtruhe-Beginn und -Ende: Akkustand merken bzw. Bericht erstellen. */
@HiltWorker
class NightStandbyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reporter: NightStandbyReporter,
    private val settings: GuardSettingsRepository,
    private val scheduler: WorkScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (inputData.getBoolean(KEY_IS_START, true)) reporter.onNightStart() else reporter.onNightEnd()
        scheduler.scheduleNightReport(settings.current())
        return Result.success()
    }

    companion object {
        const val KEY_IS_START = "is_start"
    }
}
