package app.mobilemanager.launcher.domain

import java.time.LocalDateTime

/** Momentaner Kontext für den automatischen Profilwechsel. */
data class LauncherContext(
    val connectedBluetooth: Set<String>,
    val wifiSsid: String?,
    val now: LocalDateTime,
)

/**
 * Bestimmt das aktive Profil. Priorität: Bluetooth > WLAN > Uhrzeit > manuell gewähltes Basisprofil.
 * Endet der Auslöser (z. B. Auto-Bluetooth getrennt), fällt das Ergebnis automatisch auf das Basisprofil zurück.
 */
object ProfileResolver {

    fun resolve(rules: List<ProfileRule>, context: LauncherContext, baseProfileId: Long): Long {
        val active = rules.filter { it.enabled && matches(it, context) }
        return active.minWithOrNull(compareBy<ProfileRule> { it.type.ordinal }.thenBy { it.id })?.profileId ?: baseProfileId
    }

    /** Welche Regel hat gegriffen (für die Anzeige „automatisch, weil …“). */
    fun activeRule(rules: List<ProfileRule>, context: LauncherContext): ProfileRule? =
        rules.filter { it.enabled && matches(it, context) }
            .minWithOrNull(compareBy<ProfileRule> { it.type.ordinal }.thenBy { it.id })

    fun matches(rule: ProfileRule, context: LauncherContext): Boolean = when (rule.type) {
        RuleType.BLUETOOTH -> rule.value.isNotBlank() && rule.value.uppercase() in context.connectedBluetooth.map { it.uppercase() }
        RuleType.WIFI -> rule.value.isNotBlank() && context.wifiSsid != null &&
            normalizeSsid(rule.value).equals(normalizeSsid(context.wifiSsid), ignoreCase = false)
        RuleType.TIME -> inTimeWindow(rule, context.now)
    }

    fun inTimeWindow(rule: ProfileRule, now: LocalDateTime): Boolean {
        val minute = now.hour * 60 + now.minute
        val dayBit = now.dayOfWeek.value - 1 // Montag = 0
        val start = rule.startMinutes
        val end = rule.endMinutes
        return when {
            start == end -> false
            start < end -> rule.dayEnabled(dayBit) && minute in start until end
            // Über Mitternacht: Beginn zählt zum Starttag, der Rest zum Folgetag.
            minute >= start -> rule.dayEnabled(dayBit)
            minute < end -> rule.dayEnabled((dayBit + 6) % 7)
            else -> false
        }
    }

    private fun ProfileRule.dayEnabled(bit: Int) = daysMask and (1 shl bit) != 0

    /** Android liefert SSIDs in Anführungszeichen. */
    fun normalizeSsid(ssid: String): String = ssid.trim().removeSurrounding("\"")
}
