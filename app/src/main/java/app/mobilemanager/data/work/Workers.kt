package app.mobilemanager.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.mobilemanager.data.media.StorageCleanerRepository
import app.mobilemanager.data.settings.SettingsRepository
import app.mobilemanager.domain.model.ForceStopMethod
import app.mobilemanager.domain.usecase.GetInactiveAppsUseCase
import app.mobilemanager.domain.usecase.StopAppsUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.last

/**
 * Geplanter App-Stopper. Läuft ausschließlich über Shizuku – der Accessibility-Fallback
 * braucht eine sichtbare Settings-Seite und wird im Hintergrund nicht genutzt.
 */
@HiltWorker
class AppStopperWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val getInactiveApps: GetInactiveAppsUseCase,
    private val stopApps: StopAppsUseCase,
    private val notifications: AppNotifications,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val method = stopApps.availableMethod(allowAccessibility = false)
        if (method != ForceStopMethod.SHIZUKU) {
            notifications.showShizukuUnavailable()
            return Result.success()
        }
        val candidates = getInactiveApps().candidates
        if (candidates.isEmpty()) return Result.success()
        val result = stopApps(candidates, method).last()
        if (result.succeeded > 0) notifications.showAppsStopped(result.succeeded)
        return Result.success()
    }
}

/** Nacht-Routine: Duplikat- und ML-Kit-Scan, nur beim Laden und im Leerlauf. */
@HiltWorker
class NightScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: StorageCleanerRepository,
    private val settings: SettingsRepository,
    private val notifications: AppNotifications,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val duplicates = repository.findDuplicates { }
        val withoutPeople = repository.analyzeOldImages(settings.current().photoAgeMonths) { }
        val duplicateCount = duplicates.sumOf { it.duplicates.size }
        if (duplicateCount > 0 || withoutPeople > 0) {
            notifications.showScanResult(duplicateCount, withoutPeople)
        }
        return Result.success()
    }
}
