package app.mobilemanager.data.local

import androidx.room.Entity
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
