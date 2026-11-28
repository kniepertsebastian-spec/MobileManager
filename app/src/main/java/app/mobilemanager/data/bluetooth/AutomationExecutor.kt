package app.mobilemanager.data.bluetooth

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import app.mobilemanager.util.logW
import app.mobilemanager.domain.model.BluetoothRule
import app.mobilemanager.domain.model.EqProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Führt die Aktionen einer Bluetooth-Regel aus: Lautstärke, App-Start, Equalizer. */
@Singleton
class AutomationExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val equalizer: EqualizerController,
) {
    suspend fun execute(rule: BluetoothRule) {
        rule.targetVolume?.let { setMediaVolume(it) }
        EqProfile.byId(rule.eqProfileId)?.let { equalizer.apply(it) }
        rule.launchPackageName?.let { launchApp(it) }

        // Android stellt kurz nach dem Verbindungsaufbau die gespeicherte Geräte-Lautstärke
        // (Absolute Volume) wieder her – deshalb nach kurzer Pause erneut setzen.
        rule.targetVolume?.let {
            delay(VOLUME_REAPPLY_DELAY_MS)
            setMediaVolume(it)
        }
    }

    fun onDeviceDisconnected() {
        equalizer.release()
    }

    private fun setMediaVolume(percent: Int) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * percent.coerceIn(0, 100) / 100f).roundToInt()
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
            .onFailure { logW(TAG, "Lautstärke konnte nicht gesetzt werden", it) }
    }

    private fun launchApp(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: run {
            logW(TAG, "Keine startbare Activity für $packageName")
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            // Ohne "Über anderen Apps einblenden" blockiert Android Activity-Starts aus dem Hintergrund.
            logW(TAG, "SYSTEM_ALERT_WINDOW fehlt – App-Start aus dem Hintergrund wird blockiert")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { context.startActivity(intent) }
            .onFailure { logW(TAG, "App-Start fehlgeschlagen", it) }
    }

    private companion object {
        const val VOLUME_REAPPLY_DELAY_MS = 1_200L
        const val TAG = "AutomationExecutor"
    }
}
