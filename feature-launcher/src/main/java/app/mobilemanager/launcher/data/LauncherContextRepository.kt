package app.mobilemanager.launcher.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import app.mobilemanager.launcher.domain.LauncherContext
import app.mobilemanager.launcher.domain.ProfileResolver
import app.mobilemanager.launcher.domain.ProfileRule
import app.mobilemanager.launcher.domain.RuleType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Aktives Profil plus Grund (Regel), falls automatisch gewechselt wurde. */
data class ActiveProfile(val profileId: Long, val rule: ProfileRule?)

/**
 * Meilenstein 3.2.3: Kontext für den automatischen Profilwechsel (Bluetooth, WLAN, Uhrzeit).
 * Die Flows laufen nur, solange der Homescreen sie beobachtet – im Hintergrund kostet das nichts.
 * Bluetooth-Verbindungen liefert der bestehende ACL-Receiver der Automatik über [LauncherHostBridge].
 */
@Singleton
class LauncherContextRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: LauncherHostBridge,
    private val layout: LauncherLayoutRepository,
    private val prefs: LauncherPreferences,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeProfile: Flow<ActiveProfile> = layout.rules.flatMapLatest { rules ->
        val needsWifi = rules.any { it.enabled && it.type == RuleType.WIFI }
        val needsTime = rules.any { it.enabled && it.type == RuleType.TIME }
        combine(
            prefs.settings,
            bridge.connectedBluetoothAddresses,
            if (needsWifi) wifiSsid() else flowOf(null),
            if (needsTime) minuteTicker() else flowOf(LocalDateTime.now()),
        ) { settings, bluetooth, ssid, now ->
            if (!settings.autoSwitch) return@combine ActiveProfile(settings.baseProfileId, null)
            val ctx = LauncherContext(bluetooth, ssid, now)
            val rule = ProfileResolver.activeRule(rules, ctx)
            ActiveProfile(rule?.profileId ?: settings.baseProfileId, rule)
        }
    }.distinctUntilChanged()

    fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** SSID des verbundenen WLANs (braucht Standortberechtigung und eingeschalteten Standort). */
    fun wifiSsid(): Flow<String?> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null || !hasLocationPermission()) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val ssid = (caps.transportInfo as? WifiInfo)?.ssid
                    ?.let(ProfileResolver::normalizeSsid)
                    ?.takeUnless { it.isBlank() || it == UNKNOWN_SSID }
                trySend(ssid)
            }

            override fun onLost(network: Network) {
                trySend(null)
            }
        }
        trySend(null)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { cm.registerNetworkCallback(request, callback) }
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    /** Aktuelle SSID für den Regel-Editor („aktuelles WLAN übernehmen“). */
    val currentSsid: Flow<String?> get() = wifiSsid()

    private fun minuteTicker(): Flow<LocalDateTime> = flow {
        while (true) {
            val now = LocalDateTime.now()
            emit(now)
            delay((60 - now.second) * 1000L + 50)
        }
    }.map { it.withSecond(0).withNano(0) }.distinctUntilChanged()

    private companion object {
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
