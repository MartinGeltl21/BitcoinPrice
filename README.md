# BitcoinPrice

Ein kleines Plugin für **Paper 26.2 / Java 25**. Es zeigt Bitcoin-Kurse in EUR/USD, Tagesänderung und Datenalter und bietet persönliche Anzeigen und Preisalarme. Alle Funktionen verwenden denselben Kurs-Cache; es wird keine externe Datenbank benötigt.

## Installation und Update

1. Die JAR aus dem Build-Artefakt dieses Branches herunterladen oder `mvn clean verify` mit JDK 25 ausführen.
2. Server stoppen und bestehende Plugin-JAR und den Ordner `plugins/BitcoinPrice` sichern.
3. Genau eine JAR installieren: `BitcoinPrice-2.0.0.jar`. Die Datei `original-*.jar` nicht verwenden.
4. Server vollständig starten. Bestehende `config.yml` bleibt lesbar; fehlende Einstellungen erhalten Standardwerte.

Die Minecraft-Welt und das bestehende `/data`-Volume bleiben erhalten. Eine Anleitung für Docker/Coolify steht in [docs/docker-compose.md](docs/docker-compose.md). Kein `/reload` oder Hotloader.

**Änderung in 2.0:** `/btc currency` ändert für Spieler die persönliche Währung. Die globale Einstellung heißt jetzt `/btc global currency`. Globale Änderungen und Refresh benötigen `bitcoinprice.admin` (standardmäßig OP). Ein Intervallwechsel löst keine Sofortnachricht mehr aus.

## Befehle für Spieler

| Befehl | Funktion |
| --- | --- |
| `/btc`, `/btc price` | Kurs, 24-Stunden-Veränderung und Datenalter |
| `/btceur`, `/btcusd` | Kurs in einer bestimmten Währung |
| `/btc help`, `/btchelp` | Gemeinsame Hilfe |
| `/btc settings` | Persönliche Einstellungen |
| `/btc currency EUR\|USD\|BOTH\|DEFAULT` | Persönliche Währung; DEFAULT übernimmt die globale Auswahl |
| `/btc locale de-DE\|en-US\|DEFAULT` | Persönliches Zahlenformat |
| `/btc display chat\|actionbar\|off` | Persönliche Anzeigeform |
| `/btc on`, `/btc off` | Eigene regelmäßige Anzeige aktivieren/deaktivieren |
| `/btc interval` | Globales Chat-Intervall ansehen |
| `/btc sats 10 EUR` | Gegenwert von 10 EUR in Satoshis, anhand des angezeigten Kurses |
| `/btc alert above 100000 EUR` | Alarm beim Überschreiten einer Grenze |
| `/btc alert below 80000 EUR` | Alarm beim Unterschreiten einer Grenze |
| `/btc alert list` | Eigene Alarme mit IDs ansehen |
| `/btc alert remove <ID>` | Eigenen Alarm entfernen; eindeutige kurze ID genügt |
| `/btc history 1h\|6h\|24h\|7d` | Gesammelten Kursverlauf als kompaktes Textdiagramm ansehen |
| `/btc portfolio` | Eigenes virtuelles Portfolio ansehen |
| `/btc portfolio start` | Simulation ausdrücklich starten, einmalig 10.000 virtuelle EUR |
| `/btc portfolio buy 100` | Für 100 virtuelle EUR BTC kaufen |
| `/btc portfolio sell 0.001` | 0,001 virtuelle BTC verkaufen |

Das Portfolio verwendet ausschließlich **Spielgeld**. Es verbindet keine Wallet, führt keine echten Transaktionen aus und setzt vorhandenes Guthaben bei erneutem `start` nicht zurück. Käufe werden auf ganze Satoshis, Verkäufe auf Euro-Cents abgerundet. Mit veralteten oder zeitlich nicht überprüfbaren Kursen wird nicht gehandelt.

Preisalarm und Actionbar sind optional. Ein Alarm beginnt mit einer Baseline beim ersten frischen Kurs und löst erst bei einer späteren Grenzüberschreitung aus. Wiederholungen werden durch Hysterese und eine Wartezeit begrenzt; maximal zehn Alarme pro Spieler. Bei minutenweisen Abfragen können kurze Kursbewegungen zwischen zwei Abfragen unbemerkt bleiben. Persönliches `/btc off` deaktiviert die regelmäßige Anzeige; ausdrücklich angelegte Alarme können mit `alert remove` entfernt werden.

## Befehle für Administratoren

| Befehl | Funktion |
| --- | --- |
| `/btc interval 1\|5\|10\|30\|60` | Globales Chat-Intervall ändern |
| `/btc global currency EUR\|USD\|BOTH` | Globale Standardwährung ändern |
| `/btc refresh` | Kurs aktualisieren und an empfangsberechtigte Spieler senden |
| `/btc on all`, `/btc off all` | Globale regelmäßige Chat-Nachrichten ein-/ausschalten |
| `/btc board create <name>` | Benannte Kurstafel als TextDisplay am eigenen Standort erstellen |
| `/btc board list` | Kurstafeln ansehen |
| `/btc board remove <name>` | Eine eigene Plugin-Kurstafel entfernen |

Globale Chat-Deaktivierung lässt persönliche, ausdrücklich aktivierte Actionbars und Alarme bestehen. Individuelle Präferenzen werden dadurch nicht überschrieben. Kurstafeln laden keine Chunks dauerhaft nach und werden nach einem Neustart wiedererkannt. Es werden ausschließlich vom Plugin markierte Anzeigen verwaltet.

## Konfiguration

Die kommentierte [config.yml](src/main/resources/config.yml) enthält alle Optionen. Wesentliche Standardwerte:

- Chat-Intervall 10 Minuten, Standardwährung EUR, Zahlenformat `de-DE`.
- Gemeinsamer Cache 60 Sekunden. Gleichzeitig laufende Anfragen werden zusammengefasst.
- API-Timeout 10 Sekunden; globaler Refresh hat 30 Sekunden Wartezeit.
- Bei API-Fehlern höchstens fünf Minuten alte gespeicherte Daten, ausdrücklich als veraltet gekennzeichnet. Alarme und virtuelle Trades verwenden solche Daten nicht.
- Anbieterzeitstempel werden getrennt vom lokalen Abrufzeitpunkt angezeigt. Fehlende Zeitstempel gelten als unbekannt.
- Überwachung aktiver Anzeigen/Alarme/Kurstafeln alle 60 Sekunden. Die Actionbar wird alle fünf Sekunden aus dem Cache neu angezeigt und erzeugt dabei keine zusätzlichen HTTP-Anfragen.
- Lokale Kursgeschichte höchstens sieben Tage. Gesammelt wird ab Installation während tatsächlicher Abfragen; keine rückwirkende historische Datenabfrage.

Bei leerem Server ohne geladene Kurstafeln erfolgen keine Hintergrundabfragen. Ein gespeicherter Alarm eines offline befindlichen Spielers hält die Abfrage nicht aktiv.

Optional kann ein CoinGecko-Demo-Key mit `api.demo-api-key` oder über `COINGECKO_DEMO_API_KEY` gesetzt werden. Er wird als Header versendet und nicht protokolliert. Keine Zugangsdaten im Repository speichern. HTTP 429 führt zu einer Wartephase; `Retry-After` wird berücksichtigt. Kein sofortiger Wiederholungsversuch durch Befehle.

Preis-, Actionbar-, Alarm- und Board-Nachrichten sind in `messages` mit `&`-Farben konfigurierbar. Platzhalter: `{price}`, `{currency}`, `{eur}`, `{usd}`, `{change}`, `{age}`, `{provider_age}`, `{status}`; bei Alarmen zusätzlich `{threshold}` und `{direction}`. Das Plugin nutzt Adventure-Komponenten.

## Gespeicherte Daten

Im Ordner `plugins/BitcoinPrice`:

- `config.yml`: globale Einstellungen.
- `players.json`: UUID-bezogene Präferenzen, Alarme und virtuelles Portfolio.
- `history.json`: begrenzte Kursgeschichte.
- `boards.yml`: benannte Kurstafeln und deren Zuordnung.

Spieler- und Historiedaten werden asynchron mit atomarem Dateiaustausch geschrieben; beim ordentlichen Stoppen wird ausstehende Speicherung abgeschlossen. Defekte Dateien werden erhalten bzw. gesichert und im Log gemeldet. Diese Dateien sollten zusammen mit der Plugin-Konfiguration gesichert werden.

## Entwicklung und Tests

```sh
mvn --batch-mode --no-transfer-progress clean verify
```

JUnit-Tests prüfen u. a. Cache, parallele Anfragen, ungültige Antworten, API-Limits, gespeicherte Daten, Alarmschwellen und virtuelle Guthaben. GitHub Actions führt dieselben Tests aus und stellt die fertige JAR bereit.

Ein zusätzlicher Test-Plugin unter `src/test/paper` prüft Befehle und echte TextDisplay-Entities auf Paper einschließlich Neustart. Dieser Test-Plugin gehört nicht auf einen Produktionsserver und wird nicht in die BitcoinPrice-JAR gepackt. Er benötigt einen lokalen Paper-Testserver und verwendet die Ports 25586/28761 auf 127.0.0.1. Der Testplayer ist ein Proxy; die visuelle Darstellung mit einem echten Minecraft-Client sollte ergänzend geprüft werden.

Unter Windows führt `scripts/paper-smoke.ps1` beide Serverphasen aus. Parameter: `-JavaHome`, `-MavenCommand`, `-PaperJar` und `-Workspace` (frisches Testverzeichnis außerhalb des Repositories). Das Verzeichnis benötigt eine bereits akzeptierte `eula.txt`; alternativ kann nach Lesen der Minecraft-EULA `-EulaAccepted` angegeben werden. Mit `-RuntimeCache` lässt sich ein vorhandener Paper-Laufzeitcache wiederverwenden.

## Quellen und Lizenz

[CoinGecko einfache Preise](https://docs.coingecko.com/demo/reference/simple-price), [CoinGecko Rate Limits](https://docs.coingecko.com/docs/errors-and-rate-limits), [Paper Scheduler](https://docs.papermc.io/paper/dev/scheduler/).

MIT-Lizenz; siehe [LICENSE](LICENSE). Autor: [Martin Geltl](https://github.com/MartinGeltl21).
