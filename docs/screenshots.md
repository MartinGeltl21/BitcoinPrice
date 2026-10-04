# Screenshots für die README

Drei echte Aufnahmen reichen: **Kurstafel**, **Actionbar** und **Chat-Kurs**. Eine vierte Aufnahme der neuen Hilfe ist optional. Die README enthält bis dahin eine eigene Vektorillustration, die ausdrücklich als Illustration gekennzeichnet ist.

## Vorbereitung

- Fenster oder Vollbild möglichst **1920 × 1080**, Bilder mit **F2 als PNG** aufnehmen. Die Originaldateien behalten; nach Bedarf kann für die README zusätzlich zugeschnitten werden.
- Eine ruhige Stelle bei Tageslicht wählen, etwa am Spawn mit etwas Landschaft im Hintergrund. Die normale Minecraft-Darstellung eignet sich am besten; keine Shader oder Ressourcenpakete nötig.
- Keine Server-IP, privaten Spielernamen, UUIDs, Login-Meldungen, Koordinaten mit privatem Bezug oder sonstige vertrauliche Angaben zeigen. Tab-Liste und Debug-Anzeige schließen; alte Chatmeldungen möglichst ausblenden. Sensible Bildbereiche vor dem Hochladen unkenntlich machen.
- Als OP die folgenden Befehle im Spiel ausführen. Globale Änderungen wirken auch für andere Spieler; eine zuvor verwendete globale Währung anschließend wiederherstellen.
- Reale Kurse dürfen in den Bildern stehen. Es ist kein bestimmter Bitcoin-Preis erforderlich, und für diese Aufnahmen müssen keine Alarme ausgelöst werden.

## 1. Kurstafel — `board.png`

```text
/btc global currency EUR
/btc board create showcase
/btc
```

Am gewünschten Ort stehen und dort die Tafel erstellen. Sie erscheint ungefähr zwei Blöcke oberhalb des Standorts. Anschließend vier bis sechs Blöcke zurückgehen und leicht nach oben auf die Tafel schauen. Warten, bis der Kurs sichtbar ist. Wenn die API gerade nicht erreichbar ist, später erneut versuchen.

Die Tafel mit etwas Landschaft aufnehmen. **F1** kann für diese Aufnahme die übrige Benutzeroberfläche ausblenden; danach mit F1 wieder einschalten. Der Text sollte auch im verkleinerten Bild lesbar bleiben. Der Name `showcase` darf noch nicht vergeben sein; zum Entfernen einer ausschließlich für diese Aufnahme erstellten Tafel `/btc board remove showcase` verwenden.

## 2. Actionbar — `actionbar.png`

```text
/btc currency EUR
/btc display actionbar continuous
/btc on
/btc
```

Den Chat schließen, kurz warten und aufnehmen, sobald die Kurszeile **oberhalb der Schnellzugriffsleiste** erscheint. Im Modus `continuous` wird sie jede Sekunde aus dem Cache erneuert und bleibt sichtbar. Hier **F1 nicht verwenden**, da die Actionbar zur Benutzeroberfläche gehört. Ein ruhiger Hintergrund erleichtert das Lesen.

Für eine zusätzliche Aufnahme des Intervallmodus `/btc display actionbar interval 1` wählen und die nächste automatische Anzeige abwarten. Sie erscheint einmal pro Minute kurz und blendet anschließend aus; manuelle Kursabfragen lösen keine zusätzliche Intervallanzeige aus.

## 3. Chat-Kurs — `chat.png`

```text
/btc display chat
/btc currency BOTH
/btc
```

Die Kursantwort mit EUR und USD, Tagesänderung und Datenalter aufnehmen. Für eine Aufnahme mit einer weiteren Währung stattdessen `/btc currency GBP` und anschließend `/btc` verwenden. Den Chat gegebenenfalls öffnen, damit die Zeile gut lesbar ist. Möglichst wenige ältere Meldungen im Ausschnitt lassen.

## 4. Hilfe, optional — `help.png`

```text
/btc help 1
```

Alternativ `/btchelp 1`; für eine andere Seite `/btc help 2`. Eine einzelne Seite einschließlich Seitennavigation aufnehmen. Die Anzahl der Seiten richtet sich nach den eigenen Rechten. Keine vollständige Befehlsliste in ein einziges Bild pressen.

## Danach

Die eigenen vorherigen Einstellungen mit `/btc currency <vorheriger CODE>`, `/btc display <vorheriger Modus>` und `/btc on` beziehungsweise `/btc off` wiederherstellen. Bei einer vorherigen Actionbar-Auswahl auch `continuous` beziehungsweise `interval` und das persönliche Minutenintervall oder `DEFAULT` wiederherstellen. Eine nur zum Fotografieren erstellte Tafel bei Bedarf entfernen.

Die Bilder können im Chat hochgeladen werden. Vorgesehene Repository-Dateien sind `docs/assets/board.png`, `docs/assets/actionbar.png`, `docs/assets/chat.png` und optional `docs/assets/help.png`. Diese Dateien werden erst nach Erhalt und Prüfung echter Bilder in die README eingebunden. Bis dahin gibt es keine leeren Bildverweise.
