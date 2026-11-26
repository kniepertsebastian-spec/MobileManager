package app.mobilemanager.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.mobilemanager.data.settings.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
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

    private companion object {
        const val APP_STOPPER_WORK = "app_stopper_periodic"
        const val NIGHT_SCAN_WORK = "night_scan_periodic"
    }
}
