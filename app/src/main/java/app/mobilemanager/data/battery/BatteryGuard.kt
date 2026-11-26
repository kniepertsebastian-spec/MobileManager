package app.mobilemanager.data.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import app.mobilemanager.data.guard.GuardSettingsRepository
import app.mobilemanager.data.work.AppNotifications
import app.mobilemanager.domain.guard.ChargePolicy
import app.mobilemanager.domain.guard.HeatPolicy
import app.mobilemanager.domain.guard.NightReportPolicy
import app.mobilemanager.domain.model.BatteryStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Liest ACTION_BATTERY_CHANGED (Sticky-Broadcast) aus. */
@Singleton
class BatteryMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Aktueller Stand ohne dauerhafte Registrierung (Sticky-Intent). */
    fun current(): BatteryStatus? = ContextCompat.registerReceiver(
        context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
    )?.let(::parse)

    /** Live-Werte, solange gesammelt wird (UI, Wächter-Dienst). */
    fun observe(): Flow<BatteryStatus> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                parse(intent)?.let { trySend(it) }
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sticky?.let(::parse)?.let { trySend(it) }
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun parse(intent: Intent): BatteryStatus? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return BatteryStatus(
            levelPercent = level * 100 / scale,
            // Zehntel-Grad Celsius: 420 = 42,0 °C
            temperatureCelsius = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
            isPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
        )
    }
}

/**
 * Meilenstein 2.4.2: Hitze- und Lade-Alarm mit Hysterese – je Überschreitung genau eine Meldung.
 */
@Singleton
class BatteryGuard @Inject constructor(
    private val settings: GuardSettingsRepository,
    private val notifications: AppNotifications,
) {
    private val mutex = Mutex()

    suspend fun evaluate(status: BatteryStatus) = mutex.withLock {
        val s = settings.current()

        val heatAlerted = settings.heatAlerted()
        val heat = if (s.heatAlertEnabled) {
            HeatPolicy.evaluate(status.temperatureCelsius, s.heatThresholdCelsius.toFloat(), heatAlerted)
        } else {
            null
        }
        if (heat?.fire == true) notifications.showHeatWarning(status.temperatureCelsius)
        val newHeat = heat?.alerted ?: false
        if (newHeat != heatAlerted) settings.setHeatAlerted(newHeat)
        if (heatAlerted && !newHeat) notifications.cancelHeatWarning()

        val chargeAlerted = settings.chargeAlerted()
        val charge = if (s.chargeAlertEnabled) {
            ChargePolicy.evaluate(status.levelPercent, status.isPlugged, s.chargeLimitPercent, chargeAlerted)
        } else {
            null
        }
        if (charge?.fire == true) notifications.showChargeLimitReached(status.levelPercent)
        val newCharge = charge?.alerted ?: false
        if (newCharge != chargeAlerted) settings.setChargeAlerted(newCharge)
    }
}

/** Meilenstein 2.4.3: Akkuverlust über Nacht (nur wenn nicht am Kabel). */
@Singleton
class NightStandbyReporter @Inject constructor(
    private val monitor: BatteryMonitor,
    private val settings: GuardSettingsRepository,
) {
    suspend fun onNightStart() {
        val status = monitor.current() ?: return
        if (status.isPlugged) {
            settings.clearNightStart()
        } else {
            settings.setNightStart(status.levelPercent, System.currentTimeMillis())
        }
    }

    suspend fun onNightEnd() {
        val (startLevel, startTime) = settings.nightStart() ?: return
        settings.clearNightStart()
        val status = monitor.current() ?: return
        NightReportPolicy.build(startLevel, startTime, status.levelPercent, System.currentTimeMillis(), status.isPlugged)
            ?.let { settings.setNightReport(it) }
    }
}
