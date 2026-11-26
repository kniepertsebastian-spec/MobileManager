# Roadmap Phase 3: Custom Launcher („Ordnen“ & Profile) + Profi-Extras

> **Hinweis für den KI-Assistenten (Claude):**
> Dies ist die technische Spezifikation für **Phase 3**. Das Herzstück ist ein eigener Android Custom Launcher, da der vorinstallierte Google Pixel Launcher keine externen Änderungen an Ordnern oder Icons zulässt. Implementiere den Launcher als sauberes, eigenständiges Feature-Modul (`:feature-launcher`), damit die App aus Phase 1 & 2 stabil bleibt.

## Meilenstein 3.1: Custom Launcher – Basis (Homescreen & App-Drawer)

**Was damit gemeint ist:**
Die App wird so erweitert, dass Android sie als offiziellen Standard-Startbildschirm (Homescreen) anerkennt. Sie zeigt ein eigenes Raster (Grid) mit Apps, ein unteres Dock und eine durchsuchbare App-Übersicht (App-Drawer).

### Aufgaben im Detail

1. **Manifest-Registrierung als Launcher**

   * *Bedeutung:* Eine eigene `LauncherActivity` erhält im `AndroidManifest.xml` den Intent-Filter mit `ACTION_MAIN`, `CATEGORY_HOME` und `CATEGORY_DEFAULT` sowie `launchMode="singleTask"`.

2. **App-Liste & Icons laden (`LauncherApps` API)**

   * *Bedeutung:* Nutze `LauncherApps.getActivityList(null, Process.myUserHandle())` statt des alten `PackageManager`, da dies sauber mit Arbeitsprofilen, adaptiven Icons und App-Shortcuts umgeht. Icons asynchron laden und im Speicher cachen.

3. **Homescreen-Grid, Dock & App-Drawer (Jetpack Compose)**

   * *Bedeutung:*

     * **Homescreen:** Konfigurierbares Raster (z. B. 4×5 oder 5×6) auf mehreren horizontal wischbaren Seiten (`HorizontalPager`) über dem normalen System-Wallpaper (`Theme.Wallpaper` / transparenter Activity-Hintergrund).

     * **Dock:** Feste untere Leiste für 4–5 Lieblings-Apps.

     * **App-Drawer:** Wischgeste nach oben öffnet die alphabetische App-Liste mit Suchleiste oben.

4. **Grund-Interaktionen**

   * *Bedeutung:* Kurzer Klick startet die App; langer Druck öffnet ein Kontextmenü (App-Info, vom Homescreen entfernen, Deinstallieren, in Ordner verschieben).

### Akzeptanzkriterien

* Die App lässt sich in den Pixel-Einstellungen als „Standard-Startbildschirm-App“ auswählen.

* Drücken der Home-Geste führt flüssig (60+ FPS) auf den eigenen Homescreen; das eingestellte Pixel-Hintergrundbild scheint sauber durch.

## Meilenstein 3.2: Automatisches „Ordnen“ & Kontext-Profile

**Was damit gemeint ist:**
Das ist deine Kernidee für den Homescreen: Per Knopfdruck sortiert die App deine Apps automatisch in thematische Ordner, räumt lange ungenutzte Apps weg und erlaubt komplett unterschiedliche Homescreen-Layouts je nach Situation (*Arbeit*, *Auto*, *Freizeit*).

### Aufgaben im Detail

1. **Auto-Kategorisierung von Apps**

   * *Bedeutung:* Jede App wird einer Kategorie zugeordnet:

     * Primär über `ApplicationInfo.category` (Android liefert z. B. `CATEGORY_GAME`, `CATEGORY_SOCIAL`, `CATEGORY_AUDIO`, `CATEGORY_MAPS`, `CATEGORY_PRODUCTIVITY`).

     * Sekundär über eine lokale Mapping-Regel/Keyword-Liste (z. B. Banking-Apps → „Finanzen“, Browser/Mail → „Kommunikation“, Rest → „Tools“).

2. **Die „Ordnen“-Funktion (One-Click-Sortierung)**

   * *Bedeutung:* Ein Button „Homescreen ordnen“ bietet drei Optionen (einzeln oder kombiniert):

     * **In Ordner sammeln:** Erstellt automatisch Ordner pro Kategorie und verschiebt die passenden Apps direkt hinein.

     * **Aufräumen / Archivieren:** Nutzt `UsageStatsManager` aus Phase 1 – alle Apps, die $> 60$ Tage nicht genutzt wurden, werden vom Homescreen entfernt oder in einen separaten „Archiv“-Ordner verschoben.

     * **Vorschau:** Zeigt vor dem Anwenden kurz an, wie viele Ordner erstellt und wie viele Apps verschoben werden (inkl. „Rückgängig“-Funktion über Room-Snapshot).

3. **Kontext-Profile & automatischer Wechsel**

   * *Bedeutung:* In Room werden komplette Grid-Layouts unter einer `profileId` gespeichert (z. B. *Standard*, *Arbeit*, *Auto*, *Fokus*).

   * **Kopplung an Phase 1 (Automation):**

     * Auto-Bluetooth verbunden → Wechsel auf *Auto-Profil* (nur große Icons für Maps, Spotify, Telefon).

     * Bestimmtes WLAN verbunden oder Uhrzeit (Mo–Fr 08:00–17:00) → Wechsel auf *Arbeits-Profil* (Spiele & Social Media ausgeblendet).

### Akzeptanzkriterien

* Ein Klick auf „Ordnen“ sortiert einen unaufgeräumten Homescreen reproduzierbar in saubere Ordner.

* Beim Verbinden mit einem Bluetooth-Gerät wechselt der Homescreen automatisch auf das verknüpfte Profil und danach wieder zurück.

## Meilenstein 3.3: Lokaler Tracker-Blocker (Ohne Root per `VpnService`)

**Was damit gemeint ist:**
Die App richtet ein rein lokales VPN auf dem Pixel ein (es werden keine Daten an einen fremden Server gesendet). Dadurch sieht sie, welche Apps im Hintergrund welche Domains anfragen, und kann bekannte Werbe- und Tracking-Server direkt blockieren.

### Aufgaben im Detail

1. **Lokaler `VpnService`**

   * *Bedeutung:* Implementierung eines `VpnService` mit `VpnService.Builder()`, der den DNS-Verkehr (Port 53) abfängt.

2. **Lokale Blockliste (Hosts-Format)**

   * *Bedeutung:* Einbinden einer kompakten, lokal gespeicherten Domain-Blockliste (z. B. gängige Tracker-Domains). Anfragen an blockierte Domains werden sofort mit `NXDOMAIN` / `0.0.0.0` beantwortet, erlaubte Anfragen normal weitergeleitet.

3. **Pro-App-Statistik**

   * *Bedeutung:* Speichert in Room aggregiert (nicht jedes einzelne Paket, um Speicher zu sparen), wie viele Tracker-Anfragen pro App am Tag blockiert wurden, und zeigt eine Rangliste im Security-Tab.

### Edge-Cases & Fallstricke

* Android erlaubt immer nur **ein** aktives VPN gleichzeitig. Wenn der Nutzer ein anderes VPN (z. B. WireGuard oder Firmen-VPN) nutzt, muss dieses Modul deaktiviert bleiben.

### Akzeptanzkriterien

* Bei aktiviertem Tracker-Blocker funktioniert das normale Surfen ohne spürbare Verzögerung, während Test-Tracking-Domains blockiert und im Log gezählt werden.

## Meilenstein 3.4: Fokus-Bremse & Komfort-Extras

**Was damit gemeint ist:**
Kleine, in sich abgeschlossene Zusatzmodule, die den Alltag auf dem Pixel weiter abrunden und unabhängig voneinander ein- und ausgeschaltet werden können.

### Aufgaben im Detail

1. **Fokus-Bremse (Atempause vor Zeitfressern)**

   * *Bedeutung:* Erkennt über `UsageStatsManager` oder den optionalen `AccessibilityService`, wenn eine markierte App (z. B. Instagram, TikTok) geöffnet wird, und legt über `SYSTEM_ALERT_WINDOW` für 5 Sekunden ein ruhiges Overlay darüber („Wirklich öffnen?“ mit Countdown und „Abbrechen“-Button).

2. **Lautstärke pro App**

   * *Bedeutung:* Speichert pro Paketname eine gewünschte Medienlautstärke (z. B. Spiele 20 %, Podcasts 70 %). Beim Wechsel in die App wird `AudioManager.setStreamVolume()` automatisch angepasst und beim Verlassen auf den vorherigen Wert zurückgesetzt.

3. **Hardware-Schnellcheck (Diagnose-Menü)**

   * *Bedeutung:* Ein eigener Unterbildschirm mit zwei Tools:

     * *Lautsprecher-Reiniger:* Generiert per `AudioTrack` eine tiefe Sinuswelle (z. B. 165 Hz) für 15 Sekunden auf maximaler Lautstärke, um Staub/Wasser aus dem Gitter zu lösen.

     * *Display-Test:* Zeigt nacheinander Vollbild-Farben (Rot, Grün, Blau, Weiß, Schwarz) zum Prüfen auf Pixelfehler.

4. **APK-Backup**

   * *Bedeutung:* Kopiert die `applicationInfo.sourceDir` (`base.apk`) einer ausgewählten installierten App in einen Backup-Ordner im Speicher (`Documents/PixelManagerBackups`), damit man bei schlechten App-Updates eine Sicherung hat.

### Akzeptanzkriterien

* Jedes Extra-Modul lässt sich einzeln in den Einstellungen aktivieren/deaktivieren und verbraucht im deaktivierten Zustand keinerlei Hintergrundressourcen.