package app.mobilemanager.ui.appstopper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.apps.InstalledAppsRepository
import app.mobilemanager.data.permissions.PermissionChecker
import app.mobilemanager.data.settings.SettingsRepository
import app.mobilemanager.data.shizuku.ShizukuManager
import app.mobilemanager.data.shizuku.ShizukuState
import app.mobilemanager.domain.model.ForceStopMethod
import app.mobilemanager.domain.model.InstalledApp
import app.mobilemanager.domain.model.StopProgress
import app.mobilemanager.domain.usecase.GetInactiveAppsUseCase
import app.mobilemanager.domain.usecase.StopAppsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppStopperUiState(
    val loading: Boolean = true,
    val hasUsageAccess: Boolean = true,
    val thresholdHours: Int = 24,
    val showSystemApps: Boolean = false,
    val apps: List<InstalledApp> = emptyList(),
    val candidates: List<InstalledApp> = emptyList(),
    val shizukuState: ShizukuState = ShizukuState.NOT_RUNNING,
    val method: ForceStopMethod? = null,
    val progress: StopProgress? = null,
    val confirmStop: List<InstalledApp>? = null,
)

@HiltViewModel
class AppStopperViewModel @Inject constructor(
    private val getInactiveApps: GetInactiveAppsUseCase,
    private val stopApps: StopAppsUseCase,
    private val appsRepository: InstalledAppsRepository,
    private val settings: SettingsRepository,
    private val permissions: PermissionChecker,
    private val shizukuManager: ShizukuManager,
) : ViewModel() {

    private val _state = MutableStateFlow(AppStopperUiState())
    val state: StateFlow<AppStopperUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            shizukuManager.state.collect { shizuku ->
                _state.update { it.copy(shizukuState = shizuku, method = stopApps.availableMethod()) }
            }
        }
        // Schwellenwert / System-Apps geändert -> neu laden
        viewModelScope.launch { settings.settings.drop(1).collect { reload() } }
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val hasAccess = permissions.hasUsageAccess()
            val result = getInactiveApps()
            val current = settings.current()
            _state.update {
                it.copy(
                    loading = false,
                    hasUsageAccess = hasAccess,
                    thresholdHours = result.thresholdHours,
                    showSystemApps = current.showSystemApps,
                    apps = result.apps,
                    candidates = result.candidates,
                    method = stopApps.availableMethod(),
                )
            }
        }
    }

    fun setWhitelisted(app: InstalledApp, whitelisted: Boolean) {
        viewModelScope.launch {
            appsRepository.setWhitelisted(app.packageName, whitelisted)
            reload()
        }
    }

    fun setThreshold(hours: Int) {
        viewModelScope.launch { settings.setInactiveThresholdHours(hours) }
    }

    fun setShowSystemApps(show: Boolean) {
        viewModelScope.launch { settings.setShowSystemApps(show) }
    }

    /** "Jetzt aufräumen": zeigt zuerst die Liste der betroffenen Apps. */
    fun requestCleanup() {
        _state.update { it.copy(confirmStop = it.candidates) }
    }

    fun requestStopSingle(app: InstalledApp) {
        _state.update { it.copy(confirmStop = listOf(app)) }
    }

    fun dismissConfirm() {
        _state.update { it.copy(confirmStop = null) }
    }

    fun confirmStop() {
        val targets = _state.value.confirmStop.orEmpty()
        _state.update { it.copy(confirmStop = null) }
        val method = stopApps.availableMethod() ?: return
        if (targets.isEmpty()) return
        viewModelScope.launch {
            stopApps(targets, method).collect { progress -> _state.update { it.copy(progress = progress) } }
            reload()
        }
    }

    fun dismissProgress() {
        _state.update { it.copy(progress = null) }
    }
}
