# MobileManager

Nativer App-Manager für Google Pixel (Android 13+, ohne Root, Sideloading).
Kotlin · Jetpack Compose (Material 3 / Material You) · MVVM + Clean Architecture · Hilt · Room · WorkManager.
Alle Daten bleiben lokal auf dem Gerät – keine Cloud, keine Telemetrie. Einzige Ausnahme: Sicherheitsalarme
an den Kanal (E-Mail, Telegram, ntfy), den du selbst einrichtest.

Roadmaps: [`docs/`](docs/)

## Phase 1 (MVP) – Stand

| Meilenstein | Inhalt |
|---|---|
| 1.1 | Gradle-Setup, Bottom-Navigation (Dashboard / App-Stopper / Cleaner / Automatik), Room-Schema v1, Berechtigungs-Assistent mit Live-Status (`ON_RESUME`) |
| 1.2 | App-Stopper: inaktive Apps über `UsageStatsManager`, Whitelist + automatischer Schutz (Launcher, Telefon, SMS, Wecker, Tastatur, Messenger, Shizuku, eigene App), `am force-stop` per Shizuku-UserService, optionaler Accessibility-Fallback, periodischer Worker |
| 1.3 | Speicher-Cleaner: Duplikate (Größe → SHA-256, Cache in Room), alte Fotos ohne Personen (ML Kit, 640 px, Cache in Room), Swipe-Review, Löschen über **einen** `MediaStore.createDeleteRequest`-Dialog |
| 1.4 | Bluetooth-Automatik: Regel-Editor für gekoppelte Geräte, `ACL_CONNECTED`-Receiver, Medienlautstärke, App-Start, eingebauter 5-Band-EQ (Session 0) |
| 1.5 | Dashboard mit Status-Karten, Nacht-Routine (`setRequiresCharging` + `setRequiresDeviceIdle`) |

## Phase 2 (Sicherheit & Komfort) – Stand

Neuer Tab **Wächter** mit Unterseiten. Room-Schema v2 (Auto-Migration von v1).

| Meilenstein | Inhalt |
|---|---|
| 2.1 | Security-Log: `DeviceAdminReceiver` mit `<watch-login />` (Alarm bei 3/6/9 … Fehlversuchen, Schwelle einstellbar), Deaktivieren direkt in der App, Sideloading-Erkennung (Installer ≠ Play Store), SIM-Überwachung (`simState`, Carrier-ID, Subscription-ID – ohne IMSI), optionaler Standort (`FusedLocationProviderClient`) als Google-Maps-Link |
| 2.2 | Alarm-Versand per Strategy Pattern: SMTP (Jakarta/Angus Mail, SSL/TLS oder STARTTLS), Resend-API, Telegram-Bot, ntfy (OkHttp). Zugangsdaten AES-256-GCM-verschlüsselt mit Schlüssel im Android Keystore. Offline-Warteschlange über `WorkManager` (`NetworkType.CONNECTED`, Retry mit Backoff), Sperrzeit pro Ereignistyp, „Test-Alarm senden“ |
| 2.3 | Berechtigungs-Audit (Hintergrund-Standort, Kamera, Mikrofon, Kontakte, Alle Dateien; „Hohes Risiko“ = > 30 Tage ungenutzt, Direktlink in die App-Einstellungen). Wöchentlicher Job für Screenshots/Downloads älter als 14 Tage mit Benachrichtigung → Swipe-Ansicht aus Phase 1 |
| 2.4 | Akku-Wächter: Live-Temperatur/Ladestand, Hitzewarnung mit Hysterese (42 °C → erneut erst unter 39 °C), einmaliger Ladelimit-Hinweiston (80 %), Nacht-Standby-Bericht (23–7 Uhr) im Dashboard, optionaler Echtzeit-Vordergrunddienst |
| 2.5 | `NotificationListenerService`: 7-Tage-Archiv mit Volltextsuche und App-Filter, Digest-Modus für Apps/Stichwörter mit Sammel-Benachrichtigung zu festen Uhrzeiten. Laufende Meldungen und Gruppen-Summaries werden nie archiviert bzw. abgefangen |

## Phase 3 (Custom Launcher & Profi-Extras) – Stand

Neuer Bereich **Launcher & Extras** (Puzzle-Symbol oben rechts). Jedes Modul ist einzeln schaltbar; ausgeschaltet läuft nichts im Hintergrund.
Room-Schema v3 (Auto-Migration von v2). Der Launcher ist ein eigenes Gradle-Modul `:feature-launcher` mit eigener Datenbank (`launcher.db`).

| Meilenstein | Inhalt |
|---|---|
| 3.1 | Eigener Startbildschirm (`CATEGORY_HOME`, `singleTask`, transparentes Fenster über dem System-Hintergrund). App-Liste über `LauncherApps` inkl. Arbeitsprofil, Icons asynchron mit LRU-Cache. Seiten per `HorizontalPager` (Raster 4×5 bis 5×6), Dock mit bis zu 5 Apps, App-Drawer per Wischgeste nach oben mit Suche. Langer Druck: App-Info, entfernen, in Ordner verschieben, deinstallieren; langer Druck auf freie Fläche: Ordnen, Hintergrund, Profil, Einstellungen. Die Launcher-Activity ist im Manifest deaktiviert und wird erst per Schalter aktiviert |
| 3.2 | Auto-Kategorisierung (Finanz-Liste → `ApplicationInfo.category` → Stichwörter → Tools). „Homescreen ordnen“: Ordner pro Kategorie (ab 2 Apps), Aufräumen > 60 Tage ungenutzt (Archiv-Ordner oder entfernen), Vorschau und „Rückgängig“ über Room-Snapshots. Deterministisch: ein zweiter Durchlauf ändert nichts. Profile *Standard*, *Arbeit* (Spiele & Social ausgeblendet), *Auto* (große Icons, 2×3), *Fokus* mit eigenem Layout; automatischer Wechsel per Bluetooth-Gerät (aus der Phase-1-Automatik), WLAN oder Uhrzeit – Priorität Bluetooth > WLAN > Zeit, danach zurück zum Basisprofil |
| 3.3 | Tracker-Blocker per lokalem `VpnService`: nur die virtuelle DNS-Adresse wird ins VPN geroutet, gesperrte Domains (inkl. Subdomains) bekommen sofort `NXDOMAIN`, der Rest geht unverändert an den DNS-Server des aktuellen Netzes. ~150 mitgelieferte Tracker-Domains, eigener Hosts-Import, eigene Sperr-/Erlaubtliste. Statistik pro App/Domain und Tag (aggregiert, 30 Tage), Rangliste unter Wächter → Security. Erkennt fremde VPNs und bleibt dann aus |
| 3.4 | Fokus-Bremse (Overlay mit Countdown, „Abbrechen“ führt zum Startbildschirm, Ruhezeit nach „Trotzdem öffnen“), Lautstärke pro App (setzt beim Öffnen, stellt beim Verlassen wieder her). App-Erkennung bevorzugt über eine eigene, schlanke Bedienungshilfe (nur Fensterwechsel, keine Inhalte), sonst über UsageStats – dann nur bei eingeschaltetem Display. Hardware-Schnellcheck: Lautsprecher-Reiniger (165 Hz, 15 s, `AudioTrack`) und Display-Test (Rot/Grün/Blau/Weiß/Schwarz). APK-Backup von `base.apk` und Split-APKs nach `Documents/PixelManagerBackups` |

## Bauen

Voraussetzungen: JDK 17+ (Gradle 9 läuft nicht mit JDK 11) und ein Android SDK mit Platform 37.

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Einrichtung auf dem Pixel

1. App starten → Berechtigungs-Assistent durchgehen.
2. [Shizuku](https://shizuku.rikka.app/) installieren und per „Kabelloses Debugging“ starten, dann in MobileManager die Shizuku-Berechtigung erteilen.
   Nach jedem Neustart muss Shizuku neu gestartet werden – MobileManager zeigt das an.
3. Optional (ohne Shizuku): Bedienungshilfe aktivieren. Bei per APK installierten Apps vorher
   *App-Info → ⋮ → Eingeschränkte Einstellungen zulassen*.
4. Wächter → Security: Geräteadministrator aktivieren. **Vor dem Deinstallieren dort wieder deaktivieren**,
   sonst blockiert Android die Deinstallation.
5. Wächter → Alarm-Versand: Kanal einrichten und „Test-Alarm senden“ drücken.
6. Für Archiv/Digest den Benachrichtigungszugriff erteilen (ebenfalls „Eingeschränkte Einstellungen zulassen“ nötig).
7. Launcher & Extras → „Eigener Launcher“ einschalten → „Als Standard-Startbildschirm festlegen“. Zurück zum Pixel Launcher
   geht es über Einstellungen → Apps → Standard-Apps → Startbildschirm-App oder einfach durch Ausschalten des Schalters.
8. Tracker-Blocker einschalten und die einmalige VPN-Anfrage bestätigen. Optional unter Einstellungen → Netzwerk → VPN
   „Durchgehend aktives VPN“ für MobileManager aktivieren.
9. Für Fokus-Bremse/Lautstärke pro App am besten die Bedienungshilfe „MobileManager – Fokus-Bremse & Lautstärke“ aktivieren.

## Bekannte Grenzen

- Der globale Equalizer (Audio-Session 0) ist von Android als veraltet markiert und funktioniert nicht auf jedem Gerät; er bleibt nur aktiv, solange der App-Prozess lebt.
- Der geplante App-Stopper nutzt im Hintergrund nur Shizuku (der Accessibility-Fallback braucht eine sichtbare Settings-Seite).
- `EncryptedSharedPreferences` ist abgekündigt; stattdessen verschlüsselt `KeystoreCipher` die Zugangsdaten selbst (AES-GCM, Schlüssel im Android Keystore) und legt sie in DataStore ab.
- `ACTION_PACKAGE_ADDED` erreicht seit Android 8 keine Manifest-Receiver mehr. Neue Apps werden daher über `getChangedPackages()` alle 15 Minuten erkannt – sofort nur mit aktivem Echtzeit-Wächter.
- Der Fehlversuchszähler von Android umfasst PIN, Muster und Passwort, aber keine Fingerabdruck-Fehlversuche.
- Diebstahl-Ereignisse (falsche PIN, SIM-Wechsel) erzeugen absichtlich keine lokale Benachrichtigung, damit ein Dieb den Alarm nicht sieht.
- Digest: Android spielt Ton/Vibration ab, bevor ein `NotificationListenerService` die Meldung entfernen kann. Für völlige Ruhe zusätzlich den Ton der App in den Android-Einstellungen ausschalten (Button „Ton aus“).
- PDFs & Co. im Download-Ordner sieht die App nur mit „Zugriff auf alle Dateien“; ohne dieses Recht verfallen nur Bilder.
- Ladelimit und Hitzewarnung reagieren ohne Echtzeit-Wächter mit bis zu 15 Minuten Verzögerung.
- Launcher: Apps lassen sich noch nicht per Drag & Drop verschieben (Hinzufügen geht über den App-Drawer, Ordner über das Kontextmenü).
  Wischen nach unten öffnet nicht die Benachrichtigungsleiste. Bei Drittanbieter-Launchern zeigt Android die „Zuletzt verwendet“-Ansicht
  des Pixel Launchers und eingeschränkte Übergangsanimationen.
- WLAN-Regeln brauchen Standortberechtigung und eingeschalteten Standort (Android gibt sonst keinen WLAN-Namen heraus). Der Profilwechsel
  wird beim nächsten Anzeigen des Startbildschirms sichtbar.
- Tracker-Blocker: Ist „Privates DNS“ fest auf einen Anbieter eingestellt, gehen DNS-Anfragen verschlüsselt am VPN vorbei (die App weist darauf hin).
  Apps mit eigenem DNS-over-HTTPS (z. B. manche Browser) umgehen den Filter ebenfalls. Die Zuordnung zur App ist eine Schätzung,
  weil Android viele DNS-Anfragen stellvertretend stellt. Nur ein VPN gleichzeitig möglich.
- APK-Backup: Viele Play-Store-Apps bestehen aus mehreren Split-APKs – zum Wiederherstellen braucht es dann einen Split-APK-Installer.
  App-Daten werden nicht gesichert.
- Der Lautsprecher-Reiniger startet nicht, solange Kopfhörer verbunden sind.
