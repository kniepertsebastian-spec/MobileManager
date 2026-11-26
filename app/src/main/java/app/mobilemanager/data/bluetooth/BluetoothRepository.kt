package app.mobilemanager.data.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import app.mobilemanager.data.local.BluetoothRuleDao
import app.mobilemanager.data.local.BluetoothRuleEntity
import app.mobilemanager.data.settings.SettingsRepository
import app.mobilemanager.domain.model.BluetoothRule
import app.mobilemanager.domain.model.PairedDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ruleDao: BluetoothRuleDao,
    private val settings: SettingsRepository,
) {
    private val manager: BluetoothManager? get() = context.getSystemService(BluetoothManager::class.java)

    val rules: Flow<List<BluetoothRule>> = ruleDao.observeAll().map { list -> list.map { it.toDomain() } }

    /** address -> name der aktuell verbundenen Geräte. */
    val connectedDevices: Flow<Map<String, String>> = settings.connectedBluetoothDevices

    fun hasConnectPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun isBluetoothEnabled(): Boolean = manager?.adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PairedDevice> {
        if (!hasConnectPermission()) return emptyList()
        return runCatching {
            manager?.adapter?.bondedDevices.orEmpty()
                .map { PairedDevice(it.address, it.name ?: it.address) }
                .sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    suspend fun saveRule(rule: BluetoothRule) {
        ruleDao.upsert(rule.toEntity())
    }

    suspend fun setRuleEnabled(id: Long, enabled: Boolean) = ruleDao.setEnabled(id, enabled)

    suspend fun deleteRule(rule: BluetoothRule) = ruleDao.delete(rule.toEntity())

    suspend fun enabledRulesFor(address: String): List<BluetoothRule> =
        ruleDao.getEnabledForDevice(address).map { it.toDomain() }

    /**
     * Gleicht den vom Receiver gepflegten Verbindungsstatus mit den tatsächlich über
     * A2DP/Headset/LE-Audio verbundenen Geräten ab (z. B. nach einem Neustart).
     */
    @SuppressLint("MissingPermission")
    suspend fun refreshConnectedDevices() {
        if (!hasConnectPermission() || !isBluetoothEnabled()) {
            settings.clearConnectedBluetoothDevices()
            return
        }
        val adapter = manager?.adapter ?: return
        val found = mutableMapOf<String, String>()
        for (profile in PROFILES) {
            val deferred = CompletableDeferred<BluetoothProfile?>()
            val requested = runCatching {
                adapter.getProfileProxy(
                    context,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                            deferred.complete(proxy)
                        }

                        override fun onServiceDisconnected(p: Int) {
                            deferred.complete(null)
                        }
                    },
                    profile,
                )
            }.getOrDefault(false)
            if (!requested) continue
            val proxy = withTimeoutOrNull(PROXY_TIMEOUT_MS) { deferred.await() } ?: continue
            runCatching {
                proxy.connectedDevices.forEach { found[it.address] = it.name ?: it.address }
            }
            runCatching { adapter.closeProfileProxy(profile, proxy) }
        }
        settings.clearConnectedBluetoothDevices()
        found.forEach { (address, name) -> settings.markBluetoothConnected(address, name) }
    }

    private fun BluetoothRuleEntity.toDomain() = BluetoothRule(
        id = id,
        deviceAddress = deviceAddress,
        deviceName = deviceName,
        targetVolume = targetVolume,
        launchPackageName = launchPackageName,
        eqProfileId = eqProfileId,
        isEnabled = isEnabled,
    )

    private fun BluetoothRule.toEntity() = BluetoothRuleEntity(
        id = id,
        deviceAddress = deviceAddress,
        deviceName = deviceName,
        targetVolume = targetVolume,
        launchPackageName = launchPackageName,
        eqProfileId = eqProfileId,
        isEnabled = isEnabled,
    )

    private companion object {
        val PROFILES = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, BluetoothProfile.LE_AUDIO)
        const val PROXY_TIMEOUT_MS = 1_500L
    }
}
