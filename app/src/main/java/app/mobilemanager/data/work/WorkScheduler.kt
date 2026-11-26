package app.mobilemanager.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.mobilemanager.data.guard.GuardSettings
import app.mobilemanager.data.settings.AppSettings
import app.mobilemanager.domain.guard.Schedule
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Gleicht die geplanten Jobs mit den Einstellungen ab. */
    fun apply(settings: AppSettings) {
        scheduleAppStopper(settings.autoStopIntervalHours)
        scheduleNightRoutine(settings.nightRoutineEnabled)
    }

    private fun scheduleAppStopper(intervalHours: Int) {
        if (intervalHours <= 0) {
            workManager.cancelUniqueWork(APP_STOPPER_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<AppStopperWorker>(intervalHours.toLong(), TimeUnit.HOURS)
            .setInitialDelay(intervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag(APP_STOPPER_WORK)
            .build()
        workManager.enqueueUniquePeriodicWork(APP_STOPPER_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun scheduleNightRoutine(enabled: Boolean) {
        if (!enabled) {
            workManager.cancelUniqueWork(NIGHT_SCAN_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<NightScanWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .build(),
            )
            .addTag(NIGHT_SCAN_WORK)
            .build()
        workManager.enqueueUniquePeriodicWork(NIGHT_SCAN_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /* ---------- Phase 2: Wächter ---------- */

    fun applyGuard(settings: GuardSettings) {
        schedulePeriodic<GuardCheckWorker>(GUARD_CHECK_WORK, settings.needsPeriodicCheck, 15, TimeUnit.MINUTES)
        schedulePeriodic<ExpiredFilesWorker>(EXPIRED_FILES_WORK, settings.expiredFilesEnabled, 7, TimeUnit.DAYS)
        schedulePeriodic<NotificationCleanupWorker>(NOTIFICATION_CLEANUP_WORK, true, 1, TimeUnit.DAYS)
        scheduleDigest(settings)
        scheduleNightReport(settings)
    }

    /** Alarm in die Offline-Warteschlange stellen: läuft, sobald Internet verfügbar ist. */
    fun enqueueAlert(eventId: Long) {
        val request = OneTimeWorkRequestBuilder<AlertSendWorker>()
            .setInputData(workDataOf(AlertSendWorker.KEY_EVENT_ID to eventId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(ALERT_TAG)
            .build()
        workManager.enqueueUniqueWork("alert_$eventId", ExistingWorkPolicy.KEEP, request)
    }

    fun scheduleDigest(settings: GuardSettings) {
        val next = Schedule.nextOccurrence(settings.digestTimes, LocalDateTime.now())
        if (!settings.digestEnabled || next == null) {
            workManager.cancelUniqueWork(DIGEST_WORK)
            return
        }
        val request = OneTimeWorkRequestBuilder<DigestWorker>()
            .setInitialDelay(delayUntil(next))
            .addTag(DIGEST_WORK)
            .build()
        workManager.enqueueUniqueWork(DIGEST_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun scheduleNightReport(settings: GuardSettings) {
        if (!settings.nightReportEnabled) {
            workManager.cancelUniqueWork(NIGHT_REPORT_WORK)
            return
        }
        val now = LocalDateTime.now()
        val nextStart = Schedule.nextOccurrence(listOf(settings.nightStartMinutes), now) ?: return
        val nextEnd = Schedule.nextOccurrence(listOf(settings.nightEndMinutes), now) ?: return
        val isStart = !nextEnd.isBefore(nextStart)
        val request = OneTimeWorkRequestBuilder<NightStandbyWorker>()
            .setInitialDelay(delayUntil(if (isStart) nextStart else nextEnd))
            .setInputData(workDataOf(NightStandbyWorker.KEY_IS_START to isStart))
            .addTag(NIGHT_REPORT_WORK)
            .build()
        workManager.enqueueUniqueWork(NIGHT_REPORT_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private inline fun <reified W : androidx.work.ListenableWorker> schedulePeriodic(
        name: String,
        enabled: Boolean,
        interval: Long,
        unit: TimeUnit,
    ) {
        if (!enabled) {
            workManager.cancelUniqueWork(name)
            return
        }
        val request = PeriodicWorkRequestBuilder<W>(interval, unit).addTag(name).build()
        workManager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun delayUntil(time: LocalDateTime): Duration =
        Duration.between(LocalDateTime.now(), time).coerceAtLeast(Duration.ZERO)

    private companion object {
        const val APP_STOPPER_WORK = "app_stopper_periodic"
        const val NIGHT_SCAN_WORK = "night_scan_periodic"
        const val GUARD_CHECK_WORK = "guard_check_periodic"
        const val EXPIRED_FILES_WORK = "expired_files_weekly"
        const val NOTIFICATION_CLEANUP_WORK = "notification_cleanup_daily"
        const val DIGEST_WORK = "notification_digest"
        const val NIGHT_REPORT_WORK = "night_standby_report"
        const val ALERT_TAG = "security_alert"
    }
}
