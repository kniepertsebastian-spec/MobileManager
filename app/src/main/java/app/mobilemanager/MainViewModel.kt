package app.mobilemanager

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.guard.GuardService
import app.mobilemanager.data.guard.GuardSettingsRepository
import app.mobilemanager.data.settings.SettingsRepository
import app.mobilemanager.data.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val application: Application,
    settings: SettingsRepository,
    guardSettings: GuardSettingsRepository,
    workScheduler: WorkScheduler,
) : ViewModel() {

    /** `null` solange DataStore noch lädt. */
    val onboardingDone: StateFlow<Boolean?> = settings.settings
        .map { it.onboardingDone }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _pendingDestination = MutableStateFlow<String?>(null)
    val pendingDestination: StateFlow<String?> = _pendingDestination.asStateFlow()

    init {
        // Geplante Jobs (App-Stopper, Nacht-Routine) mit den Einstellungen synchron halten.
        viewModelScope.launch {
            settings.settings
                .map { it.copy(onboardingDone = false, inactiveThresholdHours = 0, showSystemApps = false, photoAgeMonths = 0) }
                .distinctUntilChanged()
                .collect { workScheduler.apply(it) }
        }
        // Phase 2: Wächter-Jobs und optionalen Echtzeit-Dienst synchron halten.
        viewModelScope.launch {
            guardSettings.settings.distinctUntilChanged().collect { s ->
                workScheduler.applyGuard(s)
                if (s.realtimeServiceEnabled) GuardService.start(application) else GuardService.stop(application)
            }
        }
    }

    fun setPendingDestination(route: String) {
        _pendingDestination.value = route
    }

    fun clearPendingDestination() {
        _pendingDestination.value = null
    }
}
