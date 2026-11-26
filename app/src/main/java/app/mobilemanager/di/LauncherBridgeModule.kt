package app.mobilemanager.di

import app.mobilemanager.data.apps.InstalledAppsRepository
import app.mobilemanager.data.bluetooth.BluetoothRepository
import app.mobilemanager.data.settings.SettingsRepository
import app.mobilemanager.launcher.data.LauncherHostBridge
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Verbindet das Launcher-Modul mit Phase 1: Bluetooth-Verbindungen und Nutzungsdaten. */
@Singleton
class LauncherHostBridgeImpl @Inject constructor(
    private val settings: SettingsRepository,
    private val bluetooth: BluetoothRepository,
    private val apps: InstalledAppsRepository,
) : LauncherHostBridge {
    override val connectedBluetoothAddresses: Flow<Set<String>> =
        settings.connectedBluetoothDevices.map { it.keys }.distinctUntilChanged()

    override fun pairedDevices(): List<Pair<String, String>> = bluetooth.pairedDevices().map { it.address to it.name }

    override fun lastUsedByPackage(): Map<String, Long> = apps.lastUsedMap()

    override suspend fun refreshConnectedBluetooth() = bluetooth.refreshConnectedDevices()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LauncherBridgeModule {
    @Binds
    abstract fun bindLauncherHostBridge(impl: LauncherHostBridgeImpl): LauncherHostBridge
}
