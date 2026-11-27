package app.mobilemanager.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
    /** Pixel-„Designsymbole“: einfarbige, an Material You angepasste Icons. */
    val themedIcons: Boolean = false,
    /** Phase 4.7: Graustufen-Icons gegen Ablenkung. */
    val grayscale: Boolean = false,
    /** Phase 4.7: Startbildschirm als schlichte Textliste. */
    val textOnly: Boolean = false,
    /** Phase 4.7: Graustufen + Textliste automatisch, solange die Fokus-Bremse aktiv ist. */
    val minimalInFocus: Boolean = false,
    val doubleTapAction: GestureAction = GestureAction.NONE,
    val swipeDownAction: GestureAction = GestureAction.NONE,
    /** Phase 4.7: App-Schlüssel im versteckten Tresor. */
    val vault: Set<String> = emptySet(),
) {
    val pageSize: Int get() = columns * rows
}

/** Phase 4.7: frei belegbare Homescreen-Gesten. Sperren & Benachrichtigungen laufen über die Bedienungshilfe. */
enum class GestureAction(val label: String) {
    NONE("Keine"),
    LOCK_SCREEN("Display sperren"),
    NOTIFICATIONS("Benachrichtigungen"),
    QUICK_SETTINGS("Schnelleinstellungen"),
    SEARCH("App-Suche"),
    ;

    companion object {
        fun from(name: String?) = entries.firstOrNull { it.name == name } ?: NONE
    }
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
        val THEMED_ICONS = booleanPreferencesKey("themed_icons")
        val LAUNCH_HISTORY = stringPreferencesKey("launch_history")
        val GRAYSCALE = booleanPreferencesKey("grayscale")
        val TEXT_ONLY = booleanPreferencesKey("text_only")
        val MINIMAL_IN_FOCUS = booleanPreferencesKey("minimal_in_focus")
        val DOUBLE_TAP = stringPreferencesKey("double_tap_action")
        val SWIPE_DOWN = stringPreferencesKey("swipe_down_action")
        val VAULT = stringSetPreferencesKey("vault")
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
            p[Keys.THEMED_ICONS] = next.themedIcons
            p[Keys.GRAYSCALE] = next.grayscale
            p[Keys.TEXT_ONLY] = next.textOnly
            p[Keys.MINIMAL_IN_FOCUS] = next.minimalInFocus
            p[Keys.DOUBLE_TAP] = next.doubleTapAction.name
            p[Keys.SWIPE_DOWN] = next.swipeDownAction.name
            p[Keys.VAULT] = next.vault
        }
    }

    /** Zuletzt gestartete Apps (neueste zuerst) – Grundlage der App-Vorschläge im Drawer. Bleibt lokal. */
    val launchHistory: Flow<List<String>> = context.launcherStore.data.map { p ->
        p[Keys.LAUNCH_HISTORY]?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    }

    suspend fun recordLaunch(key: String) {
        context.launcherStore.edit { p ->
            val history = p[Keys.LAUNCH_HISTORY]?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
            p[Keys.LAUNCH_HISTORY] = (listOf(key) + history).take(MAX_HISTORY).joinToString("\n")
        }
    }

    private fun fromPrefs(p: Preferences) = LauncherSettings(
        baseProfileId = p[Keys.BASE_PROFILE] ?: 0L,
        columns = p[Keys.COLUMNS] ?: 4,
        rows = p[Keys.ROWS] ?: 5,
        showLabels = p[Keys.SHOW_LABELS] ?: true,
        autoSwitch = p[Keys.AUTO_SWITCH] ?: true,
        initialized = p[Keys.INITIALIZED] ?: false,
        themedIcons = p[Keys.THEMED_ICONS] ?: false,
        grayscale = p[Keys.GRAYSCALE] ?: false,
        textOnly = p[Keys.TEXT_ONLY] ?: false,
        minimalInFocus = p[Keys.MINIMAL_IN_FOCUS] ?: false,
        doubleTapAction = GestureAction.from(p[Keys.DOUBLE_TAP]),
        swipeDownAction = GestureAction.from(p[Keys.SWIPE_DOWN]),
        vault = p[Keys.VAULT] ?: emptySet(),
    )

    private companion object {
        const val MAX_HISTORY = 60
    }
}
