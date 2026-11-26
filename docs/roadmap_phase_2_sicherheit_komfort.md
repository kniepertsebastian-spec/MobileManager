# Roadmap Phase 2: Sicherheit, E-Mail-Alarme & Komfort (Pixel App-Manager)

> **Hinweis für den KI-Assistenten (Claude):**
> Dies ist die technische Spezifikation für **Phase 2**. Sie baut auf der bestehenden Codebasis und Architektur aus Phase 1 (Kotlin, Jetpack Compose, Hilt, Room, WorkManager) auf. Erweitere die Navigation um einen neuen Bereich „Security & Wächter“ und halte alle sensiblen Daten (wie SMTP-Passwörter oder API-Keys) strikt verschlüsselt im Android Keystore.

---

## Meilenstein 2.1: Security-Events & Diebstahl-Erkennung

**Was damit gemeint ist:**
Die App soll im Hintergrund verdächtige Vorgänge auf dem Pixel erkennen – insbesondere unbefugte Entsperrversuche, heimlich installierte Apps (Sideloading) und einen SIM-Karten-Wechsel – und diese als strukturierte Sicherheitsereignisse protokollieren.

### Aufgaben im Detail
1. **Fehlgeschlagene Entsperrversuche (`DeviceAdminReceiver`)**
   * *Bedeutung:* Eine eigene Klasse erbt von `DeviceAdminReceiver` und wird im Manifest mit `BIND_DEVICE_ADMIN` sowie einer XML-Policy (`<watch-login />`) registriert.
   * In `onPasswordFailed()` wird ein Zähler hochgezählt (und bei `onPasswordSucceeded()` wieder auf 0 gesetzt). Erreicht der Zähler den Schwellenwert (z. B. 3 Fehlversuche), wird ein `SecurityEvent` ausgelöst.
2. **Überwachung neuer App-Installationen**
   * *Bedeutung:* Ein `BroadcastReceiver` lauscht auf `Intent.ACTION_PACKAGE_ADDED`. Über `PackageManager.getInstallSourceInfo(packageName)` prüft die App, ob der Installer `com.android.vending` (Google Play Store) ist. Wenn nicht (= Sideloading), wird ein Warn-Event erzeugt.
3. **SIM-Karten-Status prüfen**
   * *Bedeutung:* Speichert beim Einrichten den aktuellen SIM-Status/Carrier-ID (ohne sensible IMSI, da ohne Carrier-Privilegien nicht lesbar; stattdessen `SubscriptionManager` / `TelephonyManager.simState`). Ändert sich der Zustand unerwartet auf `SIM_STATE_ABSENT` oder ändert sich die `subscriptionId`, wird ein Alarm ausgelöst.
4. **Optionaler GPS-Standort beim Alarm**
   * *Bedeutung:* Bei einem Diebstahl-Event (z. B. 3× falsche PIN) fragt die App einmalig den letzten bekannten Standort über `FusedLocationProviderClient.lastLocation` ab und hängt einen Google-Maps-Link (`https://maps.google.com/?q=LAT,LNG`) an den Alarmtext an.

### Akzeptanzkriterien
* 3 falsche PIN-Eingaben auf dem Sperrbildschirm erzeugen zuverlässig einen Eintrag im Security-Log und triggern die Benachrichtigungs-Engine.
* Das Deaktivieren des Device-Admins muss im UI sauber erklärt werden (sonst lässt sich die App nicht deinstallieren).

---

## Meilenstein 2.2: Automatischer E-Mail- & Push-Versand (Offline-fähig)

**Was damit gemeint ist:**
Wenn ein Sicherheitsereignis eintritt, soll die App völlig lautlos im Hintergrund eine E-Mail (oder wahlweise eine Push-Nachricht über Telegram/ntfy.sh) an den Besitzer senden – auch wenn das Handy im Moment des Alarms kurzzeitig offline ist.

### Aufgaben im Detail
1. **Sichere Speicherung der Zugangsdaten**
   * *Bedeutung:* E-Mail-Adresse, SMTP-Server/App-Passwort oder Webhook-API-Key dürfen **niemals** im Klartext in SharedPreferences oder Room liegen. Nutze `EncryptedSharedPreferences` (Android Jetpack Security / Android Keystore).
2. **Versand-Kanäle implementieren (Strategy Pattern)**
   * *Option A (SMTP):* Versand per Jakarta Mail (`com.sun.mail:android-mail`) über TLS/SSL (z. B. Gmail mit App-Passwort oder Posteo).
   * *Option B (Webhook / API – empfohlen):* Einfacher HTTP-POST über `OkHttp` oder `Ktor` an einen Mail-Dienst (z. B. Resend API), einen Telegram-Bot (`api.telegram.org/bot<TOKEN>/sendMessage`) oder `ntfy.sh`.
3. **Offline-Warteschlange (`WorkManager`)**
   * *Bedeutung:* Jeder Alarm wird nicht direkt im Receiver gesendet, sondern als `OneTimeWorkRequest` mit `Constraints(requiredNetworkType = NetworkType.CONNECTED)` in den `WorkManager` gestellt. Schaltet ein Dieb den Flugmodus ein, wartet der Job automatisch, bis wieder Internet besteht, und sendet dann sofort.
4. **Cooldown- & Test-Funktion**
   * *Bedeutung:* Pro Ereignistyp gilt eine konfigurierbare Sperrzeit (Standard: 10 Minuten), damit man sich beim eigenen Vertippen nicht selbst zuspammt. Ein „Test-Alarm senden“-Button im UI prüft die Zugangsdaten sofort.

### Akzeptanzkriterien
* Test-Mail kommt zuverlässig an.
* Ein im Flugmodus ausgelöster Alarm wird automatisch versendet, sobald WLAN/Mobilfunk wieder eingeschaltet wird.

---

## Meilenstein 2.3: Berechtigungs-Audit & Datei-Ablauf

**Was damit gemeint ist:**
Zwei proaktive Hygiene-Werkzeuge: Das eine deckt „Datenkraken“ unter den installierten Apps auf, das andere verhindert, dass Screenshots und PDFs aus dem Downloads-Ordner den Speicher dauerhaft zumüllen.

### Aufgaben im Detail
1. **Berechtigungs-Audit**
   * *Bedeutung:* Scannt alle Nutzer-Apps über `PackageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)` und prüft, welche kritischen Rechte tatsächlich erteilt (`PERMISSION_GRANTED`) sind: `ACCESS_BACKGROUND_LOCATION`, `CAMERA`, `RECORD_AUDIO`, `READ_CONTACTS`, `MANAGE_EXTERNAL_STORAGE`.
   * Kombiniert dies mit `UsageStatsManager` aus Phase 1: Apps mit kritischen Rechten, die seit $> 30$ Tagen nicht genutzt wurden, werden ganz oben als „Hohes Risiko“ markiert – inklusive Direktbutton in die Android-App-Einstellungen (`ACTION_APPLICATION_DETAILS_SETTINGS`).
2. **Screenshot- & Download-Verfallsdatum**
   * *Bedeutung:* Ein wöchentlicher `WorkManager`-Job sucht über `MediaStore` nach Dateien in `Pictures/Screenshots` und `Download`, die älter als 14 Tage sind. Findet er Dateien, schickt er eine lokale Benachrichtigung („12 alte Screenshots gefunden – jetzt prüfen?“), die direkt die Swipe-Ansicht aus Phase 1 öffnet.

### Akzeptanzkriterien
* Audit-Liste lädt in unter 2 Sekunden und verlinkt korrekt in die jeweiligen Systemeinstellungen.
* Alte Screenshots werden getrennt von normalen Kamerafotos erkannt.

---

## Meilenstein 2.4: Akku- & Temperatur-Wächter

**Was damit gemeint ist:**
Schützt den Pixel-Akku vor Überhitzung (z. B. im Sommer im Auto an der Windschutzscheibe) und vor unnötigem Dauerladen auf 100 %.

### Aufgaben im Detail
1. **Sensor-Überwachung (`BatteryManager`)**
   * *Bedeutung:* Lauscht auf `Intent.ACTION_BATTERY_CHANGED` (über einen leichten Service oder periodischen Check beim Laden) und liest aus:
     * `EXTRA_TEMPERATURE` (Wert ist in Zehntel-Grad Celsius, z. B. `420` = $42{,}0\,^\circ\text{C}$).
     * `EXTRA_LEVEL` & `EXTRA_SCALE` (Ladestand in Prozent).
     * `EXTRA_PLUGGED` (ob ein Ladegerät angeschlossen ist).
2. **Hitze- & Lade-Alarme**
   * *Bedeutung:* Überschreitet die Temperatur den Schwellenwert (Standard: $42\,^\circ\text{C}$), erscheint eine hochpriorisierte Warnung. Erreicht der Ladestand beim Laden 80 % (konfigurierbar), gibt die App einmalig einen Hinweiston aus.
3. **Nacht-Standby-Bericht**
   * *Bedeutung:* Speichert den Akkustand beim Einschalten der Nachtruhe (z. B. 23:00 Uhr, sofern nicht am Kabel) und vergleicht ihn morgens (z. B. 07:00 Uhr). Zeigt den prozentualen Verlust in einer kurzen Übersicht im Dashboard.

### Akzeptanzkriterien
* Temperatur und Ladestand werden live und korrekt im UI angezeigt.
* Alarme lösen nicht dauerhaft im Sekundentakt aus, sondern nur einmal pro Überschreitung (Hysterese, z. B. erst wieder nach Abkühlung unter $39\,^\circ\text{C}$).

---

## Meilenstein 2.5: Benachrichtigungs-Manager (Verlauf & Digest)

**Was damit gemeint ist:**
Rettet versehentlich weggewischte Nachrichten und bündelt unwichtige App-Benachrichtigungen, damit das Pixel tagsüber ruhiger bleibt.

### Aufgaben im Detail
1. **`NotificationListenerService` implementieren**
   * *Bedeutung:* Service im Manifest mit `BIND_NOTIFICATION_LISTENER_SERVICE` registrieren und im Berechtigungs-Assistenten verlinken.
2. **7-Tage-Archiv (Room)**
   * *Bedeutung:* In `onNotificationPosted(sbn)` werden Paketname, Titel, Text und Zeitstempel lokal in Room gespeichert. Ein täglicher `WorkManager`-Job löscht Einträge, die älter als 7 Tage sind. Das UI bietet eine Volltextsuche und Filter nach App.
3. **Digest-Modus & Keyword-Filter**
   * *Bedeutung:* Der Nutzer markiert bestimmte Apps als „Digest-Apps“ (z. B. Shopping, News) oder hinterlegt Stichwörter (z. B. „Rabatt“, „Sale“).
   * Trifft eine solche Benachrichtigung ein, bricht der Service sie sofort stumm ab (`cancelNotification(sbn.key)`) und speichert sie in der Digest-Liste. Zu festen Uhrzeiten (z. B. 12:00 und 19:00 Uhr) zeigt deine App eine einzige Sammel-Benachrichtigung mit allen abgefangenen Meldungen.

### Edge-Cases & Fallstricke
* Laufende System-Benachrichtigungen (`isOngoing == true`, z. B. Musikplayer, Timer, Navi) niemals im Digest abfangen oder löschen.
* Gruppen-Summary-Benachrichtigungen (`FLAG_GROUP_SUMMARY`) deduplizieren, damit Nachrichten nicht doppelt im Archiv landen.

### Akzeptanzkriterien
* Weggewischte WhatsApp-/System-Meldungen sind sofort im 7-Tage-Archiv auffindbar.
* Eine als „Digest“ markierte App vibriert tagsüber nicht mehr, sondern erscheint gesammelt zur eingestellten Uhrzeit.