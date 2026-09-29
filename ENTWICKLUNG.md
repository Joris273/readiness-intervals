# Readiness – Entwicklung

Diese Datei beschreibt, wie man den Quellcode baut, testet, signiert und aufs Gerät bringt, wie er
aufgebaut ist und worauf man achten muss.

```
app\        Android-App (Kotlin, Jetpack Compose, Glance-Widget, WorkManager)
docs\       Screenshots
tools\      Dateiliste für die CI, Abruf von Backtest-Daten
```

---

## 1. Voraussetzungen

| Werkzeug | Version (gebaut und geprüft) |
|---|---|
| JDK | 17 (17.0.13) als `JAVA_HOME` — **nicht** JDK 21+, Gradle 8.9 bricht damit ab |
| Android SDK | Platform 34, Build-Tools 34, Platform-Tools (adb) |
| Gradle | 8.9 (kein Wrapper im Repo; die CI installiert Gradle selbst), Android Gradle Plugin 8.5.2, Kotlin 2.0.20 |

---

## 2. App bauen

```powershell
# local.properties anlegen (einmalig pro Rechner, per .gitignore ausgeschlossen)
#   sdk.dir=C\:\\Users\\<du>\\Android\\sdk
#   readiness.signing.file=C\:\\<Pfad außerhalb des Repos>\\signing.properties   (optional)
gradle :app:testDebugUnitTest      # 93 Unit-Tests
gradle :app:assembleDebug          # -> app\build\outputs\apk\debug\app-debug.apk
gradle :app:assembleDemo           # Demo mit synthetischen Daten, eigene Kennung
```

**Signatur:** Der Schlüssel liegt nicht im Repository. `local.properties` verweist mit
`readiness.signing.file=<Pfad>\signing.properties` auf eine Datei außerhalb des Repos. Diese enthält
`readiness.keystore`, `readiness.keystore.password`, `readiness.key.alias` und
`readiness.key.password`. Fehlt sie, wird mit dem Standard-Debug-Schlüssel des Rechners signiert.

**Eigene Builds:** Eine anders signierte APK lässt sich nicht über die Release-Version installieren.
Android verlangt dann erst die Deinstallation, und dabei gehen API-Key, Einstellungen und der
Kraftdaten-Cache verloren.

> Die Releases sind mit diesem Zertifikat signiert (SHA-256):
> `9a86b60977921d41dd1a2ecb605e17a20aea5647a78afb6fa1d23cade642cf33`
> Prüfen lässt es sich mit `apksigner verify --print-certs readiness-4.0.apk`.

**CI (GitHub Actions):** Prüft zuerst die Dateiliste (`tools/expected-files.txt`), kompiliert, führt
die Tests aus und baut die APK. Mit den Repository-Secrets `READINESS_KEYSTORE_B64`,
`READINESS_KEYSTORE_PASSWORD`, `READINESS_KEY_ALIAS` und `READINESS_KEY_PASSWORD` signiert sie mit
dem Release-Schlüssel, sonst mit einem Wegwerf-Debugschlüssel.

**Dateiliste:** Jede neu angelegte oder gelöschte `.kt`-Datei unter `app/src` muss in
`tools/expected-files.txt` nachgeführt werden, sonst bricht der erste CI-Schritt ab. So fallen beim
Hochladen liegengebliebene Dateien einer Vorversion sofort auf.

**Versionen:** `versionCode` und `versionName` stehen in `app\build.gradle.kts`. Jedes Update braucht
einen höheren `versionCode`.

### Bekannte Fallen beim Bauen (Windows)
- **Gradle sperrt `app\build`** („Unable to delete directory“). Abhilfe: `app\build` löschen und
  noch einmal bauen. `org.gradle.vfs.watch=false` steht schon in `gradle.properties`.
- **Ein fehlgeschlagener Build hinterlässt die alte APK.** Vor jedem `adb install` den Zeitstempel der
  APK prüfen.

---

## 3. Aufs Gerät bringen und prüfen

```powershell
$adb = "$env:USERPROFILE\Android\sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell dumpsys package com.readiness.app | findstr versionName
& $adb logcat -d -s Readiness            # geschluckte Fehler der Daten- und Repo-Schicht
& $adb logcat -d -b crash
```

Die Demo (`com.readiness.app.demo`) installiert sich **neben** der echten App und rechnet mit einem
synthetischen Athleten (`repo/DemoData.kt`). Sie braucht keinen API-Key und eignet sich für
Screenshots und zum Ausprobieren.

---

## 4. Tests

Die Domäne (`app\src\main\java\com\readiness\app\domain\`) ist reines Kotlin ohne Android und
vollständig durch Unit-Tests abgedeckt (`app\src\test\…`).

| Test | Prüft |
|---|---|
| `MonteCarloTest` | Fehlalarm- und Erkennungsquote auf 500 verrauschten Reihen: unauffällige Tage < 10 % nicht GRÜN, < 2 % ROT; nach hartem Tag < 5 % ROT; echte Suppression ≥ 80 % erkannt |
| `ProgressionTest` | Formaufbau: Scheinfortschritt aus Rauschen < 10 %, echte Verbesserung ≥ 80 % erkannt; Versuchserkennung, Rollen-Versatz |
| `CrossCheckTest` | HRV-Trend, akuter Einbruch, Sättigung, Form relativ zur CTL, Störfaktoren |
| `LoadHistoryTest` | Zonenparser, harte Reize, Serien, Monotonie, ACWR |
| `MissingDataTest` | fehlende, veraltete und ungültige Werte, Mindestdatenlage, Schlaf, Ruhepuls |
| `SubjectiveTest`, `ScoreFunctionsTest`, `StreamsTest`, `ScoreHistoryTest`, `AtomicWriteTest`, `SnapshotTextTest` | Teilfunktionen |
| `BacktestTest` | echte Daten Tag für Tag (übersprungen ohne Daten) |

**Schwellen ändern:** Alle Schwellen und Gewichte stehen in `domain\ScoringParams.kt`. Danach immer
`MonteCarloTest` und `ProgressionTest` laufen lassen. Die dortigen Grenzen sind die Abnahmekriterien.

**Backtest auf eigenen Daten:**

```powershell
.\tools\fetch-backtest-data.ps1 -ApiKey <intervals.icu API-Key> -Out backtest.json
$env:READINESS_RAW = (Resolve-Path backtest.json)
gradle :app:testDebugUnitTest --tests '*BacktestTest*' -i
```

`backtest*.json` ist per `.gitignore` ausgeschlossen, denn die Datei enthält persönliche
Gesundheitsdaten.

---

## 5. Aufbau der App

| Datei (`app\src\main\java\com\readiness\app\`) | Aufgabe |
|---|---|
| `domain\ReadinessEngine.kt` | Setzt die Schritte zusammen, reine Funktion |
| `domain\MetricsBuilder.kt` | Tageskennwerte: HRV-Band und -Trend, Ruhepuls, Schlaf, Befinden, Form, ACWR |
| `domain\ScoreEngine.kt` | Teilscores, Gesamtwert, limitierende Faktoren, Ampel |
| `domain\LoadHistory.kt` | harte Reize, Serien, Qualitätstage, Monotonie, Belastungsabzug |
| `domain\Progression.kt`, `EffortQuality.kt` | Formaufbau: Dosis gegen Antwort, Maximalversuche |
| `domain\Streams.kt` | Roh-Streams: 1-Hz-Raster, Artefakte, Kraftfenster |
| `domain\ScoringParams.kt` | alle Schwellen und Gewichte |
| `domain\Reasons.kt` | Entscheidungen als Codes |
| `data\SnapshotMapper.kt` | **alle** Texte der App |
| `data\…Store.kt`, `AtomicWrite.kt` | Caches, atomar geschrieben |
| `repo\ReadinessRepository.kt` | Orchestrierung, eine Instanz je Prozess, Mutex |
| `repo\IcuRepository.kt`, `IcuMapper.kt`, `TorqueRepository.kt` | Abruf und Übersetzung |
| `repo\DemoData.kt` | synthetischer Athlet für den Demo-Build |
| `ui\`, `widget\`, `work\` | Oberfläche, Homescreen-Widgets, Hintergrundläufe |

**Regeln beim Ändern:**
- Die Domäne erzeugt keine Texte. Neue Befunde bekommen einen Code in `Reasons.kt` und ihren Satz
  im `SnapshotMapper`.
- Maximum-Marker (Kraft, eFTP) brauchen einen Maximalversuch. Ein Anstieg zählt immer, ein
  Rückgang nur mit Versuch (`EffortQuality`).
- Entschieden wird über Trends und bestätigte Befunde, nicht über einzelne verrauschte Tageswerte.
