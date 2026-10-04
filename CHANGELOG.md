# Changelog

Alle wichtigen Änderungen am BitcoinPrice-Plugin werden in dieser Datei dokumentiert.

Das Format basiert auf [Keep a Changelog](https://keepachangelog.com/de/1.0.0/),
und dieses Projekt folgt [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.2.0] - 2026-10-04

### Hinzugefügt
- Actionbar-Modi `continuous` und `interval`: dauerhaft sichtbar oder kurz im gewählten Minutenintervall.
- Persönliches Actionbar-Intervall von 1, 5, 10, 30 oder 60 Minuten; `DEFAULT` übernimmt das globale Intervall.
- OP-/Admin-Steuerung der Actionbar-Modi und persönlichen Intervalle über `/btc player <Name|UUID> display actionbar ...`.
- `/btc status` zeigt Cache, Anbieter-Datenalter, API-Wartezeit und eine laufende Abfrage, ohne eine neue HTTP-Anfrage auszulösen.

### Geändert
- Dauerhafte Actionbars werden jede Sekunde aus dem Cache erneuert, ohne zusätzliche HTTP-Abfragen und ohne die bisherigen Fünf-Sekunden-Lücken.
- Actionbars im Intervallmodus folgen ihrer eigenen Uhr; API-Aktualisierungen und manuelle Refreshes lösen keine zusätzliche Intervallanzeige aus.
- Die erste aktive Intervall-Actionbar erscheint sofort; spätere Anzeigen folgen dem gewählten Abstand mit der normalen Minecraft-Ausblendung. Ohne Minutenargument bleibt das gespeicherte persönliche Intervall erhalten.
- Ein globaler Intervallwechsel ändert das Chat-Intervall und den Actionbar-Standard, ohne ausdrücklich gewählte persönliche Intervalle zurückzusetzen.
- Gespeicherte ältere Spielerpräferenzen erhalten automatisch den Modus `continuous`; der alte Konfigurationsschlüssel `actionbar-seconds` wird ignoriert.
- Hilfetexte, Einstellungsanzeige, Dokumentation und Build-Artefakte für Version 2.2.0 aktualisiert.
- Einstellungen lösen `DEFAULT` zur wirksamen Währung und Sprache auf und zeigen, ob die automatische Anzeige aktiv ist. Blockierte Anzeigen nennen den benötigten Aktivierungsbefehl.

### Fehler behoben
- `/btc refresh` bestätigt keine erfolgreiche Aktualisierung, wenn nur ein veralteter Ersatzkurs verfügbar ist.
- Frische-/Veraltet-Kennzeichnungen berücksichtigen das Datenalter beim tatsächlichen Anzeigen, auch nach verzögerten Rückmeldungen.

## [2.1.1] - 2026-10-04

- Vollständige, gemeinsame Hilfeseiten für `/btc help [Seite]` und `/btchelp [Seite]` mit eindeutigen Befehlen und Navigation.
- Auch Intervall-/Währungsabfrage und Portfolio-Anzeige werden aufgeführt; OP-/Admin-Befehle bleiben nur Berechtigten sichtbar.
- README mit eigener Illustration, Funktionsübersicht und Anleitung für echte Ingame-Screenshots.
- Docker-Anleitung erläutert Updates über die vorhandene `PLUGINS`-Downloadquelle.

## [2.1.0] - 2026-10-04

### Hinzugefügt
- OP- und Admin-Steuerung für Intervall-Benachrichtigungen und persönliche Währungen einzelner Spieler, einschließlich gespeicherter UUID-Profile.
- Währungen GBP, CHF, CAD, AUD, JPY, CNY und INR zusätzlich zu EUR und USD für Kurse, Alarme, Satoshi-Umrechnung und Anzeigen.

### Geändert
- OPs können alle Plugin-Funktionen unabhängig von abweichenden Permission-Zuweisungen verwenden.
- Neutrale Projektmetadaten und Java-Packages unter `org.bitcoinprice`; Klarname und persönliche Hostnamen aus aktuellen Projektdateien entfernt.
- Kurstafel-Standardvorlage zeigt die global gewählte Währung; bestehende Vorlagen bleiben kompatibel.
- Gemeinsame API-Abfrage erweitert die Währungsliste bestehender URLs ohne zusätzliche Anfragen.
- Persönliche Aktivierung/Deaktivierung ändert nur Intervall-Benachrichtigungen, keine Alarme, Währung oder Portfolioguthaben.

## [2.0.0] - 2026-10-04

### Geändert
- Globale Intervall-/Währungsänderungen, Refresh und Kurstafeln benötigen Admin-Rechte.
- `/btc currency` ist für Spieler persönlich; globale Währung über `/btc global currency`.
- Gemeinsamer Kurs-Cache, Zusammenfassung paralleler Anfragen, Refresh-Wartezeit und begrenzte Fehler-/429-Wiederholungen.
- Validierte Konfiguration und Kursdaten, explizites Zahlenformat, getrennte Anbieter- und Abrufzeitstempel.
- Keine Hintergrundabfragen ohne aktive Abnehmer; sauberes Beenden laufender Arbeit.
- Einheitliche Adventure-Nachrichten, Hilfe, Fehlerbehandlung und Tab-Vervollständigung.

### Hinzugefügt
- Dauerhafte persönliche Währung, Sprache, Benachrichtigungen und Anzeigeform.
- 24-Stunden-Veränderung, optionale Actionbar, Satoshi-Umrechnung.
- Persönliche Preisalarme mit Grenzüberschreitung, Hysterese und Wartezeit.
- Persistente benannte TextDisplay-Kurstafeln und begrenzte lokale Kursgeschichte.
- Optionales virtuelles EUR/BTC-Portfolio mit ausdrücklichem Start und reinem Spielgeld.
- Automatisierte JUnit-Tests sowie zusätzlicher Paper-Test für Befehle, Entities und Neustart.

## [1.2.0] - 2026-10-04

- Paper 26.2 (API-Build 123) und Java 25 als Build- und Laufzeitbasis.
- Admin-Berechtigung mit OP-Standard in plugin.yml registriert; ungültigen mehrteiligen Alias entfernt.
- HTTP-Verbindungen auch bei Fehlern schließen, UTF-8-Antworten und dynamische Plugin-Version im User-Agent.
- GitHub-Actions-Build mit fertiger JAR und VPS-/Docker-Compose-/Coolify-Anleitung.
- Veraltete Build-Dateien aus Git entfernt und target/ ignoriert.

## [1.1.0] - 2025-05-04

### Hinzugefügt
- Neue Befehle:
  - `/btc on` und `/btc off` für individuelle Spielerbenachrichtigungen
  - `/btc on all` und `/btc off all` für globale Steuerung der Benachrichtigungen (nur für Administratoren)
- Berechtigungssystem für Admin-Befehle
- Verbesserte Fehlerbehandlung bei API-Anfragen

### Geändert
- Entfernt den `/btc menu` Befehl, da er redundant mit `/btc help` war
- Verbesserte README.md mit ausführlicheren Erklärungen
- Aktualisierte API-Konfiguration mit dem `precision=2` Parameter für genauere Preisangaben
- Erhöhter API-Timeout (10000ms statt 5000ms) für mehr Stabilität

### Fehler behoben
- Bessere Fehlerbehandlung bei Verbindungsproblemen
- Verbesserte Aufgabenplanung für Preisaktualisierungen

## [1.0.0] - 2025-04-20

### Erste Veröffentlichung
- Grundfunktionalitäten:
  - Automatisches Abrufen und Anzeigen des Bitcoin-Preises
  - Konfigurierbare Intervalle (1, 5, 10, 30, 60 Minuten)
  - Unterstützung für EUR, USD und beide Währungen
  - Basis-Befehle: `/btc`, `/btc help`, `/btc interval`, `/btc currency`, `/btc refresh`, `/btceur`, `/btcusd`
- Konfigurationssystem mit config.yml
- Verbindung zur CoinGecko API für Preisinformationen
