package app.mobilemanager.launcher.ui

import android.provider.Settings
import app.mobilemanager.launcher.data.QuickAction

/** Phase 4.7: Treffer für die Universal-Suche im Drawer (lokal, ohne Netz). */
object UniversalSearch {

    data class SettingsEntry(val label: String, val action: String, val keywords: List<String>)

    val SETTINGS = listOf(
        SettingsEntry("WLAN", Settings.ACTION_WIFI_SETTINGS, listOf("wlan", "wifi", "internet")),
        SettingsEntry("Netzwerk & Internet", Settings.ACTION_WIRELESS_SETTINGS, listOf("netzwerk", "mobilfunk", "mobile daten", "flugmodus")),
        SettingsEntry("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, listOf("bluetooth", "kopfhörer", "koppeln")),
        SettingsEntry("Akku", android.content.Intent.ACTION_POWER_USAGE_SUMMARY, listOf("akku", "batterie", "energie")),
        SettingsEntry("Display", Settings.ACTION_DISPLAY_SETTINGS, listOf("display", "helligkeit", "bildschirm", "dunkel")),
        SettingsEntry("Töne & Vibration", Settings.ACTION_SOUND_SETTINGS, listOf("ton", "töne", "lautstärke", "klingelton", "vibration")),
        SettingsEntry("Apps", Settings.ACTION_APPLICATION_SETTINGS, listOf("apps", "anwendungen")),
        SettingsEntry("Speicher", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, listOf("speicher", "platz")),
        SettingsEntry("Standort", Settings.ACTION_LOCATION_SOURCE_SETTINGS, listOf("standort", "gps", "ortung")),
        SettingsEntry("Datenschutz", Settings.ACTION_PRIVACY_SETTINGS, listOf("datenschutz", "privatsphäre", "kamera", "mikrofon")),
        SettingsEntry("Sicherheit", Settings.ACTION_SECURITY_SETTINGS, listOf("sicherheit", "displaysperre", "fingerabdruck")),
        SettingsEntry("Bedienungshilfen", Settings.ACTION_ACCESSIBILITY_SETTINGS, listOf("bedienungshilfe", "barrierefreiheit")),
        SettingsEntry("VPN", Settings.ACTION_VPN_SETTINGS, listOf("vpn")),
        SettingsEntry("NFC", Settings.ACTION_NFC_SETTINGS, listOf("nfc", "bezahlen")),
        SettingsEntry("Datum & Uhrzeit", Settings.ACTION_DATE_SETTINGS, listOf("datum", "uhrzeit", "zeitzone")),
        SettingsEntry("Sprachen", Settings.ACTION_LOCALE_SETTINGS, listOf("sprache", "tastatur")),
        SettingsEntry("Entwickleroptionen", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, listOf("entwickler", "debugging", "adb")),
        SettingsEntry("Über das Telefon", Settings.ACTION_DEVICE_INFO_SETTINGS, listOf("telefon", "info", "version", "android")),
        SettingsEntry("Einstellungen", Settings.ACTION_SETTINGS, listOf("einstellungen", "settings")),
    )

    fun matchSettings(query: String, limit: Int = 4): List<SettingsEntry> {
        if (query.length < 2) return emptyList()
        val q = query.lowercase()
        return SETTINGS.filter { entry -> entry.label.lowercase().contains(q) || entry.keywords.any { it.startsWith(q) || q.startsWith(it) } }
            .take(limit)
    }

    fun matchActions(actions: List<QuickAction>, query: String, limit: Int = 4): List<QuickAction> {
        if (query.length < 2) return emptyList()
        val q = query.lowercase()
        return actions.filter { action ->
            action.label.lowercase().contains(q) || action.keywords.any { it.contains(q) || q.contains(it) }
        }.take(limit)
    }
}
