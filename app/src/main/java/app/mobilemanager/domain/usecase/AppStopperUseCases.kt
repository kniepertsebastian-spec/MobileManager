package app.mobilemanager.domain.usecase

import app.mobilemanager.data.apps.InstalledAppsRepository
import app.mobilemanager.data.forcestop.ForceStopEngine
import app.mobilemanager.data.local.CleanupHistoryDao
import app.mobilemanager.data.local.CleanupHistoryEntity
import app.mobilemanager.data.settings.SettingsRepository
import app.mobilemanager.domain.model.ForceStopMethod
import app.mobilemanager.domain.model.ForceStopResult
import app.mobilemanager.domain.model.InactiveAppsResult
import app.mobilemanager.domain.model.InstalledApp
import app.mobilemanager.domain.model.StopProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Lädt alle Apps und bestimmt die Kandidaten für den App-Stopper:
 * länger als der Schwellenwert inaktiv, nicht geschützt, nicht bereits gestoppt.
 */
class GetInactiveAppsUseCase @Inject constructor(
    private val appsRepository: InstalledAppsRepository,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(): InactiveAppsResult {
        val current = settings.current()
        val now = System.currentTimeMillis()
        val thresholdMs = TimeUnit.HOURS.toMillis(current.inactiveThresholdHours.toLong())
        val apps = appsRepository.loadApps(includeSystemApps = current.showSystemApps)
            .sortedWith(compareBy<InstalledApp> { it.lastUsed ?: 0L }.thenBy { it.label.lowercase() })
        val candidates = apps.filter { app ->
            !app.isProtected && !app.isStopped && isInactive(app, now, thresholdMs)
        }
        return InactiveAppsResult(current.inactiveThresholdHours, apps, candidates)
    }

    companion object {
        /** Apps ohne Nutzungsdaten im Auswertungszeitraum gelten als inaktiv. */
        fun isInactive(app: InstalledApp, now: Long, thresholdMs: Long): Boolean =
            app.lastUsed == null || now - app.lastUsed > thresholdMs
    }
}

/**
 * Stoppt die übergebenen Apps nacheinander und meldet den Fortschritt.
 * Geschützte Apps werden hier ein zweites Mal hart übersprungen.
 */
class StopAppsUseCase @Inject constructor(
    private val engine: ForceStopEngine,
    private val historyDao: CleanupHistoryDao,
) {
    fun availableMethod(allowAccessibility: Boolean = true): ForceStopMethod? =
        engine.availableMethod(allowAccessibility)

    operator fun invoke(apps: List<InstalledApp>, method: ForceStopMethod): Flow<StopProgress> = flow {
        val targets = apps.filterNot { it.isProtected }
        var succeeded = 0
        var failed = 0
        targets.forEachIndexed { index, app ->
            emit(StopProgress(index, targets.size, app.label, succeeded, failed, finished = false))
            when (engine.forceStop(app.packageName, method)) {
                ForceStopResult.Success -> succeeded++
                is ForceStopResult.Failed -> failed++
                ForceStopResult.Skipped -> Unit
            }
        }
        if (succeeded > 0) {
            historyDao.insert(
                CleanupHistoryEntity(
                    timestamp = System.currentTimeMillis(),
                    freedBytes = 0,
                    stoppedAppsCount = succeeded,
                ),
            )
        }
        emit(StopProgress(targets.size, targets.size, "", succeeded, failed, finished = true))
    }
}
