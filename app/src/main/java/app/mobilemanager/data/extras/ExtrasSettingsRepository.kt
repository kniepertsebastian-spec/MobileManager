package app.mobilemanager.data.extras

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.extrasStore by preferencesDataStore(name = "extras_settings")

/** Meilenstein 3.4: jedes Extra einzeln schaltbar. */
data class ExtrasSettings(
    val focusEnabled: Boolean = false,
    val focusPackages: Set<String> = emptySet(),
    val focusCountdownSeconds: Int = 5,
    val focusGraceMinutes: Int = 5,
    val volumeEnabled: Boolean = false,
    val diagnosticsEnabled: Boolean = true,
    val apkBackupEnabled: Boolean = true,
) {
    /** Braucht irgendein Modul die Erkennung der Vordergrund-App? */
    val needsForegroundApp: Boolean get() = (focusEnabled && focusPackages.isNotEmpty()) || volumeEnabled
}

@Singleton
class ExtrasSettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val FOCUS_ENABLED = booleanPreferencesKey("focus_enabled")
        val FOCUS_PACKAGES = stringSetPreferencesKey("focus_packages")
        val FOCUS_COUNTDOWN = intPreferencesKey("focus_countdown")
        val FOCUS_GRACE = intPreferencesKey("focus_grace")
        val VOLUME_ENABLED = booleanPreferencesKey("volume_enabled")
        val DIAGNOSTICS = booleanPreferencesKey("diagnostics_enabled")
        val APK_BACKUP = booleanPreferencesKey("apk_backup_enabled")
    }

    val settings: Flow<ExtrasSettings> = context.extrasStore.data.map(::fromPrefs)

    suspend fun current(): ExtrasSettings = settings.first()

    suspend fun update(transform: (ExtrasSettings) -> ExtrasSettings) {
        context.extrasStore.edit { p ->
            val next = transform(fromPrefs(p))
            p[Keys.FOCUS_ENABLED] = next.focusEnabled
            p[Keys.FOCUS_PACKAGES] = next.focusPackages
            p[Keys.FOCUS_COUNTDOWN] = next.focusCountdownSeconds
            p[Keys.FOCUS_GRACE] = next.focusGraceMinutes
            p[Keys.VOLUME_ENABLED] = next.volumeEnabled
            p[Keys.DIAGNOSTICS] = next.diagnosticsEnabled
            p[Keys.APK_BACKUP] = next.apkBackupEnabled
        }
    }

    private fun fromPrefs(p: Preferences) = ExtrasSettings(
        focusEnabled = p[Keys.FOCUS_ENABLED] ?: false,
        focusPackages = p[Keys.FOCUS_PACKAGES] ?: emptySet(),
        focusCountdownSeconds = p[Keys.FOCUS_COUNTDOWN] ?: 5,
        focusGraceMinutes = p[Keys.FOCUS_GRACE] ?: 5,
        volumeEnabled = p[Keys.VOLUME_ENABLED] ?: false,
        diagnosticsEnabled = p[Keys.DIAGNOSTICS] ?: true,
        apkBackupEnabled = p[Keys.APK_BACKUP] ?: true,
    )
}
