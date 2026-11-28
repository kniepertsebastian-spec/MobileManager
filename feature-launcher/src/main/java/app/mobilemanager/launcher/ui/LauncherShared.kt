package app.mobilemanager.launcher.ui

import android.graphics.Rect
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import app.mobilemanager.launcher.data.LauncherApp
import app.mobilemanager.launcher.data.LauncherShortcut
import kotlinx.coroutines.launch

internal data class MenuAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Callbacks, die jede App-Zelle braucht. */
internal class CellActions(
    val launch: (LauncherApp, Rect?) -> Unit,
    val shortcut: (LauncherShortcut, Rect?) -> Unit,
    val info: (LauncherApp) -> Unit,
    val uninstall: (LauncherApp) -> Unit,
)

/** Biometrie bzw. Geräte-PIN des Systems, z. B. für den Tresor. */
internal fun authenticate(activity: android.app.Activity, title: String, onSuccess: () -> Unit) {
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (activity.getSystemService(BiometricManager::class.java)?.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
        // Ohne Displaysperre gibt es nichts zu prüfen.
        onSuccess()
        return
    }
    BiometricPrompt.Builder(activity)
        .setTitle(title)
        .setAllowedAuthenticators(authenticators)
        .build()
        .authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        })
}

internal val GRAYSCALE_FILTER = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

internal const val SWIPE_DOWN_THRESHOLD_PX = 150f

internal const val VAULT_KEYWORD = "tresor"
