package app.mobilemanager.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.bluetooth.BluetoothRepository
import app.mobilemanager.data.local.CleanupHistoryEntity
import app.mobilemanager.data.media.StorageCleanerRepository
import app.mobilemanager.data.media.StorageInfo
import app.mobilemanager.data.permissions.PermissionChecker
import app.mobilemanager.domain.model.ForceStopMethod
import app.mobilemanager.domain.model.GrantState
import app.mobilemanager.domain.model.InstalledApp
import app.mobilemanager.domain.model.StopProgress
import app.mobilemanager.domain.usecase.GetInactiveAppsUseCase
import app.mobilemanager.domain.usecase.StopAppsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val inactiveApps: List<InstalledApp>? = null,
    val stopMethod: ForceStopMethod? = null,
    val stopProgress: StopProgress? = null,
    val storage: StorageInfo? = null,
    val missingPermissions: Int = 0,
)

data class BluetoothSummary(
    val activeRules: Int = 0,
    val connectedNames: List<String> = emptyList(),
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val getInactiveApps: GetInactiveAppsUseCase,
    private val stopApps: StopAppsUseCase,
    private val storageRepository: StorageCleanerRepository,
    private val bluetooth: BluetoothRepository,
    private val permissions: PermissionChecker,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    val lastCleanup: StateFlow<CleanupHistoryEntity?> =
        storageRepository.lastStorageCleanup.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val bluetoothSummary: StateFlow<BluetoothSummary> =
        combine(bluetooth.rules, bluetooth.connectedDevices) { rules, connected ->
            BluetoothSummary(
                activeRules = rules.count { it.isEnabled },
                connectedNames = connected.values.sorted(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BluetoothSummary())

    fun refresh() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    storage = storageRepository.storageInfo(),
                    stopMethod = stopApps.availableMethod(),
                    missingPermissions = permissions.statusList().count { s -> s.required && s.state != GrantState.GRANTED },
                )
            }
            val candidates = if (permissions.hasUsageAccess()) getInactiveApps().candidates else emptyList()
            _state.update { it.copy(inactiveApps = candidates) }
        }
        viewModelScope.launch { bluetooth.refreshConnectedDevices() }
    }

    fun stopAll() {
        val targets = _state.value.inactiveApps.orEmpty()
        val method = stopApps.availableMethod() ?: return
        if (targets.isEmpty() || _state.value.stopProgress?.finished == false) return
        viewModelScope.launch {
            stopApps(targets, method).collect { p -> _state.update { it.copy(stopProgress = p) } }
            refresh()
        }
    }

    fun dismissStopResult() = _state.update { it.copy(stopProgress = null) }
}
