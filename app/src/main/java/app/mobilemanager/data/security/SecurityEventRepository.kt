package app.mobilemanager.data.security

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import app.mobilemanager.data.alerts.AlertChannelFactory
import app.mobilemanager.data.alerts.AlertConfigStore
import app.mobilemanager.data.guard.GuardSettingsRepository
import app.mobilemanager.data.local.SecurityEventDao
import app.mobilemanager.data.local.SecurityEventEntity
import app.mobilemanager.data.work.AppNotifications
import app.mobilemanager.data.work.WorkScheduler
import app.mobilemanager.domain.guard.CooldownPolicy
import app.mobilemanager.domain.model.AlertMessage
import app.mobilemanager.domain.model.AlertState
import app.mobilemanager.domain.model.SecurityEvent
import app.mobilemanager.domain.model.SecurityEventType
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zentrale "Benachrichtigungs-Engine": protokolliert Sicherheitsereignisse in Room und stellt
 * den Alarm-Versand (unter Beachtung der Sperrzeit) in die Offline-Warteschlange.
 */
@Singleton
class SecurityEventRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: SecurityEventDao,
    private val guardSettings: GuardSettingsRepository,
    private val alertConfig: AlertConfigStore,
    private val channels: AlertChannelFactory,
    private val workScheduler: WorkScheduler,
    private val notifications: AppNotifications,
    private val location: LocationHelper,
) {
    val events: Flow<List<SecurityEvent>> = dao.observeRecent().map { list -> list.map { it.toDomain() } }
    val unseenCount: Flow<Int> = dao.observeUnseenCount()

    suspend fun markAllSeen() = dao.markAllSeen()
    suspend fun clear() = dao.clear()

    /**
     * Protokolliert ein Ereignis. Bei Diebstahl-Indikatoren wird (falls aktiviert) sofort der
     * letzte bekannte Standort angehängt; der Versand selbst läuft über WorkManager.
     */
    suspend fun record(type: SecurityEventType, title: String, details: String): Long {
        val settings = guardSettings.current()
        val now = System.currentTimeMillis()
        val config = alertConfig.current()
        val state = when {
            !config.isConfigured -> AlertState.NOT_CONFIGURED
            !CooldownPolicy.canSend(guardSettings.lastAlertSent(type), now, settings.alertCooldownMinutes) ->
                AlertState.COOLDOWN
            else -> AlertState.QUEUED
        }
        val fix = if (type.isTheftIndicator && settings.attachLocation) location.lastKnown() else null
        val id = dao.insert(
            SecurityEventEntity(
                type = type.name,
                timestamp = now,
                title = title,
                details = details,
                latitude = fix?.latitude,
                longitude = fix?.longitude,
                alertState = state.name,
            ),
        )
        if (state == AlertState.QUEUED) {
            guardSettings.setLastAlertSent(type, now)
            workScheduler.enqueueAlert(id)
        }
        // Lokale Meldung nur bei harmlosen Ereignissen – ein Dieb soll den Alarm nicht sehen.
        if (!type.isTheftIndicator) notifications.showSecurityEvent(title, details)
        return id
    }

    /** Wird vom AlertSendWorker aufgerufen. @return true = erledigt, false = später erneut versuchen. */
    suspend fun deliver(eventId: Long, finalAttempt: Boolean): Boolean {
        val entity = dao.get(eventId) ?: return true
        val config = alertConfig.current()
        val channel = channels.create(config)
        if (channel == null || !config.isConfigured) {
            dao.setAlertState(eventId, AlertState.NOT_CONFIGURED.name)
            return true
        }
        var event = entity.toDomain()
        if (event.type.isTheftIndicator && event.latitude == null && guardSettings.current().attachLocation) {
            location.current()?.let { fix ->
                dao.setLocation(eventId, fix.latitude, fix.longitude)
                event = event.copy(latitude = fix.latitude, longitude = fix.longitude)
            }
        }
        return try {
            channel.send(buildMessage(event))
            dao.setAlertState(eventId, AlertState.SENT.name)
            true
        } catch (e: Exception) {
            if (finalAttempt) dao.setAlertState(eventId, AlertState.FAILED.name)
            false
        }
    }

    /** "Test-Alarm senden": prüft die Zugangsdaten sofort (ohne Warteschlange und Sperrzeit). */
    suspend fun sendTest(): Result<Unit> = runCatching {
        val config = alertConfig.current()
        val channel = channels.create(config) ?: error("Kein Alarm-Kanal ausgewählt")
        check(config.isConfigured) { "Bitte alle Pflichtfelder ausfüllen" }
        val fix = if (guardSettings.current().attachLocation) location.lastKnown() else null
        channel.send(
            buildMessage(
                SecurityEvent(
                    id = 0,
                    type = SecurityEventType.TEST,
                    timestamp = System.currentTimeMillis(),
                    title = "Test-Alarm",
                    details = "Wenn du diese Nachricht liest, funktioniert der Alarm-Versand von MobileManager.",
                    latitude = fix?.latitude,
                    longitude = fix?.longitude,
                    alertState = AlertState.SENT,
                    seen = true,
                ),
            ),
        )
    }

    private fun buildMessage(event: SecurityEvent): AlertMessage {
        val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(event.timestamp))
        val body = buildString {
            appendLine(event.title)
            if (event.details.isNotBlank()) appendLine(event.details)
            appendLine()
            appendLine("Zeitpunkt: $time")
            appendLine("Gerät: ${Build.MANUFACTURER} ${Build.MODEL}")
            if (event.type.isTheftIndicator || event.type == SecurityEventType.TEST) {
                appendLine("Standort: ${event.mapsLink ?: "nicht verfügbar"}")
            }
            appendLine()
            append("Gesendet von MobileManager auf deinem Gerät.")
        }
        return AlertMessage(subject = "⚠ MobileManager: ${event.type.label}", body = body)
    }

    private fun SecurityEventEntity.toDomain() = SecurityEvent(
        id = id,
        type = SecurityEventType.entries.firstOrNull { it.name == type } ?: SecurityEventType.TEST,
        timestamp = timestamp,
        title = title,
        details = details,
        latitude = latitude,
        longitude = longitude,
        alertState = AlertState.entries.firstOrNull { it.name == alertState } ?: AlertState.FAILED,
        seen = seen,
    )
}

/** Optionaler Standort für Diebstahl-Alarme über den FusedLocationProviderClient. */
@Singleton
class LocationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Schnell (Cache), geeignet für BroadcastReceiver. */
    @SuppressLint("MissingPermission") // geprüft in hasPermission()
    suspend fun lastKnown(): Location? {
        if (!hasPermission()) return null
        return withTimeoutOrNull(3_000) { runCatching { client.lastLocation.await() }.getOrNull() }
    }

    /** Aktiver Fix (bis zu 20 s), z. B. im Versand-Worker, wenn kein Cache-Standort vorlag. */
    @SuppressLint("MissingPermission")
    suspend fun current(): Location? {
        if (!hasPermission()) return null
        val cancellation = CancellationTokenSource()
        return try {
            withTimeoutOrNull(20_000) {
                runCatching {
                    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellation.token).await()
                }.getOrNull()
            } ?: lastKnown()
        } finally {
            cancellation.cancel()
        }
    }
}
