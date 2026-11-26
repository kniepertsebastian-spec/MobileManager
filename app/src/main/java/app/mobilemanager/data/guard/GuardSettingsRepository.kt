package app.mobilemanager.data.guard

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.mobilemanager.domain.model.NightReport
import app.mobilemanager.domain.model.SecurityEventType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.guardStore: DataStore<Preferences> by preferencesDataStore(name = "guard_settings")

/** Vom Nutzer einstellbare Optionen der Wächter (keine Geheimnisse – die liegen im [app.mobilemanager.data.alerts.AlertConfigStore]). */
data class GuardSettings(
    // 2.1 Security
    val failedUnlockThreshold: Int = 3,
    val sideloadWatchEnabled: Boolean = true,
    val simWatchEnabled: Boolean = false,
    val attachLocation: Boolean = false,
    // 2.2 Alarme
    val alertCooldownMinutes: Int = 10,
    // 2.3 Datei-Ablauf
    val expiredFilesEnabled: Boolean = true,
    val expiredFilesAgeDays: Int = 14,
    // 2.4 Akku
    val heatAlertEnabled: Boolean = true,
    val heatThresholdCelsius: Int = 42,
    val chargeAlertEnabled: Boolean = true,
    val chargeLimitPercent: Int = 80,
    val realtimeServiceEnabled: Boolean = false,
    val nightReportEnabled: Boolean = true,
    val nightStartMinutes: Int = 23 * 60,
    val nightEndMinutes: Int = 7 * 60,
    // 2.5 Benachrichtigungen
    val notificationArchiveEnabled: Boolean = true,
    val digestEnabled: Boolean = false,
    val digestApps: Set<String> = emptySet(),
    val digestKeywords: Set<String> = emptySet(),
    val digestTimes: Set<Int> = setOf(12 * 60, 19 * 60),
) {
    /** Braucht es den 15-Minuten-Check (Sideload, SIM, Akku)? */
    val needsPeriodicCheck: Boolean
        get() = sideloadWatchEnabled || simWatchEnabled || heatAlertEnabled || chargeAlertEnabled
}

@Singleton
class GuardSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val FAILED_THRESHOLD = intPreferencesKey("failed_unlock_threshold")
        val SIDELOAD_WATCH = booleanPreferencesKey("sideload_watch")
        val SIM_WATCH = booleanPreferencesKey("sim_watch")
        val ATTACH_LOCATION = booleanPreferencesKey("attach_location")
        val COOLDOWN = intPreferencesKey("alert_cooldown_minutes")
        val EXPIRED_ENABLED = booleanPreferencesKey("expired_files_enabled")
        val EXPIRED_AGE = intPreferencesKey("expired_files_age_days")
        val HEAT_ENABLED = booleanPreferencesKey("heat_alert_enabled")
        val HEAT_THRESHOLD = intPreferencesKey("heat_threshold")
        val CHARGE_ENABLED = booleanPreferencesKey("charge_alert_enabled")
        val CHARGE_LIMIT = intPreferencesKey("charge_limit")
        val REALTIME = booleanPreferencesKey("realtime_service")
        val NIGHT_REPORT = booleanPreferencesKey("night_report_enabled")
        val NIGHT_START = intPreferencesKey("night_start_minutes")
        val NIGHT_END = intPreferencesKey("night_end_minutes")
        val ARCHIVE = booleanPreferencesKey("notification_archive")
        val DIGEST = booleanPreferencesKey("digest_enabled")
        val DIGEST_APPS = stringSetPreferencesKey("digest_apps")
        val DIGEST_KEYWORDS = stringSetPreferencesKey("digest_keywords")
        val DIGEST_TIMES = stringSetPreferencesKey("digest_times")

        // Laufzeitzustand
        val SIM_BASELINE = stringPreferencesKey("sim_baseline")
        val SIM_LAST_ALERTED = stringPreferencesKey("sim_last_alerted")
        val PKG_SEQUENCE = intPreferencesKey("package_change_sequence")
        val PKG_LAST_CHECK = longPreferencesKey("package_last_check")
        val HEAT_ALERTED = booleanPreferencesKey("heat_alerted")
        val CHARGE_ALERTED = booleanPreferencesKey("charge_alerted")
        val NIGHT_START_LEVEL = intPreferencesKey("night_start_level")
        val NIGHT_START_TIME = longPreferencesKey("night_start_time")
        val REPORT_START_LEVEL = intPreferencesKey("report_start_level")
        val REPORT_END_LEVEL = intPreferencesKey("report_end_level")
        val REPORT_START_TIME = longPreferencesKey("report_start_time")
        val REPORT_END_TIME = longPreferencesKey("report_end_time")
        val EXPIRED_COUNT = intPreferencesKey("expired_files_count")

        fun cooldown(type: SecurityEventType) = longPreferencesKey("last_alert_${type.name}")
    }

    private val store get() = context.guardStore

    val settings: Flow<GuardSettings> = store.data.map(::fromPrefs)

    private fun fromPrefs(p: Preferences): GuardSettings {
        val defaults = GuardSettings()
        return GuardSettings(
            failedUnlockThreshold = p[Keys.FAILED_THRESHOLD] ?: defaults.failedUnlockThreshold,
            sideloadWatchEnabled = p[Keys.SIDELOAD_WATCH] ?: defaults.sideloadWatchEnabled,
            simWatchEnabled = p[Keys.SIM_WATCH] ?: defaults.simWatchEnabled,
            attachLocation = p[Keys.ATTACH_LOCATION] ?: defaults.attachLocation,
            alertCooldownMinutes = p[Keys.COOLDOWN] ?: defaults.alertCooldownMinutes,
            expiredFilesEnabled = p[Keys.EXPIRED_ENABLED] ?: defaults.expiredFilesEnabled,
            expiredFilesAgeDays = p[Keys.EXPIRED_AGE] ?: defaults.expiredFilesAgeDays,
            heatAlertEnabled = p[Keys.HEAT_ENABLED] ?: defaults.heatAlertEnabled,
            heatThresholdCelsius = p[Keys.HEAT_THRESHOLD] ?: defaults.heatThresholdCelsius,
            chargeAlertEnabled = p[Keys.CHARGE_ENABLED] ?: defaults.chargeAlertEnabled,
            chargeLimitPercent = p[Keys.CHARGE_LIMIT] ?: defaults.chargeLimitPercent,
            realtimeServiceEnabled = p[Keys.REALTIME] ?: defaults.realtimeServiceEnabled,
            nightReportEnabled = p[Keys.NIGHT_REPORT] ?: defaults.nightReportEnabled,
            nightStartMinutes = p[Keys.NIGHT_START] ?: defaults.nightStartMinutes,
            nightEndMinutes = p[Keys.NIGHT_END] ?: defaults.nightEndMinutes,
            notificationArchiveEnabled = p[Keys.ARCHIVE] ?: defaults.notificationArchiveEnabled,
            digestEnabled = p[Keys.DIGEST] ?: defaults.digestEnabled,
            digestApps = p[Keys.DIGEST_APPS] ?: defaults.digestApps,
            digestKeywords = p[Keys.DIGEST_KEYWORDS] ?: defaults.digestKeywords,
            digestTimes = p[Keys.DIGEST_TIMES]?.mapNotNull { it.toIntOrNull() }?.toSet() ?: defaults.digestTimes,
        )
    }

    suspend fun current(): GuardSettings = settings.first()

    suspend fun update(transform: (GuardSettings) -> GuardSettings) {
        store.edit { p ->
            val s = transform(fromPrefs(p))
            p[Keys.FAILED_THRESHOLD] = s.failedUnlockThreshold
            p[Keys.SIDELOAD_WATCH] = s.sideloadWatchEnabled
            p[Keys.SIM_WATCH] = s.simWatchEnabled
            p[Keys.ATTACH_LOCATION] = s.attachLocation
            p[Keys.COOLDOWN] = s.alertCooldownMinutes
            p[Keys.EXPIRED_ENABLED] = s.expiredFilesEnabled
            p[Keys.EXPIRED_AGE] = s.expiredFilesAgeDays
            p[Keys.HEAT_ENABLED] = s.heatAlertEnabled
            p[Keys.HEAT_THRESHOLD] = s.heatThresholdCelsius
            p[Keys.CHARGE_ENABLED] = s.chargeAlertEnabled
            p[Keys.CHARGE_LIMIT] = s.chargeLimitPercent
            p[Keys.REALTIME] = s.realtimeServiceEnabled
            p[Keys.NIGHT_REPORT] = s.nightReportEnabled
            p[Keys.NIGHT_START] = s.nightStartMinutes
            p[Keys.NIGHT_END] = s.nightEndMinutes
            p[Keys.ARCHIVE] = s.notificationArchiveEnabled
            p[Keys.DIGEST] = s.digestEnabled
            p[Keys.DIGEST_APPS] = s.digestApps
            p[Keys.DIGEST_KEYWORDS] = s.digestKeywords
            p[Keys.DIGEST_TIMES] = s.digestTimes.map { it.toString() }.toSet()
        }
    }

    /* ---------- Laufzeitzustand ---------- */

    suspend fun simBaseline(): String? = store.data.first()[Keys.SIM_BASELINE]
    suspend fun simLastAlerted(): String? = store.data.first()[Keys.SIM_LAST_ALERTED]
    val simBaselineFlow: Flow<String?> = store.data.map { it[Keys.SIM_BASELINE] }

    suspend fun setSimBaseline(value: String) = store.edit {
        it[Keys.SIM_BASELINE] = value
        it.remove(Keys.SIM_LAST_ALERTED)
    }

    suspend fun setSimLastAlerted(value: String) = store.edit { it[Keys.SIM_LAST_ALERTED] = value }

    suspend fun packageCheckpoint(): Pair<Int, Long>? {
        val p = store.data.first()
        val seq = p[Keys.PKG_SEQUENCE] ?: return null
        return seq to (p[Keys.PKG_LAST_CHECK] ?: 0L)
    }

    suspend fun setPackageCheckpoint(sequence: Int, time: Long) = store.edit {
        it[Keys.PKG_SEQUENCE] = sequence
        it[Keys.PKG_LAST_CHECK] = time
    }

    suspend fun heatAlerted(): Boolean = store.data.first()[Keys.HEAT_ALERTED] ?: false
    suspend fun chargeAlerted(): Boolean = store.data.first()[Keys.CHARGE_ALERTED] ?: false
    suspend fun setHeatAlerted(value: Boolean) = store.edit { it[Keys.HEAT_ALERTED] = value }
    suspend fun setChargeAlerted(value: Boolean) = store.edit { it[Keys.CHARGE_ALERTED] = value }

    suspend fun lastAlertSent(type: SecurityEventType): Long? = store.data.first()[Keys.cooldown(type)]
    suspend fun setLastAlertSent(type: SecurityEventType, time: Long) = store.edit { it[Keys.cooldown(type)] = time }

    suspend fun nightStart(): Pair<Int, Long>? {
        val p = store.data.first()
        val level = p[Keys.NIGHT_START_LEVEL] ?: return null
        return level to (p[Keys.NIGHT_START_TIME] ?: return null)
    }

    suspend fun setNightStart(level: Int, time: Long) = store.edit {
        it[Keys.NIGHT_START_LEVEL] = level
        it[Keys.NIGHT_START_TIME] = time
    }

    suspend fun clearNightStart() = store.edit {
        it.remove(Keys.NIGHT_START_LEVEL)
        it.remove(Keys.NIGHT_START_TIME)
    }

    val nightReport: Flow<NightReport?> = store.data.map { p ->
        NightReport(
            startLevel = p[Keys.REPORT_START_LEVEL] ?: return@map null,
            endLevel = p[Keys.REPORT_END_LEVEL] ?: return@map null,
            startTime = p[Keys.REPORT_START_TIME] ?: return@map null,
            endTime = p[Keys.REPORT_END_TIME] ?: return@map null,
        )
    }

    suspend fun setNightReport(report: NightReport) = store.edit {
        it[Keys.REPORT_START_LEVEL] = report.startLevel
        it[Keys.REPORT_END_LEVEL] = report.endLevel
        it[Keys.REPORT_START_TIME] = report.startTime
        it[Keys.REPORT_END_TIME] = report.endTime
    }

    val expiredFilesCount: Flow<Int?> = store.data.map { it[Keys.EXPIRED_COUNT] }
    suspend fun setExpiredFilesCount(count: Int) = store.edit { it[Keys.EXPIRED_COUNT] = count }
}
