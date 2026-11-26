package app.mobilemanager.ui.automation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.apps.InstalledAppsRepository
import app.mobilemanager.data.bluetooth.AutomationExecutor
import app.mobilemanager.data.bluetooth.BluetoothRepository
import app.mobilemanager.data.permissions.PermissionChecker
import app.mobilemanager.domain.model.BluetoothRule
import app.mobilemanager.domain.model.LaunchableApp
import app.mobilemanager.domain.model.PairedDevice
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

data class AutomationUiState(
    val hasBluetoothPermission: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val pairedDevices: List<PairedDevice> = emptyList(),
    val launchableApps: List<LaunchableApp> = emptyList(),
    /** Regel im Editor, `null` = Editor geschlossen. */
    val editing: BluetoothRule? = null,
)

@HiltViewModel
class AutomationViewModel @Inject constructor(
    private val bluetooth: BluetoothRepository,
    private val apps: InstalledAppsRepository,
    private val permissions: PermissionChecker,
    private val executor: AutomationExecutor,
) : ViewModel() {

    private val _state = MutableStateFlow(AutomationUiState())
    val state: StateFlow<AutomationUiState> = _state.asStateFlow()

    val rules: StateFlow<List<BluetoothRule>> =
        bluetooth.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** address -> name der verbundenen Geräte. */
    val connected: StateFlow<Map<String, String>> =
        bluetooth.connectedDevices.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun refresh() {
        viewModelScope.launch {
            bluetooth.refreshConnectedDevices()
            val launchable = if (_state.value.launchableApps.isEmpty()) apps.getLaunchableApps() else _state.value.launchableApps
            _state.update {
                it.copy(
                    hasBluetoothPermission = bluetooth.hasConnectPermission(),
                    hasOverlayPermission = permissions.canDrawOverlays(),
                    bluetoothEnabled = bluetooth.isBluetoothEnabled(),
                    pairedDevices = bluetooth.pairedDevices(),
                    launchableApps = launchable,
                )
            }
        }
    }

    fun newRule(device: PairedDevice) {
        _state.update {
            it.copy(
                editing = BluetoothRule(
                    deviceAddress = device.address,
                    deviceName = device.name,
                    targetVolume = 60,
                    launchPackageName = SPOTIFY.takeIf { pkg -> it.launchableApps.any { app -> app.packageName == pkg } },
                    eqProfileId = null,
                ),
            )
        }
    }

    fun edit(rule: BluetoothRule) = _state.update { it.copy(editing = rule) }
    fun updateEditing(rule: BluetoothRule) = _state.update { it.copy(editing = rule) }
    fun cancelEditing() = _state.update { it.copy(editing = null) }

    fun saveEditing() {
        val rule = _state.value.editing ?: return
        viewModelScope.launch {
            bluetooth.saveRule(rule)
            _state.update { it.copy(editing = null) }
        }
    }

    fun setEnabled(rule: BluetoothRule, enabled: Boolean) {
        viewModelScope.launch { bluetooth.setRuleEnabled(rule.id, enabled) }
    }

    fun delete(rule: BluetoothRule) {
        viewModelScope.launch { bluetooth.deleteRule(rule) }
    }

    /** Führt die Regel sofort aus – zum Testen ohne Verbindungswechsel. */
    fun test(rule: BluetoothRule) {
        viewModelScope.launch { executor.execute(rule) }
    }

    private companion object {
        const val SPOTIFY = "com.spotify.music"
    }
}
