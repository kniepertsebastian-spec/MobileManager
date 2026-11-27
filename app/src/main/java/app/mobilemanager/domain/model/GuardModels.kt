package app.mobilemanager.domain.model

/* ---------- Security-Events ---------- */

enum class SecurityEventType(val label: String, val isTheftIndicator: Boolean) {
    FAILED_UNLOCK("Fehlgeschlagene Entsperrversuche", true),
    SIDELOAD_INSTALL("App außerhalb des Play Stores installiert", false),
    SIM_CHANGED("SIM-Karte geändert oder entfernt", true),
    DEVICE_ADMIN_DISABLED("Geräteadministrator deaktiviert", false),
    TEST("Test-Alarm", false),
    LOCK_FAILED("Fehlversuche an der App-Sperre", false),
    PROTECTION_INTERRUPTED("Schutzfunktion unterbrochen", false),
    PERMISSION_ADDED("Neue kritische Berechtigung nach Update", false),
    PROTECTED_SCREEN("Geschützter Systembildschirm geöffnet", false),
}

/** Versandstatus eines Sicherheitsereignisses. */
enum class AlertState(val label: String) {
    NOT_CONFIGURED("Kein Alarm-Kanal eingerichtet"),
    QUEUED("Wartet auf Internet"),
    SENT("Alarm gesendet"),
    COOLDOWN("Unterdrückt (Sperrzeit)"),
    FAILED("Versand fehlgeschlagen"),
}

data class SecurityEvent(
    val id: Long,
    val type: SecurityEventType,
    val timestamp: Long,
    val title: String,
    val details: String,
    val latitude: Double?,
    val longitude: Double?,
    val alertState: AlertState,
    val seen: Boolean,
) {
    val mapsLink: String?
        get() = if (latitude != null && longitude != null) mapsLink(latitude, longitude) else null

    companion object {
        fun mapsLink(latitude: Double, longitude: Double) = "https://maps.google.com/?q=$latitude,$longitude"
    }
}

/* ---------- Alarm-Versand ---------- */

enum class AlertChannelType(val label: String) {
    NONE("Aus"),
    SMTP("E-Mail (SMTP)"),
    RESEND("E-Mail (Resend-API)"),
    TELEGRAM("Telegram-Bot"),
    NTFY("ntfy"),
}

enum class SmtpSecurity(val label: String, val defaultPort: Int) {
    SSL_TLS("SSL/TLS", 465),
    STARTTLS("STARTTLS", 587),
}

/** Zugangsdaten des Alarm-Kanals. Wird ausschließlich verschlüsselt (Android Keystore) gespeichert. */
data class AlertConfig(
    val channel: AlertChannelType = AlertChannelType.NONE,
    val smtpHost: String = "",
    val smtpPort: Int = SmtpSecurity.SSL_TLS.defaultPort,
    val smtpSecurity: SmtpSecurity = SmtpSecurity.SSL_TLS,
    val smtpUser: String = "",
    val smtpPassword: String = "",
    val mailFrom: String = "",
    val mailTo: String = "",
    val resendApiKey: String = "",
    val telegramToken: String = "",
    val telegramChatId: String = "",
    val ntfyServer: String = "https://ntfy.sh",
    val ntfyTopic: String = "",
    val ntfyToken: String = "",
) {
    val isConfigured: Boolean
        get() = when (channel) {
            AlertChannelType.NONE -> false
            AlertChannelType.SMTP -> smtpHost.isNotBlank() && smtpPort in 1..65535 &&
                smtpUser.isNotBlank() && smtpPassword.isNotEmpty() && mailTo.isNotBlank()
            AlertChannelType.RESEND -> resendApiKey.isNotBlank() && mailFrom.isNotBlank() && mailTo.isNotBlank()
            AlertChannelType.TELEGRAM -> telegramToken.isNotBlank() && telegramChatId.isNotBlank()
            AlertChannelType.NTFY -> ntfyServer.startsWith("https://") && ntfyTopic.isNotBlank()
        }
}

data class AlertMessage(val subject: String, val body: String)

/* ---------- Berechtigungs-Audit ---------- */

enum class CriticalPermission(val permission: String, val label: String) {
    BACKGROUND_LOCATION("android.permission.ACCESS_BACKGROUND_LOCATION", "Standort im Hintergrund"),
    CAMERA("android.permission.CAMERA", "Kamera"),
    RECORD_AUDIO("android.permission.RECORD_AUDIO", "Mikrofon"),
    READ_CONTACTS("android.permission.READ_CONTACTS", "Kontakte"),
    MANAGE_EXTERNAL_STORAGE("android.permission.MANAGE_EXTERNAL_STORAGE", "Alle Dateien"),
}

enum class AuditRisk(val label: String) {
    HIGH("Hohes Risiko"),
    MEDIUM("Mittleres Risiko"),
    LOW("Geringes Risiko"),
}

data class AuditedApp(
    val packageName: String,
    val label: String,
    val grantedPermissions: List<CriticalPermission>,
    val lastUsed: Long?,
    val risk: AuditRisk,
)

/* ---------- Akku ---------- */

data class BatteryStatus(
    val levelPercent: Int,
    val temperatureCelsius: Float,
    val isPlugged: Boolean,
)

data class NightReport(
    val startLevel: Int,
    val endLevel: Int,
    val startTime: Long,
    val endTime: Long,
) {
    val dropPercent: Int get() = startLevel - endLevel
    val hours: Double get() = (endTime - startTime).coerceAtLeast(0) / 3_600_000.0
}

/* ---------- Benachrichtigungen ---------- */

data class ArchivedNotification(
    val id: Long,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val isDigest: Boolean,
)

/** Alte Screenshots und Downloads (Meilenstein 2.3). */
enum class ExpiredFileKind(val label: String) {
    SCREENSHOT("Screenshot"),
    DOWNLOAD("Download"),
}
