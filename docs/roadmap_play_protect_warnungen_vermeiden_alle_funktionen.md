# Roadmap: Play Protect nicht auslösen – ohne Funktionen zu streichen (MobileManager)

> **Hinweis für den KI-Assistenten (Claude):**
> Alle bisherigen Funktionen der App (inklusive `AccessibilityService`-Fallback, Shizuku, Overlay und Nutzungszugriff) **müssen vollständig erhalten bleiben**. Ziel dieser Roadmap ist es, die App im Code, im Manifest und beim Build so sauber und eng einzugrenzen, dass die Heuristiken von Google Play Protect sie nicht als Banking-Trojaner oder Schadsoftware einstufen. Arbeite die technischen Schritte (Phase 1 und 2) direkt im Projekt ab; Phase 3 und 4 führt der Entwickler beim Build bzw. auf dem Gerät aus.

---

## Phase 1: `AccessibilityService` strikt eingrenzen (Wichtigster Schritt)

**Warum:** Play Protect blockiert vor allem weit offene Bedienungshilfen, die potenziell Passwörter, Banking-Apps oder Eingaben mitlesen können. Wenn der Service technisch nachweisbar nur auf die Android-Einstellungen zugreift, sinkt der Risiko-Score erheblich.

### Aufgaben im Detail
* [ ] **Paket-Filter erzwingen:** In der `res/xml/accessibility_service_config.xml` zwingend `android:packageNames="com.android.settings"` setzen, damit der Service ausschließlich die Android-Systemeinstellungen (für „Beenden erzwingen“) sieht.
* [ ] **Event-Typen minimieren:** Nur `typeWindowStateChanged` und `typeWindowContentChanged` verwenden.
  * **Verboten:** `typeViewTextChanged` (Keylogger-Verdacht), `typeAllMask`, `flagRequestFilterKeyEvents`.
* [ ] **Kein unnötiger Code-Zugriff:** Kein dynamisches Nachladen von Code (`DexClassLoader`), keine versteckten Reflection-Aufrufe und keine verschleierten Klick-Simulationen außerhalb des „Beenden erzwingen“-Dialogs.
* [ ] **Transparente Beschreibung:** In `android:description` eine klare, sachliche Beschreibung für den Nutzer hinterlegen (z. B. „Hilft beim automatischen Tippen auf ‚Beenden erzwingen‘ in den App-Einstellungen“).

### Muster-Vorlage für `res/xml/accessibility_service_config.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_service_description"
    android:notificationTimeout="100"
    android:packageNames="com.android.settings" />
```

* **Prüfung:** Der `AccessibilityService` reagiert ausschließlich in den Android-App-Infos und liest keinerlei Inhalte aus anderen Apps.

---

## Phase 2: Saubere Release- & Manifest-Konfiguration

**Warum:** Unbekannte Debug-Builds mit Standard-Paketnamen, veralteten SDK-Zielen oder unsicheren Netzwerk-Flags werden von Play Protect deutlich häufiger als verdächtig eingestuft.

### Aufgaben im Detail
* [ ] **Eindeutige `applicationId`:** In `app/build.gradle.kts` prüfen, dass eine individuelle Paket-ID genutzt wird (z. B. `de.deinname.mobilemanager` statt `com.example.*`).
* [ ] **Aktuelles `targetSdk`:** `targetSdk` auf die aktuelle Android-Version des Google Pixel setzen (mindestens API 35/36).
* [ ] **Manifest-Härtung (`AndroidManifest.xml`):**
  * `android:usesCleartextTraffic="false"` im `<application>`-Tag setzen (nur verschlüsseltes HTTPS).
  * `android:allowBackup="false"` setzen (oder saubere `dataExtractionRules` hinterlegen).
  * Prüfen, ob tatsächlich nur die wirklich implementierten Rechte angefordert werden (keine ungenutzten Platzhalter-Rechte).
* [ ] **Eigener Release-Keystore (`.jks`):**
  * In Android Studio über **Build → Generate Signed App Bundle / APK** einen eigenen Release-Keystore erstellen und dauerhaft für jeden Build verwenden.
  * Keystore-Datei und Passwörter sicher lokal aufbewahren (niemals in öffentliche Git-Repositories laden).
* [ ] **Moderate R8/ProGuard-Nutzung:** Standard-Release-Build nutzen; keine aggressiven Packer, Crypter oder künstliche Code-Verschleierung einsetzen (wirkt auf Scanner wie Malware).

* **Prüfung:** Der Release-Build kompiliert fehlerfrei als signierte `app-release.apk` (bzw. `.aab`) ohne Warnungen zu veralteten SDKs.

---

## Phase 3: Der richtige Installationsweg (Entscheidend für Sideloading)

**Warum:** Selbst bei perfekt sauberem Code löst das direkte Antippen einer `.apk`-Datei im Dateimanager auf dem Pixel oft den strengen Sideloading-Schutz aus.

### Weg A: Kostenlos für den Eigengebrauch (Pixel)
* [ ] **Alte Debug-Version entfernen:** Zuerst die bisherige Debug-Installation vollständig vom Pixel deinstallieren, damit die neue Release-Signatur sauber übernommen wird.
* [ ] **Installation per USB / ADB:**
  * Auf dem Pixel unter **Einstellungen → System → Entwickleroptionen** den Punkt *„Apps über USB überprüfen“* deaktivieren.
  * Die signierte `app-release.apk` direkt über Android Studio oder `adb install app-release.apk` installieren.
* [ ] **Falls per APK-Datei installiert wird:** Im Play-Protect-Dialog einmalig auf **„App scannen“** tippen. Durch die saubere Eingrenzung aus Phase 1 gibt der automatische Google-Scan die APK in der Regel nach kurzer Prüfung frei.

### Weg B: 100 % ohne Warnung auf jedem Gerät (Google Play Console)
* [ ] Einmaliges **Google Play Developer-Konto** anlegen (25 USD).
* [ ] Die signierte App (`.aab`) im **Internen Test-Track** (nicht öffentlich) hochladen und die eigene Google-E-Mail als Tester eintragen.
* [ ] **Vorteil:** Interne Tests durchlaufen keine strenge öffentliche Store-Review für den `AccessibilityService`, werden aber offiziell über den Play Store signiert und ausgeliefert – Play Protect vertraut der App danach dauerhaft ohne jede Meldung.

---

## Phase 4: Fallback bei anhaltendem Fehlalarm (False Positive)

Falls Play Protect trotz Phase 1 bis 3 eine sauber signierte `app-release.apk` noch fälschlicherweise blockiert:

* [ ] **Signatur konstant halten:** Nicht ständig neue Keystores erzeugen – Play Protect bewertet Vertrauen pro Entwickler-Signatur.
* [ ] **Fehlalarm melden:** Die betroffene `app-release.apk` über das offizielle **Google Play Protect Appeal-Formular** einreichen und kurz erklären, dass es sich um ein privates Gerätemanager-Tool handelt, dessen Bedienungshilfe strikt auf `com.android.settings` begrenzt ist.