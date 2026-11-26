package app.mobilemanager.launcher.data

import kotlinx.coroutines.flow.Flow

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
}
