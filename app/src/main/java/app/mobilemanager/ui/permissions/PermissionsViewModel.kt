package app.mobilemanager.ui.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.permissions.PermissionChecker
import app.mobilemanager.data.settings.SettingsRepository
import app.mobilemanager.data.shizuku.ShizukuManager
import app.mobilemanager.data.shizuku.ShizukuState
import app.mobilemanager.domain.model.PermissionKind
import app.mobilemanager.domain.model.PermissionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val checker: PermissionChecker,
    private val shizukuManager: ShizukuManager,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _statuses = MutableStateFlow(checker.statusList())
    val statuses: StateFlow<List<PermissionStatus>> = _statuses.asStateFlow()

    init {
        // Shizuku meldet Statuswechsel asynchron (Binder empfangen, Berechtigung erteilt).
        viewModelScope.launch { shizukuManager.state.collect { refresh() } }
    }

    /** Wird bei ON_RESUME aufgerufen – also direkt nach Rückkehr aus den Android-Einstellungen. */
    fun refresh() {
        _statuses.value = checker.statusList()
    }

    fun settingsIntent(kind: PermissionKind) = checker.settingsIntent(kind)
    fun fallbackIntent(kind: PermissionKind) = checker.fallbackIntent(kind)

    val shizukuState: StateFlow<ShizukuState> get() = shizukuManager.state

    fun requestShizukuPermission() = shizukuManager.requestPermission()
    fun shizukuLaunchIntent() = shizukuManager.launchShizukuAppIntent()

    fun completeOnboarding(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.setOnboardingDone()
            onDone()
        }
    }
}
