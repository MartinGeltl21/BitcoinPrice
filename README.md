# BitcoinPrice Plugin

Ein Minecraft-Plugin für Paper-Server, das den aktuellen Bitcoin-Kurs über die CoinGecko API abruft und im Spiel anzeigt.

## Funktionen

- Automatisches Abrufen und Anzeigen des Bitcoin-Preises in konfigurierbaren Intervallen
- Unterstützung für Euro (EUR) und US-Dollar (USD)
- Farbige Anzeige des Bitcoin-Preises im Spiel-Chat
- Einfache Einstellung des Aktualisierungsintervalls und der Währung über In-Game-Befehle
- Manuelles Abrufen des aktuellen Bitcoin-Preises
- Individuelle Kontrolle: Spieler können Bitcoin-Preis-Nachrichten für sich selbst aktivieren oder deaktivieren
- Globale Steuerung: Administratoren können die Benachrichtigungen für alle Spieler ein- oder ausschalten

## Anforderungen

- Minecraft 1.21.11
- Paper Server oder einen kompatiblen Fork (Purpur, etc.)
- Java 17 oder höher

## Installation

1. Lade die neueste Version des Plugins aus dem [Releases](https://github.com/MartinGeltl21/BitcoinPrice/releases)-Bereich herunter.
2. Kopiere die JAR-Datei in den `plugins`-Ordner deines Minecraft-Servers.
3. Starte den Server neu oder lade das Plugin mit einem Plugin-Manager.

## Konfiguration

Die Konfigurationsdatei (`config.yml`) wird automatisch beim ersten Start des Plugins erstellt und enthält folgende Einstellungen:

```yaml
# Das Intervall, in dem der Bitcoin-Preis automatisch im Chat angezeigt wird
# Mögliche Werte: 1, 5, 10, 30, 60 (in Minuten)
price-interval: 10

# Die Währung, in der der Bitcoin-Preis angezeigt wird
# Mögliche Werte: EUR, USD, BOTH
price-currency: EUR

# Einstellungen für die CoinGecko API
api:
  # Die URL der CoinGecko API
  url: "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=eur,usd&precision=2"
  # Timeout in Millisekunden
  timeout: 10000

# Nachrichteneinstellungen
messages:
  # Präfix vor jeder Nachricht
  prefix: "&6[BitcoinPrice] &r"
  # Farbe des Bitcoin-Preises
  price-color: "&6"
  # Meldung bei API-Fehler
  api-error: "&cFehler beim Abrufen des Bitcoin-Preises. Bitte versuche es später erneut."
```

## Befehle

| Befehl | Beschreibung |
| --- | --- |
| `/btc` | Zeigt den aktuellen Bitcoin-Preis an |
| `/btc help` | Zeigt Hilfe und erklärt alle Befehle |
| `/btc interval <1\|5\|10\|30\|60>` | Ändert das Intervall für automatische Updates (in Minuten) |
| `/btc currency <EUR\|USD\|BOTH>` | Ändert die Währung für die Preisanzeige |
| `/btc refresh` | Aktualisiert den Bitcoin-Preis sofort |
| `/btc on` | Aktiviert Bitcoin-Preis Benachrichtigungen für dich selbst |
| `/btc off` | Deaktiviert Bitcoin-Preis Benachrichtigungen für dich selbst |
| `/btc on all` | Aktiviert Bitcoin-Preis Benachrichtigungen für alle Spieler (Admin-Berechtigung erforderlich) |
| `/btc off all` | Deaktiviert Bitcoin-Preis Benachrichtigungen für alle Spieler (Admin-Berechtigung erforderlich) |
| `/btceur` | Zeigt den aktuellen Bitcoin-Preis in Euro an |
| `/btcusd` | Zeigt den aktuellen Bitcoin-Preis in US-Dollar an |

## Berechtigungen

| Berechtigung | Beschreibung |
| --- | --- |
| `bitcoinprice.admin` | Erlaubt die Verwendung der Befehle `/btc on all` und `/btc off all` |

## Fehlerbehebung

### API-Fehler

Wenn du Fehler wie "Fehler beim Abrufen des Bitcoin-Preises" erhältst:
1. Überprüfe deine Internetverbindung
2. Die CoinGecko API hat ein Limit von etwa 30 Anfragen pro Minute für kostenlose Nutzung
3. Bei anhaltenden Problemen erhöhe den `timeout`-Wert in der Konfiguration

## Geplante Funktionen

- Unterstützung für weitere Kryptowährungen
- Benachrichtigung bei signifikanten Preisänderungen
- Preisdiagramme über die Zeit
- Konfigurierbare Farbgestaltung

## Entwicklung

### Voraussetzungen

- Java 17 JDK oder höher
- Maven

### Kompilieren

```bash
mvn clean package
```

Die kompilierte JAR-Datei findest du dann im `target`-Ordner.

## Lizenz

Dieses Projekt ist unter der MIT-Lizenz lizenziert. Siehe die [LICENSE](LICENSE)-Datei für Details.

## Autor

**Martin Geltl** ([@MartinGeltl21](https://github.com/MartinGeltl21))

## Mitwirken

Beiträge sind immer willkommen! Bitte folge diesen Schritten:

1. Forke das Repository
2. Erstelle einen Feature-Branch (`git checkout -b feature/AmazingFeature`)
3. Committe deine Änderungen (`git commit -m 'Add some AmazingFeature'`)
4. Pushe den Branch (`git push origin feature/AmazingFeature`)
5. Eröffne einen Pull Request

## Danksagungen

- [CoinGecko](https://www.coingecko.com/) für die öffentliche Kryptowährung-API
- Alle Mitwirkenden und Tester 