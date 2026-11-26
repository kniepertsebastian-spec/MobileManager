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
