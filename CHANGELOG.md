# Changelog

Alle wichtigen Änderungen am BitcoinPrice-Plugin werden in dieser Datei dokumentiert.

Das Format basiert auf [Keep a Changelog](https://keepachangelog.com/de/1.0.0/),
und dieses Projekt folgt [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
