# Installation auf einem bestehenden Docker-Compose-/Coolify-Server

BitcoinPrice 2.1.1 ist für Paper **26.2** mit **Java 25** gebaut, gegen
Paper-API Build 123. Der Minecraft-Client benötigt kein Plugin.

## 1. JAR besorgen

Auf GitHub unter **Actions → Build → erfolgreicher Lauf → Artifacts** das
Archiv `BitcoinPrice-2.1.1` herunterladen und entpacken. Alternativ mit
JDK 25 und Maven `mvn clean verify` ausführen; die fertige Datei heißt
`target/BitcoinPrice-2.1.1.jar`. Nicht `original-*.jar` installieren.

Die fertige JAR enthält die JSON-Bibliothek; zusätzliche Plugins sind nicht nötig.

## Update bei vorhandenem PLUGINS-Download

Wenn bereits `itzg/minecraft-server:java25`, `TYPE: PAPER`, `VERSION: "26.2"`
und ein persistentes `/data`-Volume verwendet werden, benötigen die neuen
Plugin-Funktionen keine zusätzlichen Ports, Volumes oder Datenbankdienste.

Zeigt `PLUGINS` noch auf eine ältere JAR, muss deren URL durch die öffentliche
Download-URL der neuen Version ersetzt werden. Ein Beispiel mit Platzhalter:

```yaml
environment:
  TYPE: PAPER
  VERSION: "26.2"
  PLUGINS: 'https://example.com/BitcoinPrice-2.1.1.jar' # echte veröffentlichte JAR-URL einsetzen
```

Danach in Coolify redeployen. Andere Plugin-URLs in der bestehenden Liste
beibehalten. Das Image verwaltet entfernte Download-Einträge automatisch;
manuell kopierte alte BitcoinPrice-JARs vor dem Update gezielt sichern und
aus dem Plugin-Verzeichnis nehmen. Plugin-Datenordner und Welt beibehalten.

GitHub-Actions-Artefakte sind ZIP-Archive und keine direkt nutzbare öffentliche
JAR-URL für `PLUGINS`. Ein neuer Branch oder Pull Request veröffentlicht noch
keinen Release-Download. Die Release-URL erst verwenden, wenn die JAR dort
tatsächlich veröffentlicht wurde. Bis dahin lässt sich die gebaute JAR manuell
installieren, wie unten beschrieben.

## 2. Vorhandenen Container und Datenspeicher identifizieren

Auf dem VPS:

```bash
docker ps --format 'table {{.Names}}\t{{.Image}}'
MC='DEIN_MINECRAFT_CONTAINERNAME'
docker inspect "$MC" --format '{{range .Mounts}}{{println .Type .Source "->" .Destination}}{{end}}'
docker exec "$MC" java -version
docker logs --tail 100 "$MC"
```

Den tatsächlichen Namen einsetzen. `/data` muss als persistentes Volume oder
Bind Mount eingebunden sein. Logs müssen Paper 26.2 bestätigen. Die vorhandene
Welt und das vorhandene `/data`-Volume beibehalten.

## 3. Empfohlen für itzg/minecraft-server: Plugin-Verzeichnis einbinden

Lokal die JAR auf den VPS kopieren (SSH-Benutzer und Host anpassen):

```powershell
scp .\BitcoinPrice-2.1.1.jar root@vps.example.com:/tmp/BitcoinPrice-2.1.1.jar
```

Auf dem VPS:

```bash
sudo install -d -m 755 /srv/minecraft/plugins
sudo install -m 644 /tmp/BitcoinPrice-2.1.1.jar /srv/minecraft/plugins/BitcoinPrice.jar
```

In **der bestehenden** Compose-Konfiguration beim Minecraft-Service ergänzen:

```yaml
services:
  minecraft: # vorhandenen Service-Namen verwenden
    image: itzg/minecraft-server:java25
    environment:
      TYPE: PAPER
      VERSION: "26.2"
      # alle vorhandenen Einstellungen behalten
    volumes:
      # bestehenden /data-Eintrag unverändert behalten
      - /srv/minecraft/plugins:/plugins:ro
```

Dies ist ein Ausschnitt, keine vollständige Ersatzkonfiguration. Vorhandene
Ports, Whitelist, EULA, Speichergrenzen und `/data`-Mount bleiben bestehen.
Der itzg-Container kopiert die JAR beim Start von `/plugins` nach `/data/plugins`.
In Coolify Compose speichern und **Redeploy** ausführen. Bei selbst verwaltetem
Compose im vorhandenen Projektverzeichnis `docker compose up -d minecraft`
ausführen; den tatsächlichen Service-Namen einsetzen.

Bei einem bestehenden benannten Daten-Volume kann der Mount beispielsweise so
beim Minecraft-Service ergänzt werden:

```yaml
    volumes:
      - 'minecraft-data:/data'
      - '/srv/minecraft/plugins:/plugins:ro'
```

Die bestehende Paper-/Java-Konfiguration und Speichergrenzen beibehalten, sofern
sie Paper 26.2 und Java 25 unterstützen. `VERSION` als String in Anführungszeichen
schreiben. Die vorhandene Volume-Deklaration am Ende der Datei beibehalten. Der
zusätzliche Mount muss auf dem Coolify-Zielserver vorhanden sein, nicht auf dem
lokalen Windows-PC.

Vor dem ersten Start mit dem Update alte BitcoinPrice-JARs in `/data/plugins`
und im Quellverzeichnis in einen Backup-Ordner außerhalb des Plugin-Verzeichnisses
verschieben. Genau eine BitcoinPrice-JAR behalten. Der Ordner
`/data/plugins/BitcoinPrice/` enthält die Konfiguration und bleibt bestehen.

## Alternative: JAR direkt ins bestehende /data-Volume kopieren

Falls das Image kein itzg-Image ist, oder keine Compose-Änderung gewünscht ist:
Die JAR wie oben nach `/tmp` hochladen, dann auf dem VPS ausführen:

```bash
MC='DEIN_MINECRAFT_CONTAINERNAME'
docker exec "$MC" ls -l /data/plugins
docker exec "$MC" mkdir -p /data/plugin-backups
# Vorhandene BitcoinPrice-JARs gezielt nach /data/plugin-backups verschieben.
docker stop --time 60 "$MC"
docker cp /tmp/BitcoinPrice-2.1.1.jar "$MC":/data/plugins/BitcoinPrice.jar
docker start "$MC"
docker logs --since 2m "$MC"
```

Der `/data/plugins`-Ordner muss bereits vorhanden und `/data` persistent sein.
Gegebenenfalls Datei-Eigentümer an UID/GID des Minecraft-Prozesses anpassen.
Bei einem anderen Datenpfad den tatsächlichen Plugin-Pfad verwenden. Wenn bereits
`PLUGINS` oder ein `/plugins`-Mount konfiguriert ist, dessen Quelle aktualisieren,
damit beim nächsten Start keine alte JAR zurückkopiert wird.

## 4. Prüfen und konfigurieren

- Logs: `BitcoinPrice 2.1.1 wurde erfolgreich aktiviert!`, keine Ladefehler.
- In der Serverkonsole: `plugins`, `version BitcoinPrice`, `btc`, `btceur`, `btcusd`.
- Im Spiel: `/btc`, `/btc help`, `/btc currency BOTH` (persönlich), `/btc global currency BOTH` (Admin), `/btc off`, `/btc on`.
- Als OP: `/btc off all` und `/btc on all` prüfen.
- Als OP: `/btc player <Name|UUID> off`, `/btc player <Name|UUID> on`, `/btc player <Name|UUID> currency CHF` und `/btc player <Name|UUID> settings` prüfen.
- Unterstützte Währungen: EUR, USD, GBP, CHF, CAD, AUD, JPY, CNY und INR; BOTH zeigt EUR und USD. Das virtuelle Portfolio bleibt EUR-basiert.
- Konfiguration: `/data/plugins/BitcoinPrice/config.yml`, standardmäßig EUR/10 Minuten.
- Für manuelle Konfigurationsänderungen den Server vollständig neu starten.

HTTP 429 bedeutet CoinGecko-Ratenbegrenzung; später erneut versuchen.
HTTPS-Zugriff und DNS aus dem Container müssen funktionieren. Kein zusätzlicher
eingehender Port ist erforderlich. Kein `/reload` und keinen Plugin-Hotloader verwenden.

## Rückkehr zur vorherigen Plugin-Version

Server stoppen, neue JAR außerhalb des Plugin-Verzeichnisses sichern und genau
die vorherige JAR wiederherstellen, ebenso eine mögliche `/plugins`-Quelle.
Server starten und Logs prüfen. Die vorherige Version muss separat mit Paper 26.2
kompatibel sein. Nicht die Minecraft-Welt oder das `/data`-Volume ersetzen.

## Quellen

- [Paper-Projektsetup](https://docs.papermc.io/paper/dev/project-setup/)
- [Paper und Java 25](https://docs.papermc.io/paper/getting-started/)
- [itzg Plugin-Mounts](https://docker-minecraft-server.readthedocs.io/en/latest/mods-and-plugins/)
