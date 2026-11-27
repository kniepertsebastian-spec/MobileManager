# MobileManager – Feature- & Sicherheits-Roadmap (Phase 4+)

Diese Roadmap bündelt alle besprochenen Erweiterungen für **MobileManager** (persönlicher Power-User-Build), geordnet nach technischer Abhängigkeit, Alltagsnutzen und Umsetzungsaufwand. Sie knüpft direkt an die bestehende Modulstruktur (`app` & `feature-launcher`) an.

---

## Phase 4.0 – Fundament, Qualität & Release-Härtung (Tech Debt)

Bevor neue komplexe Hintergrunddienste dazukommen, sollten diese strukturellen Lücken geschlossen werden:

- [ ] **Netzwerksicherheits-Konfiguration:** `app/src/main/res/xml/network_security_config.xml` anlegen (`cleartextTrafficPermitted="false"`).
- [ ] **R8 / ProGuard absichern:** `app/proguard-rules.pro` gezielt für Room, Hilt, AIDL/Shizuku und `FaceAnalyzer` erweitern und Release-Builds testen.
- [ ] **Automatisierte Tests (`androidTest/`):**
  - Room-Migrations-Tests für `AppDatabase` (Schema-Version 1 → 3+).
  - Grundlegende Compose-UI-Tests für kritische Flows (Onboarding, VPN-Toggle, App-Stopper).
- [ ] **CI-Pipeline (`.github/workflows/`):** Automatischer Gradle-Build, Android Lint, Ktlint/Detekt und Unit-Tests bei jedem Push/PR.

---

## Phase 4.1 – Netzwerksicherheit & VPN-Ausbau (`data/tracker` & `data/security`)

Aufbauend auf `TrackerVpnService`, `DnsPackets` und `SecurityMonitors`:

### Schnell umsetzbar (DNS- & VPN-Steuerung)
- [ ] **Per-App-Firewall:** WLAN- und Mobilfunk-Zugriff pro App über `VpnService.Builder` sperren, inkl. Profil-Umschaltung (z. B. *Unterwegs* vs. *Zuhause*).
- [ ] **Screen-Off-Kill-Switch:** Automatische Blockierung von Hintergrund-Traffic unwichtiger Apps, sobald das Display länger als $X$ Minuten ausgeschaltet ist (`ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON`).
- [ ] **Dynamische Blocklisten & DoH:**
  - Externe Blocklisten (z. B. Hagezi, OISD) abonnieren und per `WorkManager` automatisch aktualisieren.
  - Abgefangene DNS-Anfragen verschlüsselt per **DNS-over-HTTPS (DoH)** auflösen.
- [ ] **Private-DNS-Schnellwechsel:** Automatischer Wechsel des System-Private-DNS je nach Netzwerk (über `ShizukuManager` / `WRITE_SECURE_SETTINGS`).

### Fortgeschritten (Netzwerk-Analyse & Full-Traffic)
- [ ] **Unsicheres-WLAN-Wächter:** Warnung oder automatische VPN-Aktivierung bei offenen WLANs, schwacher Verschlüsselung (ab API 31 via `getCurrentSecurityType()`), wechselnden BSSIDs (Evil-Twin-Hinweis) oder auffälligen Captive-Portal-DNS-Servern. *(Hinweis: erfordert `ACCESS_FINE_LOCATION` / `ACCESS_BACKGROUND_LOCATION` für SSID/BSSID).*
- [ ] **App-Verbindungs-Log & Geo-IP-Warnung:** Live-Übersicht kontaktierter Domains/IPs pro App via `ConnectivityManager.getConnectionOwnerUid()` (API 29+) mit Warnung bei Malware-/Phishing-Domains oder ungewöhnlichen Zielländern.
- [ ] **Klartext-HTTP-Blocker (Port 80):** Blockieren oder Protokollieren unverschlüsselter HTTP-Verbindungen *(erfordert Full-Traffic-Routing, idealerweise über eine native Bibliothek wie `tun2socks` / `netstack`)*.
- [ ] **Netzwerk-Geschwindigkeits- & Daten-Monitor:** Optionaler Live-Indikator für Up-/Download-Rate sowie mobile Tageslimits mit Warnung.

> **Plattform-Hinweis:** Android erlaubt nur ein aktives VPN gleichzeitig. Ein klarer Hinweis in der UI hilft, falls bereits ein externes VPN (z. B. WireGuard) aktiv ist.

---

## Phase 4.2 – 2FA-App-Locker (`data/extras`, `data/guard`, `data/notifications`)

Aufbauend auf `ForegroundApps`, `FocusOverlay`, `AccessibilityService`, `device_admin.xml` und `NotificationArchive`:

- [ ] **Multi-Faktor-App-Sperre (2FA):**
  - Faktor 1: System-Biometrie (`BiometricPrompt`).
  - Faktor 2 (wählbar): Eigene App-PIN/Passphrase, **NFC-Tag / YubiKey** (echtes Besitz-2FA), **TOTP-Einmalcode** oder optionale Geheimfrage.
  - *(Hinweis: Getrenntes „Fingerabdruck + Gesichtscan“ ist über Androids `BiometricPrompt` nicht erzwingbar – daher Fokus auf Biometrie + Wissen/Besitz).*
- [ ] **Kontext-basiertes MFA:** Im vertrauten Heim-WLAN oder bei verbundener Smartwatch reicht Faktor 1; unterwegs wird automatisch Faktor 2 verlangt.
- [ ] **Sichere Speicherung & UX:**
  - Vollbild-Sperre mit `FLAG_SECURE` (unsichtbar in der Recents-Übersicht).
  - Konfigurierbarer Freigabe-Timer (z. B. 5 Min. oder bis Display aus).
  - PINs/Antworten ausschließlich gesalzen & gehasht (Argon2/PBKDF2) + Schlüssel im **Android Keystore**.
  - 3–5 Einmal-Recovery-Codes beim Einrichten generieren.

---

## Phase 4.3 – Anti-Umgehung, Selbstschutz & Maximal-Härtung (Tamper Protection)

Damit MobileManager nicht einfach über Einstellungen, Beenden-Erzwingen, Deinstallation oder Neustarts ausgehebelt werden kann:

### Stufe 1 – Standard-Selbstschutz (ohne Device Owner)
- [ ] **Kritische System-Screens mitsperren:** Automatische 2FA-Sperre für:
  - Android-Einstellungen (`com.android.settings`) – insbesondere App-Info, Bedienungshilfen, Geräteadmins und VPN-Einstellungen.
  - Paket-Installer (`com.android.packageinstaller` / `com.google.android.packageinstaller`), um Deinstallations-Dialoge abzufangen.
  - MobileManager selbst (Einstellungen & Regeln nur nach 2FA änderbar).
- [ ] **Deinstallationsschutz via `DeviceAdmin`:** Aktivierung über `device_admin.xml`, sodass MobileManager nicht deinstalliert werden kann, solange der Admin-Status aktiv ist (und der Admin-Screen durch die Settings-Sperre geschützt ist).
- [ ] **Split-Screen- & Benachrichtigungs-Lücken schließen:**
  - Im `AccessibilityService` **alle sichtbaren Fenster** (nicht nur das Hauptfenster) prüfen, um Split-Screen- und Pop-up-Fenster-Umgehungen zu blockieren.
  - Benachrichtigungen gesperrter Apps über `NotificationListenerService` ausblenden/maskieren, damit Inhalte nicht in der Statusleiste gelesen oder beantwortet werden können.
- [ ] **Boot-Schutz & gegenseitiger Watchdog:**
  - Sofortstart nach Neustart via `RECEIVE_BOOT_COMPLETED` mit persistent gespeichertem Sperrstatus.
  - Gegenseitige Überwachung zwischen `GuardService`, `AccessibilityService`, `NotificationListenerService` und `WorkManager`: Wird ein Dienst beendet oder entzogen, erfolgt sofort ein Alarm und automatischer Neustart-Versuch.
- [ ] **Always-On-VPN-Prüfung:** Hinweis & Prüfung, ob Androids systemseitiges *„Dauerhaft aktiviertes VPN“* + *„Verbindungen ohne VPN blockieren“* aktiv ist, damit der `TrackerVpnService` nicht einfach weggeschaltet wird.
- [ ] **Manipulations- & Einbruch-Protokoll (Tamper-Log):**
  - Protokolliert Fehlversuche, versuchte Öffnungen von App-Info/Deinstallation sowie Dienst-Unterbrechungen mit Zeitstempel in `SecurityEventRepository`.
  - Optional: Automatisches Frontkamera-Foto nach $N$ fehlgeschlagenen Entsperrversuchen.
- [ ] **Strict Mode / Cool-Down (gegen Impuls-Umgehung):** Optionaler Modus, bei dem Schutzregeln oder Fokus-Sperren nicht sofort deaktiviert werden können (z. B. nur mit NFC-Tag oder nach einer Wartezeit von 5–15 Minuten).

### Stufe 2 – Maximal-Härtung (Optional: Device-Owner-Modus per einmaligem ADB-Setup)
Da die App rein privat genutzt wird, schließt der Android-**Device-Owner-Modus** (`dpm set-device-owner`) auch die letzten System-Lücken ohne Root:
- [ ] **Safe-Mode blockieren:** `UserManager.DISALLOW_SAFE_BOOT` verhindert das Aushebeln über den abgesicherten Modus.
- [ ] **Deinstallation & App-Steuerung sperren:** `setUninstallBlocked()` und `DISALLOW_APPS_CONTROL` verhindern systemseitig *Beenden erzwingen*, *Daten löschen* und *Deinstallieren* für MobileManager.
- [ ] **Always-On-VPN systemseitig erzwingen:** `setAlwaysOnVpnPackage(..., lockdownEnabled = true)` verhindert das Abschalten des lokalen VPNs.
- [ ] **USB-Debugging sperren (optional):** `DISALLOW_DEBUGGING_FEATURES` verhindert das Aushebeln per PC/ADB im Alltag (mit eigenem 2FA-Schalter in MobileManager zum gezielten Freischalten beim Entwickeln).

---

## Phase 4.4 – Shizuku-Power-Tools & OS-Nice-to-Haves (`data/shizuku` & `data/apps`)

Aufbauend auf `ShizukuManager`, `IShellService.aidl` und `ProtectedAppsProvider`:

- [ ] **Sauberes Shizuku-Fallback-UX:** Automatische Status-Prüfung nach Neustarts mit 1-Klick-Anleitung für *Kabelloses Debugging* (Android 11+).
- [ ] **Erweitertes App-Ops-Management:** Feingranulare Steuerung versteckter Rechte (`cmd appops set <pkg> ...`) für `RUN_IN_BACKGROUND`, `WAKE_LOCK`, `READ_CLIPBOARD` und `VIBRATE`.
- [ ] **Bloatware-Manager & APK-Backup:**
  - System-Bloatware sicher deaktivieren/reaktivieren (`pm disable-user --user 0 <pkg>`) geschützt durch `ProtectedAppsProvider`.
  - Mehrfach-Deinstallation ungenutzter Apps und lokaler APK-Export.
- [ ] **Auto-Revoke temporärer Berechtigungen:** Standort, Kamera oder Mikrofon zeitlich begrenzt (z. B. 15 Min.) freigeben und danach automatisch via `pm revoke` entziehen.
- [ ] **System- & Animations-Tweaks:** Regler für Animationsskalen (`window_animation_scale` auf `0.5x`/`0x`), Dark-Mode-Overrides und globales Cache-Trimmen (`pm trim-caches`).
- [ ] **Koffein-Modus (Keep-Screen-On):** Schnelleinstellungs-Tile (15 / 30 / 60 Min.) ohne Änderung des globalen Display-Timeouts.

---

## Phase 4.5 – Datenschutz, Guard & Benachrichtigungen (`data/guard`, `data/battery`, `data/notifications`)

- [ ] **Zwischenablage-Wächter:** Automatisches Leeren der Zwischenablage nach 30–60 Sekunden (Schutz für Passwörter/TANs) + Warnung bei unerwartetem Zugriff.
- [ ] **Update- & Berechtigungs-Diff:** Automatische Prüfung nach App-Installationen/-Updates auf neu hinzugekommene kritische Berechtigungen (Hintergrund-Standort, Overlay, Bedienungshilfen).
- [ ] **USB-Daten- & Lade-Schutz:** Warnung bei aktiver Datenverbindung (MTP/ADB) an fremden USB-Ports (Juice-Jacking-Hinweis).
- [ ] **Sensoren-Protokoll & Killswitch-Absprung:** Protokollierung von Hintergrund-Zugriffen auf Kamera, Mikrofon und Standort inkl. schnellem Absprung zum Android-Sensoren-Aus-Schalter.
- [ ] **Benachrichtigungs-Digest & Spam-Filter:** Schlagwort-Filter zum stummen Archivieren von Werbung + gebündelte Tages-Zusammenfassung zu festen Uhrzeiten.
- [ ] **Akku-Gesundheit & Lade-Statistik:** Erweiterung von `BatteryGuard` um 80%-Lade-Limit-Warnung, Temperatur-Verlauf, geschätzte Ladezyklen und Top-Verbraucher pro Stunde.
- [ ] **Intelligente Ruhezeit / Schlaf-Automatik:** Wenn das Gerät nachts lädt und ruht → Funk/Sync drosseln, Benachrichtigungen ins `NotificationArchive` umleiten, morgens zum Wecker wiederherstellen.

---

## Phase 4.6 – Automation, Quick-Settings & Ökosystem (`data/bluetooth`, `data/extras`)

- [ ] **Erweiterte Automation-Engine:** Bestehende Bluetooth-Regeln um neue Trigger erweitern: **WLAN-SSID**, **Uhrzeit**, **Ladestatus** und **App-Start**.
- [ ] **App-spezifische Display- & Audio-Profile:** Pro App festlegen: Bildschirm-Drehung erzwingen, Display-Timeout verlängern (Lese-/Rezept-Apps) oder Medienlautstärke beim Öffnen auf 0 setzen.
- [ ] **Quick-Settings-Tiles & Homescreen-Widgets (Jetpack Glance):** Direktschalter in der Statusleiste für Fokus-Modus, Tracker-VPN, App-Stopper, Launcher-Profil und Koffein-Modus.
- [ ] **Verschlüsseltes Komplett-Backup (`.mmbackup`):** Passwortgeschützter Export & Import aller Launcher-Layouts, Automation-Regeln, Blocklisten-Whitelists und Guard-Einstellungen.
- [ ] **Intent- & App-Shortcut-Schnittstelle:** Kernaktionen als Android-Shortcuts / Intents bereitstellen (nutzbar mit NFC-Tags oder Tasker).

---

## Phase 4.7 – Custom Launcher (`feature-launcher`)

- [ ] **Graustufen- / Text-Only-Fokusmodus:** Ein-Klick-Umschaltung auf eine ablenkungsfreie Textliste, gekoppelt an `FocusOverlay`.
- [ ] **Versteckter App-Tresor:** Apps aus dem App-Drawer ausblenden und nur über eine versteckte Geste oder Biometrie öffnen.
- [ ] **Globale Schnellsuche (Universal Search):** Wisch nach unten durchsucht Apps, Kontakte, Einstellungen, lokale Dateien und MobileManager-Aktionen (*„VPN an“*, *„Cache leeren“*).
- [ ] **Frei belegbare Homescreen-Gesten:** Doppeltippen zum Sperren (via `AccessibilityService` / `DeviceAdmin`), Wischen zum Starten von Automationen oder App-Stopper.

---

## Phase 4.8 – Speicher, Medien & Diagnose (`data/media`, `data/extras`)

- [ ] **Geplante Aufräum-Routinen:** Automatische Erkennung alter APK-Dateien, leerer Ordner und Messenger-Medien-Duplikate + wöchentliche Erinnerung für abgelaufene Screenshots/Downloads.
- [ ] **Foto- & Video-Kompressor:** Platzsparendes Neukodieren direkt im Cleaner (z. B. 4K → 1080p, JPEG → WebP/HEIC) statt nur Löschen.
- [ ] **EXIF-Metadaten-Entferner:** Integration ins Android-Teilen-Menü (Share Sheet), um GPS- und Kameradaten vor dem Versenden zu entfernen.
- [ ] **Hardware-Schnelltest:** Erweiterung von `DiagnosticsAndBackup` um interaktive Tests für Touchscreen-Raster, Pixelfehler, Lautsprecher (L/R), Vibration, Mikrofon-Pegel und Speicher-Lesegeschwindigkeit.

---

## Empfohlene Umsetzungsreihenfolge (Quick Wins zuerst)

1. **Tech Debt & Absicherung (4.0):** `network_security_config.xml`, ProGuard, CI-Workflow.
2. **Schneller Alltagsnutzen (4.1 & 4.6):** Quick-Settings-Tiles, Per-App-Firewall + Screen-Off-Kill-Switch, dynamische Blocklisten + DoH.
3. **Sicherheit & Selbstschutz (4.2 & 4.3):** 2FA-App-Locker (Biometrie + PIN/NFC), Settings-/Installer-Sperre, Watchdog, Tamper-Log und optionaler **Device-Owner-Modus** gegen Safe-Mode & Force-Stop.
4. **Power-User-Ausbau (4.4–4.6):** Shizuku-AppOps & Bloatware-Manager, erweiterte Automation-Trigger, `.mmbackup`.
5. **Feinschliff (4.7 & 4.8):** Launcher-Extras, Medien-Kompressor & Hardware-Diagnose.