package app.mobilemanager.domain.extras

/** Art des aktuellen Standardnetzes – entscheidet über Firewall-Regeln und Privates DNS. */
enum class NetType(val label: String) { WIFI("WLAN"), MOBILE("Mobilfunk"), NONE("Kein Netz") }

/** Privates DNS je Netzart. [UNCHANGED] lässt die Systemeinstellung in Ruhe. */
enum class PrivateDnsMode(val label: String) {
    UNCHANGED("Nicht ändern"),
    OFF("Aus"),
    AUTOMATIC("Automatisch"),
    HOSTNAME("Anbieter"),
}

/**
 * Phase 4.1: Per-App-Firewall. Gesperrt wird pro Netzart; der Screen-Off-Kill-Switch sperrt zusätzlich
 * die markierten Apps, solange das Display lange genug aus ist. Geschützte Apps werden nie gesperrt.
 */
object FirewallPolicy {
    fun denied(
        net: NetType,
        blockWifi: Set<String>,
        blockMobile: Set<String>,
        killSwitchActive: Boolean,
        killSwitchPackages: Set<String>,
        protected: Set<String>,
    ): Set<String> {
        val base = when (net) {
            NetType.WIFI -> blockWifi
            NetType.MOBILE -> blockMobile
            NetType.NONE -> blockWifi intersect blockMobile
        }
        val kill = if (killSwitchActive) killSwitchPackages else emptySet()
        return (base + kill) - protected
    }

    /** Welche Pakete müssen gesperrt bzw. wieder freigegeben werden? */
    fun diff(applied: Set<String>, target: Set<String>): Pair<Set<String>, Set<String>> = (target - applied) to (applied - target)
}

/** Phase 4.1: Tageslimit für mobile Daten – höchstens eine Warnung pro Tag. */
object DataLimitPolicy {
    fun shouldWarn(usedBytes: Long, limitMb: Int, lastWarnedDay: Int, today: Int): Boolean =
        limitMb > 0 && lastWarnedDay != today && usedBytes >= limitMb.toLong() * 1024 * 1024
}

/** Phase 4.1: Bewertung eines WLANs anhand des Sicherheitstyps (WifiInfo.getCurrentSecurityType). */
object WifiSafetyPolicy {
    // Werte aus android.net.wifi.WifiInfo.SECURITY_TYPE_* (öffentliche API ab Android 12).
    const val SECURITY_TYPE_OPEN = 0
    const val SECURITY_TYPE_WEP = 1
    const val SECURITY_TYPE_OWE = 6

    /** @return Warntext oder `null`, wenn das Netz unauffällig ist. */
    fun warning(securityType: Int, captivePortal: Boolean): String? = when {
        securityType == SECURITY_TYPE_OPEN -> "Offenes WLAN ohne Verschlüsselung – andere im Netz können unverschlüsselten Verkehr mitlesen."
        securityType == SECURITY_TYPE_WEP -> "WLAN mit veralteter WEP-Verschlüsselung – gilt als geknackt."
        captivePortal -> "WLAN mit Anmeldeseite (Captive Portal) – prüfe, ob das Netz vertrauenswürdig ist."
        else -> null
    }
}
