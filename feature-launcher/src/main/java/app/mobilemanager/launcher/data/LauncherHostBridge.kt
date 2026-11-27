package app.mobilemanager.launcher.data

import android.content.Intent
import kotlinx.coroutines.flow.Flow

/**
 * Phase 4.7: Aktion der Haupt-App für die Universal-Suche („VPN an“, „Cache leeren“ …).
 * Mit [intent] öffnet der Launcher einen Bildschirm, sonst führt [LauncherHostBridge.runQuickAction] sie aus.
 */
data class QuickAction(val id: String, val label: String, val keywords: List<String>, val intent: Intent? = null)

/**
 * Schnittstelle zur Haupt-App (Phase 1 & 2). Das Launcher-Modul kennt die App nicht direkt –
 * die App stellt eine Implementierung per Hilt bereit.
 */
interface LauncherHostBridge {
    /** Adressen der aktuell verbundenen Bluetooth-Geräte (aus dem ACL-Receiver der Automatik). */
    val connectedBluetoothAddresses: Flow<Set<String>>

    /** Gekoppelte Geräte (Adresse → Name) für den Regel-Editor. */
    fun pairedDevices(): List<Pair<String, String>>

    /** Letzte Nutzung pro Paketname (UsageStatsManager), leer ohne Nutzungszugriff. */
    fun lastUsedByPackage(): Map<String, Long>

    /** Verbindungsstatus mit den tatsächlich verbundenen Geräten abgleichen (z. B. nach Neustart). */
    suspend fun refreshConnectedBluetooth()

    /** Phase 4.7: Fokus-Bremse aktiv? (Graustufen-/Textmodus koppeln) */
    val focusModeActive: Flow<Boolean>

    /** Phase 4.7: globale Aktion über die Bedienungshilfe (AccessibilityService.GLOBAL_ACTION_*). */
    fun performGlobalAction(action: Int): Boolean

    fun quickActions(): List<QuickAction>

    /** @return kurze Rückmeldung für den Nutzer. */
    suspend fun runQuickAction(id: String): String
}
