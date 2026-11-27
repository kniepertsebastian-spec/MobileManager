package app.mobilemanager.launcher.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mobilemanager.launcher.LauncherComponents
import app.mobilemanager.launcher.data.LauncherApp
import app.mobilemanager.launcher.data.LauncherAppsRepository
import app.mobilemanager.launcher.data.LauncherContextRepository
import app.mobilemanager.launcher.data.LauncherHostBridge
import app.mobilemanager.launcher.data.LauncherLayoutRepository
import app.mobilemanager.launcher.data.LauncherPreferences
import app.mobilemanager.launcher.data.LauncherSettings
import app.mobilemanager.launcher.data.SnapshotInfo
import app.mobilemanager.launcher.domain.AppMeta
import app.mobilemanager.launcher.domain.CustomFolderRule
import app.mobilemanager.launcher.domain.LauncherProfile
import app.mobilemanager.launcher.domain.ProfileKind
import app.mobilemanager.launcher.domain.ProfileRule
import app.mobilemanager.launcher.domain.TidyOptions
import app.mobilemanager.launcher.domain.TidyPlan
import app.mobilemanager.launcher.domain.TidyPlanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    val customFolders: StateFlow<List<CustomFolderRule>> = layout.customFolders.state(emptyList())

    /** Alle startbaren Apps für die Auswahl in eigenen Ordnern, alphabetisch. */
    val installedApps: StateFlow<List<LauncherApp>> = appsRepository.apps.filterNotNull()
        .map { apps -> apps.sortedBy { it.label.lowercase() } }
        .state(emptyList())

    /** Pakete, die per APK-Datei oder USB installiert wurden (für die Regel „selbst installierte Apps“). */
    private val sideloaded = MutableStateFlow<Set<String>>(emptySet())
    val sideloadedPackages: StateFlow<Set<String>> = sideloaded.asStateFlow()

    val plan: StateFlow<TidyPlan?> = _tidyProfileId.filterNotNull().flatMapLatest { profileId ->
        combine(
            layout.items(profileId),
            appsRepository.apps.filterNotNull(),
            _options,
            lastUsed.filterNotNull(),
            combine(prefs.settings, layout.profiles, layout.customFolders, sideloaded) { s, p, f, side -> Extra(s, p, f, side) },
        ) { items, apps, options, usage, extra ->
            val profile = extra.profiles.firstOrNull { it.id == profileId }
            val pageSize = if (profile?.largeIcons == true) LauncherLayoutRepository.CAR_GRID else extra.settings.pageSize
            val meta = apps.associate { app ->
                app.key to AppMeta(
                    label = app.label,
                    category = app.category,
                    lastUsed = if (app.isWorkProfile) null else usage[app.packageName],
                    packageName = app.packageName,
                    sideloaded = app.packageName in extra.sideloaded,
                )
            }
            TidyPlanner.plan(items, meta, options, pageSize, System.currentTimeMillis(), extra.customFolders)
        }.flowOn(Dispatchers.Default)
    }.state(null)

    val latestSnapshot: StateFlow<SnapshotInfo?> = _tidyProfileId.flatMapLatest { id ->
        if (id == null) flowOf(null) else layout.latestSnapshot(id)
    }.state(null)

    /**
     * Die Standardprofile entstehen sonst erst beim ersten Öffnen des Startbildschirms. Wer vorher hier Profile anlegt
     * oder ordnet, bekäme leere Layouts – deshalb auch hier einrichten.
     */
    private val ready = viewModelScope.async {
        val apps = appsRepository.apps.filterNotNull().first()
        runCatching { layout.ensureInitialized(apps, prefs.current().pageSize) }
    }

    private data class Extra(
        val settings: LauncherSettings,
        val profiles: List<LauncherProfile>,
        val customFolders: List<CustomFolderRule>,
        val sideloaded: Set<String>,
    )

    init {
        appsRepository.start()
        refreshStatus()
        viewModelScope.launch {
            appsRepository.apps.filterNotNull().collect { apps ->
                sideloaded.value = withContext(Dispatchers.IO) { findSideloaded(apps.map { it.packageName }.toSet()) }
            }
        }
        viewModelScope.launch {
            lastUsed.value = withContext(Dispatchers.IO) { runCatching { bridge.lastUsedByPackage() }.getOrDefault(emptyMap()) }
        }
        viewModelScope.launch {
            ready.await()
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

    fun saveCustomFolder(rule: CustomFolderRule) {
        viewModelScope.launch { layout.saveCustomFolder(rule) }
    }

    fun deleteCustomFolder(id: Long) {
        viewModelScope.launch { layout.deleteCustomFolder(id) }
    }

    private fun findSideloaded(packages: Set<String>): Set<String> {
        val pm = application.packageManager
        return packages.filterTo(mutableSetOf()) { pkg ->
            runCatching {
                val info = pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@runCatching false
                pm.getInstallSourceInfo(pkg).installingPackageName in SIDELOAD_INSTALLERS
            }.getOrDefault(false)
        }
    }

    // ------------------------------------------------------------- Profile & Regeln
    fun createProfile(name: String, copyFrom: Long?) {
        viewModelScope.launch {
            ready.await()
            // Nach der Einrichtung gibt es immer ein Standardprofil als Vorlage.
            val source = copyFrom ?: layout.profiles.first().firstOrNull { it.kind == ProfileKind.STANDARD }?.id
            layout.createProfile(name, source)
        }
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

    private companion object {
        /** `null` = per adb ohne Installer-Angabe; Shell = USB/adb; Paketinstaller = APK-Datei angetippt. */
        val SIDELOAD_INSTALLERS = setOf(
            null,
            "com.android.shell",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
        )
    }
}
