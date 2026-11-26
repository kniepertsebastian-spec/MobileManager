# Roadmap Phase 1: MVP – Kernfunktionen (Pixel App-Manager)

> **Hinweis für den KI-Assistenten (Claude):**
> Dies ist die technische Spezifikation für **Phase 1 (MVP)** einer nativen Android-App für ein **Google Pixel (ohne Root, Sideloading-Nutzung)**. Arbeite die Meilensteine strikt der Reihe nach ab. Schreibe sauberen, modularen Kotlin-Code mit Jetpack Compose (Material 3), MVVM + Clean Architecture, Hilt (DI) und Room. Vermeide veraltete XML-Layouts und Deprecated-APIs.

---

## Globaler Kontext & Tech-Stack (Phase 1)

* **Zielplattform:** Android 13+ (Min SDK 33, Target SDK aktuellste Version), optimiert für Google Pixel.
* **Sprache & UI:** Kotlin, Jetpack Compose, Material 3 (Dynamic Color / Material You).
* **Architektur:** Single-Activity-App mit Navigation Compose, Schichten: `data` (Room, System-APIs), `domain` (UseCases, Modelle), `ui` (Screens, ViewModels mit `StateFlow`).
* **Hintergrundprozesse:** `WorkManager` für geplante Scans, `Foreground Service` nur wenn zwingend nötig.
* **Datenschutz-Regel:** 100 % lokal auf dem Gerät. Keine Cloud-Uploads, keine Telemetrie.

---

## Meilenstein 1.1: Projekt-Setup, Navigation & Berechtigungs-Assistent

**Was damit gemeint ist:**
Bevor die eigentlichen Features gebaut werden, braucht die App ein stabiles Grundgerüst und ein geführtes Onboarding. Da Android viele Systemrechte (Nutzungszugriff, Medien, Bluetooth, Überlagerung, Shizuku) nicht über einfache Pop-ups vergibt, muss die App den Nutzer gezielt in die jeweiligen Android-Systemeinstellungen leiten und den Status live prüfen.

### Aufgaben im Detail
1. **Projekt & Dependencies aufsetzen**
   * *Bedeutung:* `build.gradle.kts` mit Compose, Hilt, Room, WorkManager, Shizuku API (`dev.rikka.shizuku:api` & `provider`) und Google ML Kit (`com.google.mlkit:face-detection`) konfigurieren.
2. **Bottom-Navigation & 4 Haupt-Tabs**
   * *Bedeutung:* Vier Hauptansichten anlegen: `DashboardScreen`, `AppStopperScreen`, `CleanerScreen`, `AutomationScreen`.
3. **Room-Datenbank (Schema v1)**
   * *Bedeutung:* Lokale SQLite-Datenbank mit mindestens drei Tabellen:
     * `WhitelistedAppEntity` (`packageName`, `addedAt`)
     * `BluetoothRuleEntity` (`id`, `deviceAddress`, `deviceName`, `targetVolume`, `launchPackageName`, `eqProfileId`, `isEnabled`)
     * `CleanupHistoryEntity` (`id`, `timestamp`, `freedBytes`, `stoppedAppsCount`)
4. **Berechtigungs-Assistent (Onboarding & Status-Screen)**
   * *Bedeutung:* Eine Checkliste im UI, die für jede benötigte Berechtigung anzeigt, ob sie aktiv ist (Grün/Rot) und per Klick den passenden Android-Settings-Intent öffnet:
     * `PACKAGE_USAGE_STATS` (`Settings.ACTION_USAGE_ACCESS_SETTINGS`)
     * `READ_MEDIA_IMAGES` (Runtime Permission)
     * `BLUETOOTH_CONNECT` (Runtime Permission)
     * `SYSTEM_ALERT_WINDOW` (`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`)
     * Akku-Optimierung ignorieren (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
     * Shizuku-Status prüfen (`Shizuku.pingBinder()` & Berechtigung anfragen).

### Akzeptanzkriterien
* App kompiliert fehlerfrei, Navigation zwischen allen 4 Tabs funktioniert.
* Der Berechtigungs-Assistent erkennt Änderungen sofort nach Rückkehr aus den Android-Einstellungen (`ON_RESUME`).

---

## Meilenstein 1.2: Modul „App-Stopper“ (Inaktive Apps beenden erzwingen)

**Was damit gemeint ist:**
Auf dem Pixel pausiert das Wegwischen aus den „Letzten Apps“ viele Anwendungen nur, statt sie komplett zu stoppen. Dieses Modul findet Apps, die der Nutzer seit einer konfigurierbaren Zeit (z. B. 12 oder 24 Stunden) nicht mehr aktiv im Vordergrund hatte, und führt für sie ein echtes `force-stop` aus – ohne wichtige System- oder Messenger-Apps abzuschießen.

### Aufgaben im Detail
1. **Installierte Apps & Nutzungszeit auslesen**
   * *Bedeutung:* Über `PackageManager` alle startbaren Nutzer-Apps laden (System-Apps standardmäßig ausblenden). Über `UsageStatsManager.queryUsageStats()` den Zeitstempel `lastTimeUsed` ermitteln und Apps filtern, die länger als der eingestellte Schwellenwert inaktiv sind.
2. **Whitelist-Management**
   * *Bedeutung:* Der Nutzer kann jede App per Schalter auf die Whitelist setzen. Standardmäßig sollten kritische Kategorien (aktueller Launcher, Wecker, Telefon, die eigene Manager-App) automatisch geschützt sein.
3. **Force-Stop-Engine (Shizuku primär)**
   * *Bedeutung:* Da normale Apps `am force-stop` nicht ausführen dürfen, nutzt die App die **Shizuku-API** (ein UserService oder `Shizuku.newProcess`), um den Shell-Befehl `am force-stop <packageName>` im Hintergrund ohne Root auszuführen.
   * *Optionaler Fallback:* Falls Shizuku nicht aktiv ist, soll die UI klar darauf hinweisen und optional einen `AccessibilityService` anbieten, der die App-Info-Seite öffnet und automatisch auf „Beenden erzwingen“ + „OK“ tippt.
4. **Manuelle & geplante Ausführung**
   * *Bedeutung:* Ein „Jetzt aufräumen“-Button im UI zeigt eine Liste der betroffenen Apps und stoppt sie nacheinander mit Fortschrittsanzeige. Optional startet ein `PeriodicWorkRequest` (`WorkManager`) die Prüfung automatisch (z. B. nachts oder alle 6 Stunden).

### Edge-Cases & Fallstricke
* Niemals die eigene App oder aktive Systemdienste stoppen.
* Wenn Shizuku-Dienst nach einem Handy-Neustart nicht läuft, darf die App nicht abstürzen, sondern muss eine klare Meldung anzeigen.

### Akzeptanzkriterien
* Inaktive Apps werden korrekt mit „Zuletzt genutzt vor X Stunden“ aufgelistet.
* Whitelist-Apps werden strikt übersprungen.
* Ein Klick auf „Beenden erzwingen“ stoppt eine Test-App zuverlässig (in den Android-App-Infos ist „Beenden erzwingen“ danach ausgegraut).

---

## Meilenstein 1.3: Modul „KI-Speicher-Cleaner“ (Duplikate & alte Fotos ohne Personen)

**Was damit gemeint ist:**
Der interne Speicher des Pixels soll intelligent aufgeräumt werden, ohne blind Dateien zu löschen. Die App sucht nach exakten Duplikaten und nach alten Bildern, auf denen keine Menschen zu sehen sind (z. B. abfotografierte Zettel, alte Screenshots, versehentliche Fotos), und legt sie dem Nutzer in einer schnellen Tinder-ähnlichen Swipe-Ansicht zur Entscheidung vor.

### Aufgaben im Detail
1. **MediaStore-Scanner**
   * *Bedeutung:* Alle Bilder im internen Speicher über `ContentResolver` und `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` abfragen (ID, URI, Dateigröße, `DATE_ADDED`, Pfad/Bucket).
2. **Exakte Duplikate finden (Zweistufiger Algorithmus)**
   * *Bedeutung:* Um Akku und Zeit zu sparen, werden Bilder zuerst nach **identischer Dateigröße** gruppiert. Nur für Gruppen mit $\ge 2$ Dateien wird ein **SHA-256-Hash** über den Dateistrom berechnet. Identischer Hash = exaktes Duplikat.
3. **Alte Bilder ohne Personen (Google ML Kit)**
   * *Bedeutung:* Filtert alle Bilder, die älter als ein wählbarer Zeitraum sind (Standard: 6 Monate). Jedes Bild wird lokal auf dem Gerät durch `FaceDetection.getClient()` (ML Kit) gejagt. Wenn `faces.isEmpty()` zutrifft, wird das Bild als „Ohne Personen“ markiert.
   * *Performance-Wichtig:* Bilder vor der ML-Kit-Analyse herunterskalieren (z. B. max. 640px Kantenlänge), damit der Scan schnell läuft und keinen `OutOfMemoryError` verursacht. Ergebnisse in Room cachen, damit bereits geprüfte Bilder nicht jedes Mal neu analysiert werden.
4. **Swipe-Review-UI & Batch-Löschung**
   * *Bedeutung:* Eine Kartenansicht zeigt das Bild, Datum und Dateigröße.
     * *Nach links wischen* = Auf die Löschliste setzen.
     * *Nach rechts wischen* = Behalten (in Room als „Ignorieren“ markieren).
   * Am Ende der Sitzung wird **ein einziger** Android-Systemdialog über `MediaStore.createDeleteRequest(contentResolver, urisToDelete)` aufgerufen, damit der Nutzer die Löschung per Scoped Storage mit einem Klick bestätigt.

### Akzeptanzkriterien
* Exakte Bild-Duplikate werden zuverlässig gruppiert; mindestens eine Kopie bleibt standardmäßig geschützt.
* ML-Kit-Scan läuft im Hintergrund (`Dispatchers.Default`), blockiert die UI nicht und erkennt Fotos mit Gesichtern zuverlässig.
* Löschen funktioniert sauber über den Android-Bestätigungsdialog ohne Absturz.

---

## Meilenstein 1.4: Modul „Bluetooth-Automatik“ (Spotify + Lautstärke + EQ)

**Was damit gemeint ist:**
Sobald sich das Pixel per Bluetooth mit dem Auto oder bestimmten Kopfhörern verbindet, soll die App automatisch eine vordefinierte Aktion ausführen: Medienlautstärke setzen, Spotify öffnen und optional ein passendes Equalizer-Profil aktivieren.

### Aufgaben im Detail
1. **Geräte-Auswahl & Regel-Editor**
   * *Bedeutung:* Liste aller gekoppelten Bluetooth-Geräte über `BluetoothManager.adapter.bondedDevices` laden. Der Nutzer wählt ein Gerät und konfiguriert: Ziel-Lautstärke (0–100 %), zu startende App (z. B. Spotify `com.spotify.music`) und EQ-Profil.
2. **Verbindungs-Erkennung**
   * *Bedeutung:* Ein `BroadcastReceiver` (registriert im Manifest bzw. über einen leichten Service) lauscht auf `BluetoothDevice.ACTION_ACL_CONNECTED` und `ACTION_ACL_DISCONNECTED`. Bei einem Treffer mit einer aktiven Regel aus Room wird die Aktion ausgelöst.
3. **App-Start & Lautstärke setzen**
   * *Bedeutung:* Medienlautstärke via `AudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, ...)` setzen. Danach Ziel-App über `PackageManager.getLaunchIntentForPackage()` mit `FLAG_ACTIVITY_NEW_TASK` starten.
   * *Pixel-Hinweis:* Ab Android 10 blockiert das System Activity-Starts aus dem Hintergrund. Deshalb ist die Berechtigung `SYSTEM_ALERT_WINDOW` aus Meilenstein 1.1 zwingend nötig.
4. **Equalizer-Integration**
   * *Bedeutung:* Entweder eine zweite App (externe EQ-App) starten oder direkt einen eigenen einfachen 5-Band-Equalizer über Androids `android.media.audiofx.Equalizer` bzw. `DynamicsProcessing` (Audio-Session-ID `0` für globalen Output) ansteuern.

### Akzeptanzkriterien
* Beim Verbinden eines hinterlegten Bluetooth-Geräts passt sich die Lautstärke innerhalb von 2 Sekunden an und Spotify öffnet sich selbst dann, wenn der Bildschirm aus oder im Homescreen ist.

---

## Meilenstein 1.5: Basis-Dashboard & Integration

**Was damit gemeint ist:**
Die Startseite fasst den Zustand des Pixels auf einen Blick zusammen und verbindet alle drei Module aus Phase 1.

### Aufgaben im Detail
* **Status-Karten (Material 3 Cards):**
  1. *App-Stopper:* „X inaktive Apps gefunden“ + Schnell-Button „Alle stoppen“.
  2. *Speicher:* Aktuell freier Speicher (`StatFs`) + „Zuletzt X MB bereinigt“ + Button „Scan starten“.
  3. *Bluetooth:* „X aktive Regeln“ + Status, welches Gerät gerade verbunden ist.
* **Nacht-Routine (`WorkManager`):** Optionaler Schalter in den Einstellungen, der Duplikat- und ML-Kit-Scans automatisch nur dann ausführt, wenn das Pixel lädt (`setRequiresCharging(true)`) und im Leerlauf ist (`setRequiresDeviceIdle(true)`).