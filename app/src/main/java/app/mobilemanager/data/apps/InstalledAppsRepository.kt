package app.mobilemanager.data.apps

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import app.mobilemanager.data.local.WhitelistDao
import app.mobilemanager.data.local.WhitelistedAppEntity
import app.mobilemanager.domain.model.InstalledApp
import app.mobilemanager.domain.model.LaunchableApp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InstalledAppsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val whitelistDao: WhitelistDao,
    private val protectedAppsProvider: ProtectedAppsProvider,
) {
    private val pm: PackageManager get() = context.packageManager

    val whitelist: Flow<Set<String>> = whitelistDao.observeAll().map { list -> list.map { it.packageName }.toSet() }

    suspend fun setWhitelisted(packageName: String, whitelisted: Boolean) {
        if (whitelisted) {
            whitelistDao.insert(WhitelistedAppEntity(packageName, System.currentTimeMillis()))
        } else {
            whitelistDao.delete(packageName)
        }
    }

    /** Alle startbaren Apps (mit Launcher-Icon). */
    suspend fun getLaunchableApps(): List<LaunchableApp> = withContext(Dispatchers.IO) {
        launcherPackages().map { pkg -> LaunchableApp(pkg, labelOf(pkg)) }.sortedBy { it.label.lowercase() }
    }

    /**
     * Lädt alle startbaren Apps inkl. letzter Vordergrund-Nutzung und Schutzstatus.
     */
    suspend fun loadApps(includeSystemApps: Boolean): List<InstalledApp> = withContext(Dispatchers.IO) {
        val whitelisted = whitelistDao.getPackageNames().toSet()
        val protected = protectedAppsProvider.resolve()
        val lastUsed = queryLastUsed()

        launcherPackages().mapNotNull { pkg ->
            val info = runCatching {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            }.getOrNull() ?: return@mapNotNull null
            val isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            if (isSystem && !includeSystemApps) return@mapNotNull null
            InstalledApp(
                packageName = pkg,
                label = info.loadLabel(pm).toString(),
                isSystemApp = isSystem,
                lastUsed = lastUsed[pkg],
                isStopped = info.flags and ApplicationInfo.FLAG_STOPPED != 0,
                autoProtection = protected[pkg],
                isWhitelisted = pkg in whitelisted,
            )
        }
    }

    /** Prüft nach einem Force-Stop, ob Android die App wirklich als gestoppt markiert hat. */
    fun isStopped(packageName: String): Boolean = runCatching {
        pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)).flags and
            ApplicationInfo.FLAG_STOPPED != 0
    }.getOrDefault(false)

    fun loadIcon(packageName: String): Drawable? = runCatching { pm.getApplicationIcon(packageName) }.getOrNull()

    fun labelOf(packageName: String): String = runCatching {
        pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm).toString()
    }.getOrDefault(packageName)

    private fun launcherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.packageName }
            .toSet()
    }

    /**
     * Letzter Zeitpunkt, zu dem eine App im Vordergrund war. Ohne Nutzungszugriff liefert
     * der UsageStatsManager eine leere Map.
     */
    private fun queryLastUsed(): Map<String, Long> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val end = System.currentTimeMillis()
        val begin = end - TimeUnit.DAYS.toMillis(USAGE_LOOKBACK_DAYS)
        return runCatching {
            usm.queryAndAggregateUsageStats(begin, end).mapValues { (_, stats) ->
                maxOf(stats.lastTimeUsed, stats.lastTimeVisible)
            }.filterValues { it > 0 }
        }.getOrDefault(emptyMap())
    }

    companion object {
        const val USAGE_LOOKBACK_DAYS = 60L
    }
}
