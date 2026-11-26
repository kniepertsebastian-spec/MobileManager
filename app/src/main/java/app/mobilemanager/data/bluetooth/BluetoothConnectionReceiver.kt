package app.mobilemanager.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.mobilemanager.data.settings.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reagiert auf ACL-Verbindungen. Bei einer aktiven Regel für das Gerät wird die Aktion
 * ausgeführt. Läuft per goAsync(), damit der Prozess bis zum Abschluss am Leben bleibt.
 */
class BluetoothConnectionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        fun bluetoothRepository(): BluetoothRepository
        fun automationExecutor(): AutomationExecutor
        fun settingsRepository(): SettingsRepository
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
        val address = device.address
        val name = runCatching { device.name }.getOrNull() ?: address

        val pending = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(MAX_RUNTIME_MS) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_ACL_CONNECTED -> {
                            entryPoint.settingsRepository().markBluetoothConnected(address, name)
                            entryPoint.bluetoothRepository().enabledRulesFor(address).forEach { rule ->
                                entryPoint.automationExecutor().execute(rule)
                            }
                        }
                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                            entryPoint.settingsRepository().markBluetoothDisconnected(address)
                            if (entryPoint.bluetoothRepository().enabledRulesFor(address).isNotEmpty()) {
                                entryPoint.automationExecutor().onDeviceDisconnected()
                            }
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** goAsync erlaubt ca. 10 s; großzügig darunter bleiben. */
        const val MAX_RUNTIME_MS = 8_000L
    }
}
