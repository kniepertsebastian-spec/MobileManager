package app.mobilemanager

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.extras.ExtrasCoordinator
import app.mobilemanager.data.extras.ExtrasSettingsRepository
import app.mobilemanager.data.extras.ForegroundAppTracker
import app.mobilemanager.data.guard.GuardService
import app.mobilemanager.data.guard.GuardSettingsRepository
import app.mobilemanager.data.locker.LockerController
import app.mobilemanager.data.locker.LockerRepository
import app.mobilemanager.data.network.NetworkPolicyController
import app.mobilemanager.data.network.NetworkPolicyRepository
import app.mobilemanager.data.protection.ProtectionRepository
import app.mobilemanager.data.settings.SettingsRepository
import app.mobilemanager.data.tracker.TrackerSettingsRepository
import app.mobilemanager.data.tracker.TrackerVpnController
import app.mobilemanager.data.tracker.TrackerVpnState
import app.mobilemanager.data.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    trackerSettings: TrackerSettingsRepository,
    private val trackerVpn: TrackerVpnController,
    extrasSettings: ExtrasSettingsRepository,
    private val extrasCoordinator: ExtrasCoordinator,
    private val foregroundTracker: ForegroundAppTracker,
    private val lockerRepository: LockerRepository,
    private val locker: LockerController,
    protection: ProtectionRepository,
    networkPolicy: NetworkPolicyRepository,
    private val networkController: NetworkPolicyController,
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
            guardSettings.settings.distinctUntilChanged().collect { s -> workScheduler.applyGuard(s) }
        }
        // Echtzeit-Wächter: eigener Schalter oder Phase-4-Wächter, die Live-Ereignisse brauchen.
        viewModelScope.launch {
            combine(guardSettings.settings, protection.settings) { g, p -> g.realtimeServiceEnabled || p.needsRealtime }
                .distinctUntilChanged()
                .collect { needed -> if (needed) GuardService.start(application) else GuardService.stop(application) }
        }
        // Phase 4: periodische Prüfungen und Blocklisten-Updates nur bei aktiven Funktionen planen.
        viewModelScope.launch {
            combine(networkPolicy.settings, protection.settings, trackerSettings.settings) { n, p, t ->
                val periodic = n.firewallEnabled || n.privateDnsEnabled || n.mobileDailyLimitMb > 0 ||
                    p.watchdogEnabled || p.permissionDiffEnabled
                periodic to t.subscriptions.isNotEmpty()
            }.distinctUntilChanged().collect { (periodic, subscriptions) -> workScheduler.applyPhase4(periodic, subscriptions) }
        }
        // Phase 4.1: Firewall & Privates DNS sofort nach jeder Regeländerung umsetzen.
        viewModelScope.launch {
            networkPolicy.settings.distinctUntilChanged().collect { networkController.apply() }
        }
        // Phase 3: Tracker-Blocker folgt dem Schalter (Start nur mit erteilter VPN-Zustimmung).
        viewModelScope.launch {
            trackerSettings.settings.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                if (enabled) {
                    if (!TrackerVpnState.running.value) trackerVpn.start()
                } else {
                    trackerVpn.stop()
                }
            }
        }
        // Phase 3: UsageStats-Dienst nur, wenn Fokus-Bremse/Lautstärke ihn brauchen.
        viewModelScope.launch {
            combine(extrasSettings.settings, lockerRepository.settings.map { it.active }) { s, lockerActive -> s to lockerActive }
                .distinctUntilChanged()
                .collect { (s, lockerActive) ->
                    extrasCoordinator.apply(s, lockerActive)
                    if (!s.volumeEnabled) foregroundTracker.reset()
                }
        }
    }

    /** Beim Zurückkehren in die App (z. B. nach dem Einschalten der Bedienungshilfe) neu abgleichen. */
    fun onResume() {
        extrasCoordinator.sync()
    }

    /** Phase 4.2: „MobileManager mitsperren“. */
    suspend fun selfNeedsUnlock(): Boolean = locker.selfNeedsUnlock()

    fun setPendingDestination(route: String) {
        _pendingDestination.value = route
    }

    fun clearPendingDestination() {
        _pendingDestination.value = null
    }
}
