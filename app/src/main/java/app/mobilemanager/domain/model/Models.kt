package app.mobilemanager.domain.model

import android.net.Uri

/* ---------- App-Stopper ---------- */

enum class ProtectionReason(val label: String) {
    SELF("Diese App"),
    LAUNCHER("Aktueller Launcher"),
    DIALER("Telefon"),
    SMS("SMS-App"),
    ALARM("Wecker"),
    KEYBOARD("Tastatur"),
    MESSENGER("Messenger"),
    SHIZUKU("Shizuku"),
    WHITELIST("Whitelist"),
}

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    /** Letzte Vordergrund-Nutzung in ms seit Epoch, `null` = im Auswertungszeitraum nie genutzt. */
    val lastUsed: Long?,
    /** Android-Flag FLAG_STOPPED: "Beenden erzwingen" ist in den App-Infos ausgegraut. */
    val isStopped: Boolean,
    /** Automatischer Schutz (Launcher, Telefon …) – hat Vorrang vor der Whitelist. */
    val autoProtection: ProtectionReason?,
    val isWhitelisted: Boolean,
) {
    val isProtected: Boolean get() = autoProtection != null || isWhitelisted
    val protectionReason: ProtectionReason?
        get() = autoProtection ?: if (isWhitelisted) ProtectionReason.WHITELIST else null

    fun inactiveForMillis(now: Long): Long? = lastUsed?.let { now - it }
}

/** Ergebnis der Inaktivitäts-Prüfung. */
data class InactiveAppsResult(
    val thresholdHours: Int,
    /** Alle (sichtbaren) Apps, sortiert nach längster Inaktivität. */
    val apps: List<InstalledApp>,
    /** Apps, die beim Aufräumen gestoppt würden. */
    val candidates: List<InstalledApp>,
)

enum class ForceStopMethod { SHIZUKU, ACCESSIBILITY }

sealed interface ForceStopResult {
    data object Success : ForceStopResult
    data class Failed(val reason: String) : ForceStopResult
    data object Skipped : ForceStopResult
}

data class StopProgress(
    val current: Int,
    val total: Int,
    val currentLabel: String,
    val succeeded: Int,
    val failed: Int,
    val finished: Boolean,
)

/* ---------- Speicher-Cleaner ---------- */

data class MediaImage(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    /** Aufnahme- bzw. Hinzufügedatum in ms seit Epoch. */
    val dateTaken: Long,
    val dateModified: Long,
    val bucket: String?,
    val relativePath: String?,
)

/** Gruppe identischer Bilder; [keep] bleibt standardmäßig geschützt. */
data class DuplicateGroup(
    val sha256: String,
    val keep: MediaImage,
    val duplicates: List<MediaImage>,
) {
    val all: List<MediaImage> get() = listOf(keep) + duplicates
    val wastedBytes: Long get() = duplicates.sumOf { it.sizeBytes }
}

data class ScanProgress(
    val phase: String,
    val processed: Int,
    val total: Int,
)

/* ---------- Bluetooth-Automatik ---------- */

data class BluetoothRule(
    val id: Long = 0,
    val deviceAddress: String,
    val deviceName: String,
    val targetVolume: Int?,
    val launchPackageName: String?,
    val eqProfileId: Int?,
    val isEnabled: Boolean = true,
)

data class PairedDevice(val address: String, val name: String)

data class LaunchableApp(val packageName: String, val label: String)

/**
 * Eingebaute EQ-Profile. Gains in Millibel für die fünf Standard-Mittenfrequenzen
 * 60 Hz, 230 Hz, 910 Hz, 3,6 kHz, 14 kHz – auf die tatsächlichen Bänder des Geräts gemappt.
 */
enum class EqProfile(val id: Int, val label: String, val gainsMilliBel: List<Int>) {
    FLAT(1, "Neutral", listOf(0, 0, 0, 0, 0)),
    BASS_BOOST(2, "Bass-Boost", listOf(600, 400, 0, 0, 100)),
    VOCAL(3, "Sprache / Podcast", listOf(-300, -100, 300, 400, 100)),
    TREBLE(4, "Höhen", listOf(-100, 0, 0, 300, 500)),
    CAR(5, "Auto", listOf(400, 200, -100, 200, 300));

    companion object {
        val centerFrequenciesHz = listOf(60, 230, 910, 3_600, 14_000)
        fun byId(id: Int?): EqProfile? = entries.firstOrNull { it.id == id }
    }
}

/* ---------- Berechtigungen ---------- */

enum class PermissionKind {
    USAGE_ACCESS,
    READ_MEDIA_IMAGES,
    BLUETOOTH_CONNECT,
    POST_NOTIFICATIONS,
    OVERLAY,
    BATTERY_OPTIMIZATION,
    SHIZUKU,
    ACCESSIBILITY,
}

enum class GrantState { GRANTED, PARTIAL, DENIED }

data class PermissionStatus(
    val kind: PermissionKind,
    val title: String,
    val description: String,
    val state: GrantState,
    val stateLabel: String,
    val required: Boolean,
)
