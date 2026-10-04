# BitcoinPrice

![BitcoinPrice — Bitcoin-Kurse direkt im Spiel. Blocklandschaft, Münze und stilisierte Kurstafel.](docs/assets/hero.svg)

*Eigene Vektorillustration; kein Spiel-Screenshot und keine realen Kursdaten.*

![Paper 26.2 · Java 25 · Version 2.3.0 · MIT-Lizenz](docs/assets/badges.svg)

Bitcoin-Kurse auf deinem Minecraft-Server: im Chat, in der Actionbar oder auf einer Kurstafel am Spawn. Dazu persönliche Währungen und Preisalarme — mit einem gemeinsamen Cache und ohne zusätzliche Datenbank.

[Features](#features) · [Schnellstart](#schnellstart) · [Spielerbefehle](#befehle-für-spieler) · [Adminbefehle](#befehle-für-administratoren) · [Konfiguration](#konfiguration) · [Screenshots](#screenshots)

## Features

| Funktion | Im Spiel |
| --- | --- |
| **Kurse mit Kontext** | Preis, Tagesänderung und getrenntes Abruf- und Datenalter |
| **Deine Anzeige** | Chat, dauerhafte Actionbar oder kurze Actionbar im eigenen Intervall; neun Währungen |
| **Kurstafeln** | Benannte TextDisplays, die Neustarts überstehen |
| **Persönliche Alarme** | Benachrichtigung bei einer gewählten Grenzüberschreitung |
| **Kursverlauf und Satoshis** | Lokale Kurshistorie und direkte Umrechnung |
| **Virtuelles Lernportfolio** | Freiwillige BTC-Simulation mit Spielgeld |

OPs können Einstellungen einzelner Spieler verwalten. Ohne Empfänger oder geladene Kurstafel ruhen die Hintergrundabfragen; API-Limits und veraltete Daten werden berücksichtigt.

## Schnellstart

**Voraussetzung:** Paper 26.2 und Java 25. Minecraft-Clients benötigen keine zusätzliche Mod.

### Installation und Update

1. Die JAR aus dem Build-Artefakt dieses Branches herunterladen oder `mvn clean verify` mit JDK 25 ausführen.
2. Server stoppen und bestehende Plugin-JAR und den Ordner `plugins/BitcoinPrice` sichern.
3. Genau eine JAR installieren: `BitcoinPrice-2.3.0.jar`. Die Datei `original-*.jar` nicht verwenden.
4. Server vollständig starten. Bestehende `config.yml` bleibt lesbar; fehlende Einstellungen erhalten Standardwerte.

Die Minecraft-Welt und das bestehende `/data`-Volume bleiben erhalten. Eine Anleitung für Docker/Coolify steht in [docs/docker-compose.md](docs/docker-compose.md). Kein `/reload` oder Hotloader.

### Im Spiel loslegen

```text
/btc
/btc currency EUR
/btc display actionbar continuous
/btc on
```

Der erste Befehl zeigt den Kurs. Die weiteren wählen die eigene Währung und aktivieren eine dauerhaft sichtbare Actionbar. Für eine kurze Anzeige alle fünf Minuten `/btc display actionbar interval 5` verwenden. Für regelmäßige Chat-Nachrichten `/btc display chat` verwenden. Als OP kannst du mit `/btc board create spawn` eine Kurstafel am eigenen Standort erstellen.

**Änderung in 2.0:** `/btc currency` ändert für Spieler die persönliche Währung. Die globale Einstellung heißt jetzt `/btc global currency`. Globale Änderungen und Refresh benötigen `bitcoinprice.admin` (standardmäßig OP). Ein Intervallwechsel löst keine Sofortnachricht mehr aus.

**Neu in 2.1:** Weitere Währungen und OP-Steuerung für einzelne Spieler. OPs können alle Funktionen auch bei abweichenden Permission-Zuweisungen verwenden. Der Java-Namespace und die veröffentlichten Projektdateien verwenden neutrale Namen; bestehende Plugin-Daten bleiben kompatibel.

**Neu in 2.1.1:** Die Hilfe ist in übersichtliche Seiten aufgeteilt: `/btc help 1`, `/btc help 2` oder `/btchelp 1`. Die Navigation zeigt, welche Seiten für die eigenen Rechte verfügbar sind.

**Neu in 2.3:** Deutsch und Englisch für Plugin-Texte, optionale automatische Spracherkennung aus Minecraft, kompakte Actionbar-Inhalte und genauere API-Diagnosen mit `/btc status`.

**Neu in 2.2:** Die Actionbar kann dauerhaft sichtbar bleiben oder in einem eigenen Minutenintervall kurz erscheinen. Die dauerhafte Anzeige wird jede Sekunde aus dem Cache erneuert; das erzeugt keine zusätzliche API-Abfrage. Bestehende Actionbar-Einstellungen erhalten automatisch den Modus `continuous`.

## Screenshots

Für die Bildgalerie sind echte Spielaufnahmen vorgesehen: **Kurstafel**, **Actionbar** und **Chat-Kurs**, optional eine Seite der Hilfe. Die [Screenshot-Anleitung](docs/screenshots.md) enthält die passenden Befehle und Bildausschnitte. Sobald echte Aufnahmen vorliegen, ergänzen sie die Illustration oben.

## Befehle für Spieler

| Befehl | Funktion |
| --- | --- |
| `/btc`, `/btc price` | Kurs, 24-Stunden-Veränderung und Datenalter |
| `/btceur`, `/btcusd` | Kurs in einer bestimmten Währung |
| `/btc help [Seite]`, `/btchelp [Seite]` | Gemeinsame Hilfe mit Seitennavigation; ohne Zahl beginnt sie auf Seite 1 |
| `/btc settings` | Persönliche und wirksame Einstellungen sowie aktive/inaktive automatische Anzeige |
| `/btc status` | Cache, Anbieter-Datenalter, Wartezeit bis zur nächsten Abfrage und laufende Abfrage ansehen; ohne HTTP-Anfrage |
| `/btc currency` | Wirksame persönliche Währung ansehen |
| `/btc currency <CODE>` | Persönliche Währung; DEFAULT übernimmt die globale Auswahl, BOTH zeigt EUR und USD |
| `/btc language de\|en\|AUTO\|DEFAULT` | Persönliche Textsprache: Deutsch, Englisch oder automatisch aus Minecraft |
| `/btc locale <Sprachcode>\|DEFAULT` | Persönliches Zahlenformat, z. B. de-DE oder en-US; unabhängig von der Textsprache |
| `/btc display chat\|actionbar\|off` | Persönliche Anzeigeform |
| `/btc display actionbar content price\|change\|full` | Inhalt wählen: nur Preis, Preis + Tagesänderung oder vollständige Anzeige |
| `/btc display actionbar continuous` | Actionbar dauerhaft anzeigen |
| `/btc display actionbar interval [1\|5\|10\|30\|60\|DEFAULT]` | Actionbar kurz im eigenen Intervall anzeigen; DEFAULT übernimmt das globale Intervall |
| `/btc on`, `/btc off` | Eigene regelmäßige Chat-/Actionbar-Anzeige aktivieren/deaktivieren |
| `/btc interval` | Globales Chat-Intervall und Standard für Actionbar-Intervalle ansehen |
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

Die Textsprache und das Zahlenformat lassen sich getrennt einstellen. `/btc language en` übersetzt Befehlsantworten, Hilfe, Chat, Actionbar und Preisalarme ins Englische, einschließlich „aktuell“ → „current“ und „veraltet“ → „stale“. `/btc locale en-US` stellt zusätzlich Zahlen wie `80.000,50` auf `80,000.50` um. `DEFAULT` übernimmt jeweils die globale Einstellung. Ohne neue Auswahl bleiben bestehende Spielerprofile bei der globalen Textsprache, standardmäßig Deutsch. Mit `/btc language AUTO` folgt die Textsprache deiner Minecraft-Sprache, auch nach einem Wechsel im Client. Unterstützt werden Deutsch und Englisch; andere Client-Sprachen verwenden die feste Server-Standardsprache. Eine persönliche Auswahl `de` oder `en` hat immer Vorrang. Als OP aktiviert `/btc global language AUTO` die Erkennung zusätzlich für Spieler mit `DEFAULT`. `/btc global language de` oder `en` deaktiviert diese globale Erkennung und setzt die feste Standardsprache. Das Zahlenformat bleibt davon unabhängig. Kurstafeln und die Konsole verwenden immer die globale Sprache; persönliche Einstellungen werden gespeichert.

Währungscodes: `EUR`, `USD`, `GBP`, `CHF`, `CAD`, `AUD`, `JPY`, `CNY`, `INR`. `BOTH` zeigt EUR und USD; `DEFAULT` übernimmt die globale Auswahl. Preisalarme und `sats` akzeptieren jeweils einen einzelnen Währungscode. Beispiel: `/btc currency CHF`, `/btc sats 10 GBP` oder `/btc alert above 90000 CAD`. Das virtuelle Portfolio bleibt in EUR geführt.

`/btc display actionbar` ohne weitere Argumente behält den gespeicherten Actionbar-Modus bei; bei neuen und älteren Spielerprofilen ist das `continuous`. `/btc display actionbar interval` ohne Minuten behält das bisher gespeicherte persönliche Intervall bei; ohne persönliche Auswahl gilt das globale Intervall. Minuten können nur zusammen mit `interval` angegeben werden. Bei der ersten aktiven Anzeige im Intervallmodus erscheint die Zeile sofort, anschließend im gewählten Abstand kurz mit der normalen Minecraft-Ausblendung. Eine eigene Anzeigedauer wird nicht konfiguriert. Die Anzeige folgt dem persönlichen Intervall unabhängig von API-Aktualisierungen oder manuellen Refreshes. `DEFAULT` verwendet das globale Intervall, anfangs zehn Minuten. Ein ausdrücklich gewähltes persönliches Intervall bleibt bei globalen Änderungen erhalten.

Die Actionbar kann unabhängig von ihrem Zeitmodus verkürzt werden: `/btc display actionbar content price` zeigt nur den Preis, `change` zusätzlich die Tagesänderung und `full` die bisherige vollständige Zeile. Bei veralteten Kursen oder fehlendem Anbieter-Zeitstempel zeigen auch die kompakten Varianten einen Statushinweis. Inhalt und Intervall werden getrennt gespeichert; bestehende Profile erhalten `full`. Ein Inhaltswechsel wählt die Anzeigeform Actionbar, schaltet `/btc off` aber nicht ein. Zur Aktivierung gegebenenfalls `/btc on` verwenden.

`/btc settings` zeigt bei `DEFAULT` auch die tatsächlich verwendete Währung und Sprache sowie den wirksamen Zustand der automatischen Anzeige. Bleibt eine Anzeige wegen `/btc off`, der Anzeigeform `off` oder global deaktiviertem Chat aus, nennt die Bestätigung den passenden Befehl zum Aktivieren. `/btc status` hilft bei der Kursdiagnose: Es liest den vorhandenen Cache, Anbieter-Datenalter, API-Wartezeit und den Zustand einer laufenden Abfrage, ohne selbst einen Kurs abzurufen. Es unterscheidet Ratenlimits (HTTP 429), Timeouts, Verbindungs- und HTTP-Fehler, ungültige Antworten und veraltete Anbieterdaten. Cache-Alter, fehlende Zeitstempel, die API-Wartezeit und die separate Sperre für manuellen Refresh werden erklärt. Nach einer erfolgreichen Abfrage werden alte Fehler zurückgesetzt; angezeigte Wartezeiten erlauben einen späteren Abruf und starten ihn nicht automatisch. Die Kennzeichnung als aktuell oder veraltet wird beim Anzeigen erneut anhand des Datenalters geprüft.

Das Portfolio verwendet ausschließlich **Spielgeld**. Es verbindet keine Wallet, führt keine echten Transaktionen aus und setzt vorhandenes Guthaben bei erneutem `start` nicht zurück. Käufe werden auf ganze Satoshis, Verkäufe auf Euro-Cents abgerundet. Mit veralteten oder zeitlich nicht überprüfbaren Kursen wird nicht gehandelt.

Preisalarm und Actionbar sind optional. Ein Alarm beginnt mit einer Baseline beim ersten frischen Kurs und löst erst bei einer späteren Grenzüberschreitung aus. Wiederholungen werden durch Hysterese und eine Wartezeit begrenzt; maximal zehn Alarme pro Spieler. Bei minutenweisen Abfragen können kurze Kursbewegungen zwischen zwei Abfragen unbemerkt bleiben. Persönliches `/btc off` deaktiviert regelmäßige Chat-/Actionbar-Anzeigen. Es ändert keine Währung oder vorhandenen Alarme; ausdrücklich angelegte Alarme werden mit `alert remove` entfernt.

## Befehle für Administratoren

| Befehl | Funktion |
| --- | --- |
| `/btc interval 1\|5\|10\|30\|60` | Globales Chat-Intervall und Standard für Actionbar-Intervalle ändern |
| `/btc global currency <CODE>\|BOTH` | Globale Standardwährung ändern |
| `/btc global language de\|en\|AUTO` | Standardsprache oder automatische Erkennung wählen; Kurstafeln/Konsole nutzen die feste Fallback-Sprache |
| `/btc refresh` | Frischen Kurs abrufen und an empfangsberechtigte Spieler senden; ein veralteter Ersatzkurs zählt nicht als erfolgreiche Aktualisierung |
| `/btc on all`, `/btc off all` | Globale regelmäßige Chat-Nachrichten ein-/ausschalten |
| `/btc board create <name>` | Benannte Kurstafel als TextDisplay am eigenen Standort erstellen |
| `/btc board list` | Kurstafeln ansehen |
| `/btc board remove <name>` | Eine eigene Plugin-Kurstafel entfernen |
| `/btc player <Name\|UUID> on` | Intervall-Benachrichtigungen eines Spielers aktivieren |
| `/btc player <Name\|UUID> off` | Intervall-Benachrichtigungen eines Spielers deaktivieren |
| `/btc player <Name\|UUID> currency <CODE>\|BOTH\|DEFAULT` | Persönliche Währung eines Spielers ändern |
| `/btc player <Name\|UUID> settings` | Gespeicherte und wirksame Einstellungen sowie aktive/inaktive automatische Anzeige eines Spielers ansehen |
| `/btc player <Name\|UUID> display chat\|actionbar\|off` | Anzeigeform eines Spielers wählen |
| `/btc player <Name\|UUID> display actionbar content price\|change\|full` | Actionbar-Inhalt eines Spielers wählen, auch offline |
| `/btc player <Name\|UUID> display actionbar continuous` | Dauerhafte Actionbar für einen Spieler wählen |
| `/btc player <Name\|UUID> display actionbar interval [1\|5\|10\|30\|60\|DEFAULT]` | Persönliches Actionbar-Intervall eines Spielers wählen |
| `/btc player <Name\|UUID> locale <Sprachcode>\|DEFAULT` | Zahlenformat eines Spielers wählen |
| `/btc player <Name\|UUID> language de\|en\|AUTO\|DEFAULT` | Textsprache eines Spielers wählen, auch offline |

Globale Chat-Deaktivierung lässt persönliche, ausdrücklich aktivierte Actionbars und Alarme bestehen. Individuelle Präferenzen werden dadurch nicht überschrieben. Kurstafeln laden keine Chunks dauerhaft nach und werden nach einem Neustart wiedererkannt. Es werden ausschließlich vom Plugin markierte Anzeigen verwaltet.

Die Spielersteuerung steht OPs, der Konsole und Benutzern mit `bitcoinprice.admin` zur Verfügung. Für offline gespeicherte Spieler kann die UUID verwendet werden. Namen werden nur gegen tatsächlich bekannte Spieler aufgelöst; unbekannte Namen erzeugen kein neues Profil. Die Änderungen werden gespeichert und greifen für laufende Anzeigen ohne Neustart. `on`/`off` ändern nur die Benachrichtigungseinstellung; Alarme, Währung und Guthaben bleiben erhalten. Wie beim eigenen Profil behält `display actionbar` ohne Modus den gespeicherten Modus des Zielspielers bei.

## Konfiguration

Die kommentierte [config.yml](src/main/resources/config.yml) enthält alle Optionen. Wesentliche Standardwerte:

- Chat-Intervall und Standard für Actionbar-Intervalle 10 Minuten, Standardwährung EUR, Textsprache `de`, Zahlenformat `de-DE`.
- Gemeinsamer Cache 60 Sekunden. Gleichzeitig laufende Anfragen werden zusammengefasst.
- API-Timeout 10 Sekunden; globaler Refresh hat 30 Sekunden Wartezeit.
- Bei API-Fehlern höchstens fünf Minuten alte gespeicherte Daten, ausdrücklich als veraltet gekennzeichnet. Alarme und virtuelle Trades verwenden solche Daten nicht.
- Anbieterzeitstempel werden getrennt vom lokalen Abrufzeitpunkt angezeigt. Fehlende Zeitstempel gelten als unbekannt.
- Überwachung aktiver Anzeigen/Alarme/Kurstafeln alle 60 Sekunden. Dauerhafte Actionbars werden jede Sekunde aus dem Cache erneuert; Actionbars im Intervallmodus folgen dem persönlichen oder globalen Minutenintervall. Die UI-Anzeige erzeugt keine zusätzlichen HTTP-Anfragen.
- Lokale Kursgeschichte höchstens sieben Tage. Gesammelt wird ab Installation während tatsächlicher Abfragen; keine rückwirkende historische Datenabfrage.

Bei leerem Server ohne geladene Kurstafeln erfolgen keine Hintergrundabfragen. Ein gespeicherter Alarm eines offline befindlichen Spielers hält die Abfrage nicht aktiv.

Der frühere Schlüssel `actionbar-seconds` wird ab 2.2 ignoriert und darf aus bestehenden Konfigurationen entfernt werden. Actionbar-Modus und persönliches Minutenintervall stehen in den Spielerpräferenzen. Intern bedeutet ein persönliches Intervall von `0`, dass das globale `price-interval` verwendet wird; im Befehl heißt diese Auswahl `DEFAULT`.

Optional kann ein CoinGecko-Demo-Key mit `api.demo-api-key` oder über `COINGECKO_DEMO_API_KEY` gesetzt werden. Er wird als Header versendet und nicht protokolliert. Keine Zugangsdaten im Repository speichern. HTTP 429 führt zu einer Wartephase; `Retry-After` wird berücksichtigt. Kein sofortiger Wiederholungsversuch durch Befehle.

Die globale Textsprache steht in `language: de` oder `language: en` und kann als OP mit `/btc global language en` ohne Neustart geändert werden. Die optionale globale automatische Erkennung steht in `language-auto-detect: false` und wird durch `/btc global language AUTO` aktiviert; `language` bleibt die Fallback-Sprache für andere Client-Sprachen, Konsole und Kurstafeln. Das Zahlenformat steht weiterhin getrennt in `locale`.

Die mitgelieferten Nachrichtenvorlagen wechseln mit der Sprache. Bereits angepasste `messages.<key>`-Vorlagen bleiben wörtlich erhalten. Für eigene zweisprachige Vorlagen können `messages.de.<key>` und `messages.en.<key>` gesetzt werden (`price`, `actionbar`, `actionbar-price`, `actionbar-change`, `alert`, `board`, `api-error`); sie haben Vorrang vor der gemeinsamen Vorlage. Dynamische Platzhalter wie `{status}`, `{warning}` und `{direction}` folgen weiterhin der ausgewählten Textsprache. `{warning}` bleibt bei frischen Kursen leer und zeigt sonst den Status mit Trennzeichen; die kompakten Standardvorlagen verwenden diesen Platzhalter.

Preis-, Actionbar-, Alarm- und Board-Nachrichten sind in `messages` mit `&`-Farben konfigurierbar. Platzhalter: `{price}`, `{currency}`, `{eur}`, `{usd}`, `{change}`, `{age}`, `{provider_age}`, `{status}`; bei Alarmen zusätzlich `{threshold}` und `{direction}`. Für die gewählte Währung auf Kurstafeln `{price} {currency}` verwenden. Alte Vorlagen mit `{eur}` und `{usd}` bleiben nutzbar und zeigen ausdrücklich diese Währungen. Das Plugin nutzt Adventure-Komponenten.

## Gespeicherte Daten

Im Ordner `plugins/BitcoinPrice`:

- `config.yml`: globale Einstellungen.
- `players.json`: UUID-bezogene Präferenzen einschließlich Actionbar-Modus und persönlichem Intervall, Alarme und virtuelles Portfolio.
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

[CoinGecko einfache Preise](https://docs.coingecko.com/demo/reference/simple-price), [CoinGecko unterstützte Währungen](https://docs.coingecko.com/reference/simple-supported-currencies), [CoinGecko Rate Limits](https://docs.coingecko.com/docs/errors-and-rate-limits), [Paper Scheduler](https://docs.papermc.io/paper/dev/scheduler/).

MIT-Lizenz; siehe [LICENSE](LICENSE). Gepflegt von den BitcoinPrice contributors.
