package app.mobilemanager.data.forcestop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.mobilemanager.data.apps.InstalledAppsRepository
import app.mobilemanager.data.shizuku.ShizukuManager
import app.mobilemanager.data.shizuku.ShizukuState
import app.mobilemanager.domain.model.ForceStopMethod
import app.mobilemanager.domain.model.ForceStopResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForceStopEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val appsRepository: InstalledAppsRepository,
) {
    /** Bevorzugte verfügbare Methode, `null` = keine (Shizuku inaktiv, kein Accessibility-Dienst). */
    fun availableMethod(allowAccessibility: Boolean = true): ForceStopMethod? {
        shizukuManager.refresh()
        return when {
            shizukuManager.state.value == ShizukuState.READY -> ForceStopMethod.SHIZUKU
            allowAccessibility && isAccessibilityServiceEnabled() -> ForceStopMethod.ACCESSIBILITY
            else -> null
        }
    }

    suspend fun forceStop(packageName: String, method: ForceStopMethod): ForceStopResult {
        if (packageName == context.packageName) return ForceStopResult.Skipped
        return when (method) {
            ForceStopMethod.SHIZUKU -> {
                shizukuManager.forceStop(packageName)?.let { ForceStopResult.Failed(it) }
                    ?: verify(packageName)
            }
            ForceStopMethod.ACCESSIBILITY -> forceStopViaAccessibility(packageName)
        }
    }

    private suspend fun verify(packageName: String): ForceStopResult {
        // Das Flag wird asynchron gesetzt – kurz warten.
        repeat(5) {
            if (appsRepository.isStopped(packageName)) return ForceStopResult.Success
            delay(100)
        }
        // `am force-stop` lief fehlerfrei; manche Apps starten sofort wieder (z. B. per Push).
        return ForceStopResult.Success
    }

    private suspend fun forceStopViaAccessibility(packageName: String): ForceStopResult {
        val request = AccessibilityForceStopCoordinator.begin(packageName)
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        return try {
            context.startActivity(intent)
            val ok = withTimeoutOrNull(ACCESSIBILITY_TIMEOUT_MS) { request.result.await() } ?: false
            delay(400) // Settings-Seite schließen lassen
            if (ok) ForceStopResult.Success else ForceStopResult.Failed("Zeitüberschreitung in den App-Infos")
        } catch (e: Exception) {
            ForceStopResult.Failed(e.message ?: "App-Info konnte nicht geöffnet werden")
        } finally {
            AccessibilityForceStopCoordinator.finish(request, success = false)
        }
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(context, ForceStopAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private companion object {
        const val ACCESSIBILITY_TIMEOUT_MS = 8_000L
    }
}
