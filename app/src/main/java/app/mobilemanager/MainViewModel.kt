package app.mobilemanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    settings: SettingsRepository,
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
    }

    fun setPendingDestination(route: String) {
        _pendingDestination.value = route
    }

    fun clearPendingDestination() {
        _pendingDestination.value = null
    }
}
