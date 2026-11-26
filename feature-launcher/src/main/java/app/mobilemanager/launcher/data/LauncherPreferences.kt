package app.mobilemanager.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.launcherStore by preferencesDataStore(name = "launcher_prefs")

data class LauncherSettings(
    val baseProfileId: Long = 0L,
    val columns: Int = 4,
    val rows: Int = 5,
    val showLabels: Boolean = true,
    val autoSwitch: Boolean = true,
    val initialized: Boolean = false,
) {
    val pageSize: Int get() = columns * rows
}

@Singleton
class LauncherPreferences @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val BASE_PROFILE = longPreferencesKey("base_profile")
        val COLUMNS = intPreferencesKey("columns")
        val ROWS = intPreferencesKey("rows")
        val SHOW_LABELS = booleanPreferencesKey("show_labels")
        val AUTO_SWITCH = booleanPreferencesKey("auto_switch")
        val INITIALIZED = booleanPreferencesKey("initialized")
    }

    val settings: Flow<LauncherSettings> = context.launcherStore.data.map(::fromPrefs)

    suspend fun current(): LauncherSettings = settings.first()

    suspend fun update(transform: (LauncherSettings) -> LauncherSettings) {
        context.launcherStore.edit { p ->
            val next = transform(fromPrefs(p))
            p[Keys.BASE_PROFILE] = next.baseProfileId
            p[Keys.COLUMNS] = next.columns
            p[Keys.ROWS] = next.rows
            p[Keys.SHOW_LABELS] = next.showLabels
            p[Keys.AUTO_SWITCH] = next.autoSwitch
            p[Keys.INITIALIZED] = next.initialized
        }
    }

    private fun fromPrefs(p: Preferences) = LauncherSettings(
        baseProfileId = p[Keys.BASE_PROFILE] ?: 0L,
        columns = p[Keys.COLUMNS] ?: 4,
        rows = p[Keys.ROWS] ?: 5,
        showLabels = p[Keys.SHOW_LABELS] ?: true,
        autoSwitch = p[Keys.AUTO_SWITCH] ?: true,
        initialized = p[Keys.INITIALIZED] ?: false,
    )
}
