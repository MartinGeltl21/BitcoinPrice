# Changelog

Alle wichtigen Änderungen am BitcoinPrice-Plugin werden in dieser Datei dokumentiert.

Das Format basiert auf [Keep a Changelog](https://keepachangelog.com/de/1.0.0/),
und dieses Projekt folgt [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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