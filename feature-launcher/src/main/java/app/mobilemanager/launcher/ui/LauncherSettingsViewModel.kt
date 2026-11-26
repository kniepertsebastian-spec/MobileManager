package app.mobilemanager.launcher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mobilemanager.launcher.LauncherComponents
import app.mobilemanager.launcher.data.LauncherAppsRepository
import app.mobilemanager.launcher.data.LauncherContextRepository
import app.mobilemanager.launcher.data.LauncherHostBridge
import app.mobilemanager.launcher.data.LauncherLayoutRepository
import app.mobilemanager.launcher.data.LauncherPreferences
import app.mobilemanager.launcher.data.LauncherSettings
import app.mobilemanager.launcher.data.SnapshotInfo
import app.mobilemanager.launcher.domain.AppMeta
import app.mobilemanager.launcher.domain.LauncherProfile
import app.mobilemanager.launcher.domain.ProfileRule
import app.mobilemanager.launcher.domain.TidyOptions
import app.mobilemanager.launcher.domain.TidyPlan
import app.mobilemanager.launcher.domain.TidyPlanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class LauncherStatus(val enabled: Boolean = false, val isDefault: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LauncherSettingsViewModel @Inject constructor(
    private val application: Application,
    private val prefs: LauncherPreferences,
    private val layout: LauncherLayoutRepository,
    private val appsRepository: LauncherAppsRepository,
    private val contextRepository: LauncherContextRepository,
    private val bridge: LauncherHostBridge,
) : AndroidViewModel(application) {

    private fun <T> Flow<T>.state(initial: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val settings: StateFlow<LauncherSettings> = prefs.settings.state(LauncherSettings())
    val profiles: StateFlow<List<LauncherProfile>> = layout.profiles.state(emptyList())
    val rules: StateFlow<List<ProfileRule>> = layout.rules.state(emptyList())
    val activeProfileId: StateFlow<Long?> = contextRepository.activeProfile.map { it.profileId }.state(null)

    private val _status = MutableStateFlow(LauncherStatus())
    val status: StateFlow<LauncherStatus> = _status.asStateFlow()

    // ------------------------------------------------------------- Ordnen
    private val _tidyProfileId = MutableStateFlow<Long?>(null)
    val tidyProfileId: StateFlow<Long?> = _tidyProfileId.asStateFlow()
    private val _options = MutableStateFlow(TidyOptions())
    val options: StateFlow<TidyOptions> = _options.asStateFlow()
    private val lastUsed = MutableStateFlow<Map<String, Long>?>(null)

    val plan: StateFlow<TidyPlan?> = _tidyProfileId.filterNotNull().flatMapLatest { profileId ->
        combine(
            layout.items(profileId),
            appsRepository.apps.filterNotNull(),
            _options,
            lastUsed.filterNotNull(),
            combine(prefs.settings, layout.profiles) { s, p -> s to p },
        ) { items, apps, options, usage, (settings, profiles) ->
            val profile = profiles.firstOrNull { it.id == profileId }
            val pageSize = if (profile?.largeIcons == true) LauncherLayoutRepository.CAR_GRID else settings.pageSize
            val meta = apps.associate { app ->
                app.key to AppMeta(app.label, app.category, if (app.isWorkProfile) null else usage[app.packageName])
            }
            TidyPlanner.plan(items, meta, options, pageSize, System.currentTimeMillis())
        }.flowOn(Dispatchers.Default)
    }.state(null)

    val latestSnapshot: StateFlow<SnapshotInfo?> = _tidyProfileId.flatMapLatest { id ->
        if (id == null) flowOf(null) else layout.latestSnapshot(id)
    }.state(null)

    init {
        appsRepository.start()
        refreshStatus()
        viewModelScope.launch {
            lastUsed.value = withContext(Dispatchers.IO) { runCatching { bridge.lastUsedByPackage() }.getOrDefault(emptyMap()) }
        }
        viewModelScope.launch {
            // Standard: das gerade aktive Profil ordnen.
            if (_tidyProfileId.value == null) {
                _tidyProfileId.value = contextRepository.activeProfile.first().profileId.takeIf { it != 0L }
                    ?: layout.profiles.first { it.isNotEmpty() }.first().id
            }
        }
    }

    fun refreshStatus() {
        _status.value = LauncherStatus(
            enabled = LauncherComponents.isEnabled(application),
            isDefault = LauncherComponents.isDefaultHome(application),
        )
    }

    fun updateSettings(transform: (LauncherSettings) -> LauncherSettings) {
        viewModelScope.launch { prefs.update(transform) }
    }

    fun selectTidyProfile(id: Long) {
        _tidyProfileId.value = id
    }

    fun setOptions(options: TidyOptions) {
        _options.value = options
    }

    fun applyTidy(onDone: () -> Unit) {
        val id = _tidyProfileId.value ?: return
        val currentPlan = plan.value ?: return
        viewModelScope.launch {
            layout.applyTidy(id, currentPlan)
            onDone()
        }
    }

    fun undo(onDone: (Boolean) -> Unit) {
        val id = _tidyProfileId.value ?: return
        viewModelScope.launch { onDone(layout.undoLast(id)) }
    }

    // ------------------------------------------------------------- Profile & Regeln
    fun createProfile(name: String, copyFrom: Long?) {
        viewModelScope.launch { layout.createProfile(name, copyFrom) }
    }

    fun updateProfile(profile: LauncherProfile) {
        viewModelScope.launch { layout.updateProfile(profile) }
    }

    fun deleteProfile(id: Long) {
        viewModelScope.launch { layout.deleteProfile(id) }
    }

    fun saveRule(rule: ProfileRule) {
        viewModelScope.launch { layout.saveRule(rule) }
    }

    fun deleteRule(id: Long) {
        viewModelScope.launch { layout.deleteRule(id) }
    }

    fun pairedDevices(): List<Pair<String, String>> = runCatching { bridge.pairedDevices() }.getOrDefault(emptyList())

    fun currentSsid(): Flow<String?> = contextRepository.wifiSsid()

    fun hasLocationPermission(): Boolean = contextRepository.hasLocationPermission()
}
