package app.mobilemanager.launcher.ui

import app.mobilemanager.launcher.R
import androidx.annotation.StringRes
import android.provider.Settings
import app.mobilemanager.launcher.data.QuickAction

/** Phase 4.7: Treffer für die Universal-Suche im Drawer (lokal, ohne Netz). */
object UniversalSearch {

    /** Suchbegriffe bleiben bewusst deutsch und klein (Tipp-Aliase); der angezeigte Name kommt aus den Ressourcen. */
    data class SettingsEntry(@StringRes val labelRes: Int, val action: String, val keywords: List<String>)

    val SETTINGS = listOf(
        SettingsEntry(R.string.launcher_setting_wifi, Settings.ACTION_WIFI_SETTINGS, listOf("wlan", "wifi", "internet")),
        SettingsEntry(R.string.launcher_setting_network, Settings.ACTION_WIRELESS_SETTINGS, listOf("netzwerk", "mobilfunk", "mobile daten", "flugmodus")),
        SettingsEntry(R.string.launcher_setting_bluetooth, Settings.ACTION_BLUETOOTH_SETTINGS, listOf("bluetooth", "kopfhörer", "koppeln")),
        SettingsEntry(R.string.launcher_setting_battery, android.content.Intent.ACTION_POWER_USAGE_SUMMARY, listOf("akku", "batterie", "energie")),
        SettingsEntry(R.string.launcher_setting_display, Settings.ACTION_DISPLAY_SETTINGS, listOf("display", "helligkeit", "bildschirm", "dunkel")),
        SettingsEntry(R.string.launcher_setting_sound, Settings.ACTION_SOUND_SETTINGS, listOf("ton", "töne", "lautstärke", "klingelton", "vibration")),
        SettingsEntry(R.string.launcher_setting_apps, Settings.ACTION_APPLICATION_SETTINGS, listOf("apps", "anwendungen")),
        SettingsEntry(R.string.launcher_setting_storage, Settings.ACTION_INTERNAL_STORAGE_SETTINGS, listOf("speicher", "platz")),
        SettingsEntry(R.string.launcher_setting_location, Settings.ACTION_LOCATION_SOURCE_SETTINGS, listOf("standort", "gps", "ortung")),
        SettingsEntry(R.string.launcher_setting_privacy, Settings.ACTION_PRIVACY_SETTINGS, listOf("datenschutz", "privatsphäre", "kamera", "mikrofon")),
        SettingsEntry(R.string.launcher_setting_security, Settings.ACTION_SECURITY_SETTINGS, listOf("sicherheit", "displaysperre", "fingerabdruck")),
        SettingsEntry(R.string.launcher_setting_accessibility, Settings.ACTION_ACCESSIBILITY_SETTINGS, listOf("bedienungshilfe", "barrierefreiheit")),
        SettingsEntry(R.string.launcher_setting_vpn, Settings.ACTION_VPN_SETTINGS, listOf("vpn")),
        SettingsEntry(R.string.launcher_setting_nfc, Settings.ACTION_NFC_SETTINGS, listOf("nfc", "bezahlen")),
        SettingsEntry(R.string.launcher_setting_date, Settings.ACTION_DATE_SETTINGS, listOf("datum", "uhrzeit", "zeitzone")),
        SettingsEntry(R.string.launcher_setting_languages, Settings.ACTION_LOCALE_SETTINGS, listOf("sprache", "tastatur")),
        SettingsEntry(R.string.launcher_setting_developer, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, listOf("entwickler", "debugging", "adb")),
        SettingsEntry(R.string.launcher_setting_about, Settings.ACTION_DEVICE_INFO_SETTINGS, listOf("telefon", "info", "version", "android")),
        SettingsEntry(R.string.launcher_setting_all, Settings.ACTION_SETTINGS, listOf("einstellungen", "settings")),
    )

    fun matchSettings(query: String, limit: Int = 4, label: (Int) -> String): List<SettingsEntry> {
        if (query.length < 2) return emptyList()
        val q = query.lowercase()
        return SETTINGS.filter { entry -> label(entry.labelRes).lowercase().contains(q) || entry.keywords.any { it.startsWith(q) || q.startsWith(it) } }
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
