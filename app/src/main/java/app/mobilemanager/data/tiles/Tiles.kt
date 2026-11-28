package app.mobilemanager.data.tiles

import app.mobilemanager.di.AppCoroutines
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.mobilemanager.MainActivity
import app.mobilemanager.data.extras.ExtrasSettingsRepository
import app.mobilemanager.data.network.NetworkPolicyController
import app.mobilemanager.data.network.NetworkPolicyRepository
import app.mobilemanager.data.power.CaffeineService
import app.mobilemanager.data.protection.ProtectionRepository
import app.mobilemanager.data.tracker.TrackerSettingsRepository
import app.mobilemanager.data.tracker.TrackerVpnController
import app.mobilemanager.data.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Phase 4.6: gemeinsame Basis der Schnelleinstellungs-Kacheln. */
abstract class BaseTileService : TileService() {
    protected val scope = AppCoroutines.scope("Tiles", Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { render(currentState()) }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            toggle()
            render(currentState())
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    protected abstract suspend fun currentState(): Boolean?
    protected abstract suspend fun toggle()
    protected open fun subtitle(active: Boolean): String? = null

    private fun render(active: Boolean?) {
        val tile = qsTile ?: return
        tile.state = when (active) {
            true -> Tile.STATE_ACTIVE
            false -> Tile.STATE_INACTIVE
            null -> Tile.STATE_UNAVAILABLE
        }
        tile.subtitle = active?.let(::subtitle)
        tile.updateTile()
    }

    /** Öffnet MobileManager an der passenden Stelle (z. B. für die VPN-Zustimmung). */
    protected fun openApp(destination: String) {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_DESTINATION, destination)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            runCatching { startActivity(intent) }
        }
    }
}

@AndroidEntryPoint
class TrackerTileService : BaseTileService() {
    @Inject lateinit var settings: TrackerSettingsRepository
    @Inject lateinit var vpn: TrackerVpnController

    override suspend fun currentState() = settings.current().enabled

    override suspend fun toggle() {
        val enable = !settings.current().enabled
        when {
            !enable -> {
                settings.setEnabled(false)
                vpn.stop()
            }
            vpn.prepareIntent() != null || vpn.otherVpnActive() -> openApp(MainActivity.DESTINATION_TRACKER)
            else -> {
                settings.setEnabled(true)
                vpn.start()
            }
        }
    }
}

@AndroidEntryPoint
class FocusTileService : BaseTileService() {
    @Inject lateinit var extras: ExtrasSettingsRepository

    override suspend fun currentState() = extras.current().focusEnabled

    override suspend fun toggle() = extras.update { it.copy(focusEnabled = !it.focusEnabled) }
}

@AndroidEntryPoint
class FirewallTileService : BaseTileService() {
    @Inject lateinit var repository: NetworkPolicyRepository
    @Inject lateinit var controller: NetworkPolicyController

    override suspend fun currentState() = repository.current().firewallEnabled

    override suspend fun toggle() {
        repository.update { it.copy(firewallEnabled = !it.firewallEnabled) }
        controller.apply()
    }
}

@AndroidEntryPoint
class AppStopperTileService : BaseTileService() {
    @Inject lateinit var workScheduler: WorkScheduler

    override suspend fun currentState() = false

    override suspend fun toggle() = workScheduler.runAppStopperNow()

    override fun subtitle(active: Boolean) = "Inaktive Apps beenden"
}

@AndroidEntryPoint
class CaffeineTileService : BaseTileService() {
    @Inject lateinit var protection: ProtectionRepository

    override suspend fun currentState() = CaffeineService.isActive

    override suspend fun toggle() {
        if (CaffeineService.isActive) {
            CaffeineService.stop(this)
        } else if (!CaffeineService.start(this, protection.current().caffeineMinutes)) {
            openApp(MainActivity.DESTINATION_POWER_TOOLS)
        }
    }

    override fun subtitle(active: Boolean) = if (active) "Display bleibt an" else null
}
