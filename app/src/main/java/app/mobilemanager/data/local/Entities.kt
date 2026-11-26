package app.mobilemanager.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Apps, die der Nutzer manuell vor dem App-Stopper geschützt hat. */
@Entity(tableName = "whitelisted_apps")
data class WhitelistedAppEntity(
    @PrimaryKey val packageName: String,
    val addedAt: Long,
)

/** Regel: Beim Verbinden mit [deviceAddress] Lautstärke setzen, App starten, EQ aktivieren. */
@Entity(tableName = "bluetooth_rules")
data class BluetoothRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceAddress: String,
    val deviceName: String,
    /** Ziel-Medienlautstärke in Prozent (0–100), `null` = nicht ändern. */
    val targetVolume: Int?,
    val launchPackageName: String?,
    /** Siehe [app.mobilemanager.domain.model.EqProfile], `null` = kein EQ. */
    val eqProfileId: Int?,
    val isEnabled: Boolean,
)

/** Protokoll eines Aufräumvorgangs (App-Stopper oder Speicher-Cleaner). */
@Entity(tableName = "cleanup_history")
data class CleanupHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val freedBytes: Long,
    val stoppedAppsCount: Int,
)

/**
 * Cache für den Speicher-Cleaner: SHA-256 und ML-Kit-Ergebnis pro MediaStore-Bild,
 * damit unveränderte Bilder nicht erneut gehasht/analysiert werden.
 * Ein Eintrag ist nur gültig, solange [dateModified] und [sizeBytes] übereinstimmen.
 */
@Entity(tableName = "image_analysis")
data class ImageAnalysisEntity(
    @PrimaryKey val mediaId: Long,
    val dateModified: Long,
    val sizeBytes: Long,
    val sha256: String? = null,
    /** `null` = noch nicht von ML Kit geprüft. */
    val hasFaces: Boolean? = null,
    val decision: Int = DECISION_NONE,
    val updatedAt: Long,
) {
    companion object {
        const val DECISION_NONE = 0
        /** Nutzer hat nach rechts gewischt: nicht erneut vorschlagen. */
        const val DECISION_KEEP = 1
    }
}

/* ---------- Phase 2 ---------- */

/** Protokolliertes Sicherheitsereignis (Security-Log). */
@Entity(tableName = "security_events", indices = [Index("timestamp")])
data class SecurityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [app.mobilemanager.domain.model.SecurityEventType] als Name. */
    val type: String,
    val timestamp: Long,
    val title: String,
    val details: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** [app.mobilemanager.domain.model.AlertState] als Name. */
    val alertState: String,
    val seen: Boolean = false,
)

/**
 * Archivierte Benachrichtigung (7 Tage). Der Unique-Index dedupliziert identische Updates
 * derselben Benachrichtigung.
 */
@Entity(
    tableName = "notification_entries",
    indices = [
        Index(value = ["packageName", "notificationKey", "title", "text"], unique = true),
        Index("postedAt"),
    ],
)
data class NotificationEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notificationKey: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val digestState: Int = DIGEST_NONE,
) {
    companion object {
        const val DIGEST_NONE = 0
        /** Abgefangen, wartet auf die nächste Sammel-Benachrichtigung. */
        const val DIGEST_PENDING = 1
        const val DIGEST_DELIVERED = 2
    }
}

/** Alte Screenshots/Downloads, die der Nutzer in der Swipe-Ansicht behalten hat. */
@Entity(tableName = "kept_expired_files")
data class KeptExpiredFileEntity(
    @PrimaryKey val mediaId: Long,
    val keptAt: Long,
)

// ---------------------------------------------------------------- Phase 3

/** Meilenstein 3.3.3: blockierte Tracker-Anfragen pro App und Tag (aggregiert, keine Einzelpakete). */
@Entity(tableName = "tracker_app_stats", primaryKeys = ["day", "packageName"])
data class TrackerAppStatEntity(
    /** Datum als yyyyMMdd. */
    val day: Int,
    val packageName: String,
    val blocked: Int,
)

@Entity(tableName = "tracker_domain_stats", primaryKeys = ["day", "domain"])
data class TrackerDomainStatEntity(
    val day: Int,
    val domain: String,
    val blocked: Int,
)

/** Meilenstein 3.4.2: gewünschte Medienlautstärke pro App. */
@Entity(tableName = "app_volume_rules")
data class AppVolumeRuleEntity(
    @PrimaryKey val packageName: String,
    val volumePercent: Int,
)
