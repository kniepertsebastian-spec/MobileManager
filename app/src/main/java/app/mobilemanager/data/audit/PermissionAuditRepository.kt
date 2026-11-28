package app.mobilemanager.data.audit

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import app.mobilemanager.data.apps.InstalledAppsRepository
import app.mobilemanager.domain.guard.AuditPolicy
import app.mobilemanager.domain.model.AuditedApp
import app.mobilemanager.domain.model.CriticalPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Meilenstein 2.3.1: Welche Nutzer-Apps haben kritische Rechte tatsächlich erteilt bekommen?
 * Ein einziger `getInstalledPackages(GET_PERMISSIONS)`-Aufruf hält die Liste unter 2 Sekunden.
 */
@Singleton
class PermissionAuditRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installedApps: InstalledAppsRepository,
) {
    private val pm: PackageManager get() = context.packageManager

    suspend fun audit(): List<AuditedApp> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastUsed = installedApps.lastUsedMap()
        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { it.applicationInfo?.isUserApp() == true }
            .mapNotNull { info ->
                val granted = grantedCriticalPermissions(info)
                val risk = AuditPolicy.classify(granted, lastUsed[info.packageName], now) ?: return@mapNotNull null
                AuditedApp(
                    packageName = info.packageName,
                    label = info.applicationInfo?.loadLabel(pm)?.toString() ?: info.packageName,
                    grantedPermissions = granted,
                    lastUsed = lastUsed[info.packageName],
                    risk = risk,
                )
            }
            .sortedWith(compareBy<AuditedApp> { it.risk.ordinal }.thenBy { it.lastUsed ?: 0L }.thenBy { it.label.lowercase() })
            .toList()
    }

    fun appSettingsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun grantedCriticalPermissions(info: PackageInfo): List<CriticalPermission> {
        val requested = info.requestedPermissions ?: return emptyList()
        val flags = info.requestedPermissionsFlags ?: return emptyList()
        return CriticalPermission.entries.filter { critical ->
            val index = requested.indexOf(critical.permission)
            if (index < 0) return@filter false
            if (critical == CriticalPermission.MANAGE_EXTERNAL_STORAGE) {
                // Spezialrecht: wird als AppOp vergeben, nicht als Runtime-Permission.
                isAppOpAllowed(OPSTR_MANAGE_EXTERNAL_STORAGE, info)
            } else {
                flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            }
        }
    }

    @Suppress("DEPRECATION") // unsafeCheckOpNoThrow ist der einzige öffentliche Weg, den AppOp-Modus fremder Apps zu lesen
    private fun isAppOpAllowed(op: String, info: PackageInfo): Boolean {
        val uid = info.applicationInfo?.uid ?: return false
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return runCatching { appOps.unsafeCheckOpNoThrow(op, uid, info.packageName) == AppOpsManager.MODE_ALLOWED }
            .getOrDefault(false)
    }

    private fun ApplicationInfo.isUserApp(): Boolean =
        flags and ApplicationInfo.FLAG_SYSTEM == 0 || flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0

    private companion object {
        const val OPSTR_MANAGE_EXTERNAL_STORAGE = "android:manage_external_storage"
    }
}
