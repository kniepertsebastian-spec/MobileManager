# MobileManager

Nativer App-Manager für Google Pixel (Android 13+, ohne Root, Sideloading).
Kotlin · Jetpack Compose (Material 3 / Material You) · MVVM + Clean Architecture · Hilt · Room · WorkManager.
Alle Daten bleiben lokal auf dem Gerät – keine Cloud, keine Telemetrie.

Roadmaps: [`docs/`](docs/)

## Phase 1 (MVP) – Stand

| Meilenstein | Inhalt |
|---|---|
| 1.1 | Gradle-Setup, Bottom-Navigation (Dashboard / App-Stopper / Cleaner / Automatik), Room-Schema v1, Berechtigungs-Assistent mit Live-Status (`ON_RESUME`) |
| 1.2 | App-Stopper: inaktive Apps über `UsageStatsManager`, Whitelist + automatischer Schutz (Launcher, Telefon, SMS, Wecker, Tastatur, Messenger, Shizuku, eigene App), `am force-stop` per Shizuku-UserService, optionaler Accessibility-Fallback, periodischer Worker |
| 1.3 | Speicher-Cleaner: Duplikate (Größe → SHA-256, Cache in Room), alte Fotos ohne Personen (ML Kit, 640 px, Cache in Room), Swipe-Review, Löschen über **einen** `MediaStore.createDeleteRequest`-Dialog |
| 1.4 | Bluetooth-Automatik: Regel-Editor für gekoppelte Geräte, `ACL_CONNECTED`-Receiver, Medienlautstärke, App-Start, eingebauter 5-Band-EQ (Session 0) |
| 1.5 | Dashboard mit Status-Karten, Nacht-Routine (`setRequiresCharging` + `setRequiresDeviceIdle`) |

## Bauen

Voraussetzungen: JDK 17+ (Gradle 9 läuft nicht mit JDK 11) und ein Android SDK mit Platform 36.

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

## Bekannte Grenzen

- Der globale Equalizer (Audio-Session 0) ist von Android als veraltet markiert und funktioniert nicht auf jedem Gerät; er bleibt nur aktiv, solange der App-Prozess lebt.
- Der geplante App-Stopper nutzt im Hintergrund nur Shizuku (der Accessibility-Fallback braucht eine sichtbare Settings-Seite).
