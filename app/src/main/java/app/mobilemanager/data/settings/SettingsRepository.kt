package app.mobilemanager.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val onboardingDone: Boolean = false,
    /** App gilt als inaktiv, wenn sie länger als so viele Stunden nicht im Vordergrund war. */
    val inactiveThresholdHours: Int = 24,
    val showSystemApps: Boolean = false,
    /** 0 = aus, sonst Intervall des automatischen App-Stoppers in Stunden. */
    val autoStopIntervalHours: Int = 0,
    /** Bilder älter als so viele Monate werden auf Personen geprüft. */
    val photoAgeMonths: Int = 6,
    val nightRoutineEnabled: Boolean = false,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val THRESHOLD_HOURS = intPreferencesKey("inactive_threshold_hours")
        val SHOW_SYSTEM_APPS = booleanPreferencesKey("show_system_apps")
        val AUTO_STOP_INTERVAL = intPreferencesKey("auto_stop_interval_hours")
        val PHOTO_AGE_MONTHS = intPreferencesKey("photo_age_months")
        val NIGHT_ROUTINE = booleanPreferencesKey("night_routine_enabled")
        val CONNECTED_BT_DEVICES = stringSetPreferencesKey("connected_bt_devices")
        val LAST_BT_DEVICE_NAME = stringPreferencesKey("last_bt_device_name")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboardingDone = p[Keys.ONBOARDING_DONE] ?: false,
            inactiveThresholdHours = p[Keys.THRESHOLD_HOURS] ?: 24,
            showSystemApps = p[Keys.SHOW_SYSTEM_APPS] ?: false,
            autoStopIntervalHours = p[Keys.AUTO_STOP_INTERVAL] ?: 0,
            photoAgeMonths = p[Keys.PHOTO_AGE_MONTHS] ?: 6,
            nightRoutineEnabled = p[Keys.NIGHT_ROUTINE] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setOnboardingDone() = context.dataStore.edit { it[Keys.ONBOARDING_DONE] = true }
    suspend fun setInactiveThresholdHours(hours: Int) = context.dataStore.edit { it[Keys.THRESHOLD_HOURS] = hours }
    suspend fun setShowSystemApps(show: Boolean) = context.dataStore.edit { it[Keys.SHOW_SYSTEM_APPS] = show }
    suspend fun setAutoStopIntervalHours(hours: Int) = context.dataStore.edit { it[Keys.AUTO_STOP_INTERVAL] = hours }
    suspend fun setPhotoAgeMonths(months: Int) = context.dataStore.edit { it[Keys.PHOTO_AGE_MONTHS] = months }
    suspend fun setNightRoutineEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.NIGHT_ROUTINE] = enabled }

    /**
     * Aktuell verbundene Bluetooth-Geräte als "address|name". Wird vom
     * [app.mobilemanager.data.bluetooth.BluetoothConnectionReceiver] gepflegt.
     */
    val connectedBluetoothDevices: Flow<Map<String, String>> = context.dataStore.data.map { p ->
        (p[Keys.CONNECTED_BT_DEVICES] ?: emptySet()).associate { entry ->
            val address = entry.substringBefore('|')
            address to entry.substringAfter('|', address)
        }
    }

    suspend fun markBluetoothConnected(address: String, name: String) = context.dataStore.edit { p ->
        val current = (p[Keys.CONNECTED_BT_DEVICES] ?: emptySet()).filterNot { it.startsWith("$address|") }
        p[Keys.CONNECTED_BT_DEVICES] = current.toSet() + "$address|$name"
        p[Keys.LAST_BT_DEVICE_NAME] = name
    }

    suspend fun markBluetoothDisconnected(address: String) = context.dataStore.edit { p ->
        p[Keys.CONNECTED_BT_DEVICES] =
            (p[Keys.CONNECTED_BT_DEVICES] ?: emptySet()).filterNot { it.startsWith("$address|") }.toSet()
    }

    /** Nach einem Neustart sind keine Geräte mehr verbunden; veraltete Einträge verwerfen. */
    suspend fun clearConnectedBluetoothDevices() = context.dataStore.edit { it.remove(Keys.CONNECTED_BT_DEVICES) }
}
