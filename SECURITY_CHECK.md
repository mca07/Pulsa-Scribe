# Prüfbericht: Pulsa Scribe (Android)

| | |
|---|---|
| **Projekt** | Pulsa Scribe – transkribiert Audio- und Videodateien sowie live aufgenommene Gespräche mit Google Gemini: Sprecher, Zeitstempel, Zusammenfassung (Kernpunkte, Aufgaben, Entscheidungen, offene Fragen), Suche, Wiedergabe mit Sprung zur Stelle, Export als PDF, Word, Text und SRT; Designs Pulsa und Academy |
| **Art** | Android (Java, native Oberfläche, keine WebView, minSdk 29 / targetSdk 35) |
| **Version / Stand** | 1.5, 17.10.2026 |
| **Geprüft von** | Claude (Erstellung), Abnahme: Mario |

**Legende:** ✅ erfüllt · 🔧 bei dieser Prüfung behoben · ⚠️ offen oder bewusst akzeptiert (mit Begründung) · – nicht relevant

---

## 1. Bedrohungsmodell
- **Schützenswert:** Gesprächsinhalte (Ton, Text, Zusammenfassung – oft vertraulich, z. B. Kundentermine), Gemini-API-Schlüssel (Missbrauch verbraucht Kontingent), Signierschlüssel der App, Einverständnis der Aufgenommenen.
- **Mögliche Angreifer:** fremde Apps auf dem Gerät (über „Teilen“ oder den Export-Provider), Gerätediebstahl, Backups/Gerätetransfer, Netzwerk (öffentliches WLAN), manipulierte oder fehlerhafte Antworten von Gemini, gesprochene Anweisungen in der Aufnahme (Prompt-Injection), manipulierte Dateien (kaputte Container, riesige Dauer), Zugriff auf das GitHub-Repository.
- **Eingänge der Anwendung:** UI (Titel, Begriffe, Sprecher- und Textänderungen, Suche, Schlüssel), Datei-Auswahl (SAF), Teilen-Intent (`audio/*`, `video/*`), Mikrofon, Antworten von Gemini, gespeicherte JSON-Dateien, Einstellungen, Benachrichtigungs-Aktionen.
- **Schlimmster Fall:** Gesprächsinhalte oder Schlüssel gelangen an Dritte, oder eine heimliche Aufnahme. Abgesichert: Schlüssel nur verschlüsselt im Keystore, nur https zum festen Google-Host ohne Weiterleitungen, kein Backup, Export nur mit befristeter Freigabe, Einverständnis-Bestätigung vor jeder Aufnahme, sichtbare Benachrichtigung während der Aufnahme. Prompt-Injection kann höchstens den eigenen Text verfälschen; die Antwort ist auf ein festes Format mit Grenzen beschränkt und wird nur als Text angezeigt.

## 2. Zugangsdaten und Geheimnisse
- ✅ Keine Passwörter, Keys oder Tokens im Code; `.gitignore` sperrt `*.p12`, `*.jks`, `*.keystore`, `*KEYSTORE*.txt`
- ✅ Gemini-Schlüssel (Formate „AIza…“ und „AQ.…“ geprüft): AES-256-GCM, AES-Schlüssel im Android Keystore (`pulsa_scribe_gemini_v1`, nicht exportierbar), nur Chiffrat in privaten Einstellungen
- ✅ Eingabe nativ im Dialog mit `FLAG_SECURE` (kein Screenshot), Passwortfeld, Autofill aus
- ✅ Schlüssel nur im Header `x-goog-api-key`, nie in URL, Log, Fehlermeldung oder Diagnose; Release entfernt `Log.v/d/i`
- ✅ Kein Backup, kein Gerätetransfer (`allowBackup=false`, `dataExtractionRules` schließen alles aus)
- ✅ Geringste Rechte: eigener, widerrufbarer API-Schlüssel; eigener Signierschlüssel nur für diese App; Workflow nur `contents: write`
- ✅ Beschädigter Keystore: Schlüssel gilt als nicht vorhanden, kein Absturz

## 3. Netzwerk
- ✅ Nur HTTPS; Network Security Config verbietet Klartext und vertraut nur System-Zertifikaten (keine Nutzer-CAs)
- ✅ Zertifikatsprüfung nie abgeschaltet, keine eigenen TrustManager
- ⚠️ Kein Certificate Pinning (siehe Offene Punkte)
- ✅ Nur ein Ziel: `generativelanguage.googleapis.com`; Host und Protokoll werden vor jedem Aufruf geprüft, Modellname per Muster; keine Weiterleitungen (`setInstanceFollowRedirects(false)`)
- ✅ Timeouts 15 s Verbindung / 300 s Antwort (lange Teile), Antwort max. 6 MB, Audio je Anfrage max. 18 MB (tatsächlich ca. 5–7 MB je 15-Minuten-Teil)
- ✅ Audio wird direkt aus der Datei als Base64 gestreamt (feste Länge, kein großer Puffer im Speicher)
- ✅ Antworten werden streng geprüft (siehe 4); Text in „thought“-Teilen wird ignoriert

## 4. Eingaben prüfen, Ausgaben kodieren
- ✅ Positivlisten: Titel 1–80 Zeichen, Begriffe max. 300 Zeichen (Anführungszeichen, Klammern, `<>{}[]` werden vor dem Prompt entfernt), Sprecher max. 40, Abschnitt max. 4.000 Zeichen, Sprache und Zusammenfassungssprache nur aus fester Liste, IDs `[a-z0-9]{8,32}`
- ✅ Gemini-Antwort: festes JSON-Schema (`responseSchema`), eigener strenger JSON-Leser (Tiefe max. 32), Zeiten nur `MM:SS`/`H:MM:SS` innerhalb des Teils (+10 s), ungültige Zeiten werden zwischen Nachbarn ergänzt, Rückwärtssprünge geglättet, endlose Wiederholungen derselben Zeile zusammengefasst, max. 20.000 Abschnitte
- ✅ Abgeschnittene Antwort (`MAX_TOKENS`): vollständige Einträge werden gerettet; bei Teilen über 2 Minuten wird der Teil halbiert und neu angefragt
- ✅ Zusammenfassung: feste Felder, Listen und Längen begrenzt (z. B. 40 Punkte, 40 Aufgaben, 600 Zeichen je Punkt)
- ✅ Fremde Texte (Antworten, Dateinamen, gespeicherte Daten) bereinigt: Steuer- und Bidi-Zeichen entfernt, nur vollständige Unicode-Paare, gekürzt; Anzeige nur per `setText` in nativen Views, nie als HTML
- ✅ Word-Export: XML-kodiert, in XML verbotene Zeichen entfernt; Dateinamen nur aus Buchstaben, Ziffern, Leer, `-_()`, max. 60 Zeichen (keine Pfade)
- ✅ Gespeicherte Transkripte werden beim Laden vollständig geprüft (`Transcript.fromJson`): Status per Positivliste, Teilgrenzen streng aufsteigend, Zahlenbereiche, Längen; ungültige Dateien werden ignoriert, nicht gelöscht
- ✅ Teilen-Intent und Datei-Auswahl: nur `content://`, MIME nur `audio/*`, `video/*` (bzw. unbestimmt), Auswertung nur einmal; gespeicherte Quell-Adresse nur `content://` ohne Steuer-/Leerzeichen
- ✅ Medien: MediaExtractor/MediaCodec des Systems; Dauer max. 10 Stunden, PCM-Formate nur 16 Bit oder Float, Raten 4–384 kHz, Kanäle 1–32; ADTS-Rahmen max. 8.191 Byte, Index max. 4 Mio. Rahmen, kaputte Daten führen zum Abbruch statt zu Raten
- ✅ Kein `eval`, keine WebView, kein dynamisches Laden von Code
- – SQL/Shell: nicht vorhanden

## 5a. Plattform: Mobile-App
- ✅ Exportiert nur `MainActivity` (Launcher, Teilen von Audio/Video); `TranscriptActivity`, `RecordActivity`, `SettingsActivity`, beide Dienste und der Export-Provider sind nicht exportiert
- ✅ `ExportProvider` (eigener, statt FileProvider-Bibliothek): nur lesend, nur Dateien in `cache/export/<16 Zeichen>/<Name>.(txt|docx|pdf|srt)`, Pfad per Muster geprüft, Freigabe nur befristet über `FLAG_GRANT_READ_URI_PERMISSION`; alte Exporte werden nach 1 Stunde gelöscht
- ✅ Exportiert zusätzlich nur: `RecordTile` (nur das System darf binden: `BIND_QUICK_SETTINGS_TILE`) und `QuickWidget` (nur `APPWIDGET_UPDATE`, liest keine Intent-Daten)
- ✅ Berechtigungen: `INTERNET`, `POST_NOTIFICATIONS` (erst beim ersten Start), `RECORD_AUDIO` (erst bei der ersten Aufnahme), `FOREGROUND_SERVICE` + `_DATA_SYNC` + `_MICROPHONE`, `WAKE_LOCK`. **Keine Speicher-Berechtigung** (Datei-Auswahl und Speichern über SAF)
- ✅ PendingIntents explizit und `FLAG_IMMUTABLE`; „Beenden“ in der Aufnahme-Benachrichtigung öffnet die Aufnahme-Ansicht (Start der Transkription immer aus einer sichtbaren Ansicht)
- ✅ Aufnahme: Bestätigung „Alle Beteiligten sind einverstanden“ vor jedem Start, Hinweis auf § 201 StGB, dauerhaft sichtbare Benachrichtigung mit Pause/Beenden, Mikrofon nur während der Aufnahme
- – WebView: nicht verwendet
- ✅ Keine sensiblen Daten in externem Speicher; Zwischenablage nur auf ausdrücklichen Wunsch („Text kopieren“, „CRM-Eintrag kopieren“); der CRM-Eintrag wird als sensibel markiert (keine Vorschau ab Android 13)
- ⚠️ Kein `FLAG_SECURE` für die Transkript-Ansicht (nur für den Schlüssel-Dialog), siehe Offene Punkte
- ✅ Hintergrund: Vordergrund-Dienste mit sichtbarer Benachrichtigung (dataSync bzw. microphone), WakeLock begrenzt (3 h bzw. 6 h + 10 min), Android-15-Zeitlimit (`onTimeout`) behandelt
- ✅ Datei-Freigaben: dauerhafte SAF-Freigabe nur für gewählte Dateien (für erneute Versuche), geteilte Dateien nur befristet an den Dienst weitergereicht

## 5b. Plattform: Web-App / Backend
- – nicht relevant (kein eigener Server)

## 6. Externe Dienste und APIs
- ✅ Gemini `generateContent` mit festem JSON-Schema, Temperatur 0 (Transkript) bzw. 0,2 (Zusammenfassung)
- ✅ Modell-Rückfall in fester Reihenfolge (`gemini-3.8-flash`, `gemini-3.6-flash`, `gemini-3.5-flash-lite`, `gemini-2.5-flash`), beginnend beim zuletzt erfolgreichen
- ✅ Vorübergehende Fehler (404, 408, 429, 5xx, Zeitüberschreitung, unbrauchbare Antwort): nächstes Modell nach 2 s, zweite Runde nach 20 s, danach Abbruch mit Meldung (Statuscode, Google-Status, Modell; nie der Schlüssel)
- ✅ Dauerhafte Fehler (400, 401, 403, Sperre durch Sicherheitsfilter) stoppen sofort mit klarer Meldung
- ✅ Fortsetzen statt Neustart: jeder fertige Teil wird gespeichert, „Erneut versuchen“ macht beim offenen Teil weiter
- ✅ Mehrfachklick: ein Auftrag je Transkript in der Warteschlange, Aufträge laufen nacheinander; eine Aufnahme gleichzeitig
- ✅ Google-Bedingungen: Gratis-Kontingent für private Nutzung; Hinweis in der App, für Kundendaten die bezahlte Variante mit Auftragsverarbeitung zu nutzen

## 7. Robustheit und Bedienung
- ✅ Datei-, Codec-, Netzwerk- und Speicherfehler abgefangen, verständliche Meldung im Eintrag und als Benachrichtigung, kein Absturz
- ✅ App beendet während der Arbeit: beim nächsten Start „Unterbrochen“ mit „Erneut versuchen“; Aufnahme beendet durch Absturz: bisheriger Ton (alle 5 s gesichert) bleibt nutzbar, abgebrochener letzter Rahmen wird ignoriert
- ✅ Gelöscht während der Arbeit: Dienst bricht ab und legt das Transkript nicht wieder an
- ✅ Dienst und Oberfläche ändern nie dasselbe Objekt (Kopie beim Bearbeiten); Bearbeiten während der Transkription gesperrt, Titel jederzeit änderbar
- ✅ Aufnahme stoppt automatisch bei weniger als 20 MB freiem Speicher oder nach 6 Stunden; Start nur mit mind. 60 MB frei
- ✅ Jede Transkription abbrechbar (Liste, Ansicht, Benachrichtigung); Löschen immer mit Rückfrage
- ✅ Sichere Standardwerte: nichts wird gesendet, bevor der Hinweis bestätigt und auf „Transkribieren“ bzw. „Beenden und transkribieren“ getippt wurde

## 8. Abhängigkeiten und Build
- ✅ Einzige fremde Komponente: whisper.cpp/ggml (MIT, Submodul mit festem Commit, aus Quellcode gebaut); sonst keine Bibliotheken (eigener JSON-Leser, eigener Export-Provider, DOCX selbst gebaut, PDF über Android `PdfDocument`); JUnit nur für Tests. Schrift Inter (SIL OFL, `tools/fonts/OFL-Inter.txt`) als Ressource, nicht aus dem Netz
- – Schwachstellen-Scan: keine App-Abhängigkeiten
- ✅ Release: R8 (Minify + Ressourcen-Shrink), `debuggable=false`, Debug-Logs entfernt
- ✅ Eigener Signierschlüssel (RSA 4096, SHA-256 89:BD:78:DF:…:16:C9) nur als GitHub-Secret; Datei wird nach dem Build gelöscht; Signatur wird im Build angezeigt
- ✅ Build-Fehler erscheinen als Hinweise in GitHub (ohne Schlüsselzeilen)
- ⚠️ Gradle-Version fest (8.11.1), aber ohne Wrapper-Prüfsumme

## 9. Tests und Verifikation
- ✅ 69 automatische Tests (ab 1.2: Ordnerbaum inkl. Tiefe, Schleifen, Löschen, manipulierte Daten; neue Transkript-Felder manipuliert; Vorlagen; Schlagwörter; Bildlesen; geteilter Text; Fragen und Belegstellen; Übersetzung mit falscher Zeilenzahl; Ordner-Auswertung mit Budget; Vorlesetexte; Suche; Sicherung mit falschem Passwort, Manipulation, Abschneiden, Zusatzdaten, fremder Datei, Dateinamen; Offline-Modelle, AudioSpecificConfig, Umrechnung, Whisper-Ausgabe), davor 40: JSON (Rundreise, kaputte Eingaben, Tiefe, Steuerzeichen), Textbereinigung (Bidi, Steuerzeichen, halbe Unicode-Paare), Dateinamen (Pfadangriffe), Zeiten (gültig/ungültig/zu groß), ADTS (Kopf, Index, kaputte Daten, Rahmenlimit, abgebrochene Aufnahme), Teilung (kurze Aufnahme, leiseste Stelle, ohne Lautstärke), Transkript laden (Rundreise, manipulierte Datei, falsche ID, nur content://), Gemini (Schlüsselformat, Modellname, Prompt ohne ungeprüfte Zeichen, gültiges Anfrage-JSON, Antwort mit „thought“, Sperre, Fehlerstatus, Zeitprüfung und Ergänzung, ohne Sprecher, Endlosschleife, abgeschnittene Antwort, Zusammenfassung manipuliert, Kontext für den nächsten Teil, Modell-Rückfall), Export (TXT, SRT-Aufteilung, DOCX als gültiges ZIP mit kodiertem XML, Wortgrenzen), Sprach-Positivliste
- ✅ Tests ausgeführt: 69/69 lokal und bei jedem GitHub-Build; GitHub-Builds 1–6 erfolgreich (1.5 = Build 6, native Bibliothek im APK geprüft: JNI-Symbole exportiert, nur liblog/libandroid/libc als Abhängigkeiten)
- ⚠️ Android-Code nur im GitHub-Build kompiliert (keine Android-Stubs in der Arbeitsumgebung); echte Gemini-Antworten in der Arbeitsumgebung nicht abrufbar (gesperrt), Antwortformat nach Dokumentation umgesetzt; Gerätetest steht aus (siehe `GERAETETEST.md`)
- ✅ Code nach dem Schreiben erneut gelesen; dabei behoben 🔧: Rückstau im Encoder (mehrere Blöcke je Runde), Reihenfolge „Aufnahme fertig“ → Transkription, gelöschtes Transkript wurde vom Dienst wieder angelegt, Titeländerung während der Arbeit ging verloren, unsichtbare Unicode-Zeichen im Quelltext durch `\u`-Schreibweise ersetzt

## 9a. Ordner, Schlagwörter, Suche (ab 1.2)
- ✅ Ordnerbaum mit Unterordnern (max. 4 Ebenen, 300 Ordner, Namen 1–40 Zeichen, eindeutig je Ebene); jede Änderung wird auf einer Kopie geprüft und nur bei Erfolg übernommen
- ✅ Verschieben prüft Schleifen und Tiefe; Löschen entfernt nur den Ordner, Inhalt wandert eine Ebene nach oben (nichts geht verloren)
- ✅ Gespeicherter Baum wird beim Laden repariert: unbekannte Eltern, Schleifen, zu tiefe Ordner und doppelte Namen
- ✅ Schlagwörter max. 10 × 30 Zeichen, ohne #, keine Doppelten; Gemini schlägt bis zu 5 vor, nur wenn noch keine gesetzt sind
- ✅ Suche über alle Transkripte läuft lokal (Titel, Schlagwörter, Text, Zusammenfassung, Antworten), Anzeige nur als Text

## 9b. Teilen → Zusammenfassen, Vorlesen, Kachel, Widget (ab 1.2)
- ✅ Teilen-Ziel für `text/plain` und `image/*`; Text max. 100.000 Zeichen, in Absätze geteilt; reine Links werden nicht geöffnet (kein Abruf fremder Webseiten durch die App)
- ✅ Bilder: Größe geprüft (max. 12.000 px je Seite), auf 1.600 px verkleinert, als JPEG im privaten Speicher; Gemini liest den Text (festes JSON-Schema, max. 400 Absätze), Anweisungen im Bild werden ignoriert
- ✅ Vorlesen mit der Android-Sprachausgabe (offline, keine zusätzliche Berechtigung); Texte in Stücken unter der Höchstlänge; Geschwindigkeit aus fester Liste
- ✅ Kein Bedienungshilfe-Dienst zum Mitlesen des Bildschirms (bewusst: hätte Zugriff auf alles, auch Passwörter)
- ✅ Schnelleinstellungs-Kachel (`BIND_QUICK_SETTINGS_TILE`) und Widget öffnen nur App-Ansichten; die Aufnahme startet erst nach Einverständnis-Bestätigung; Widget-Empfänger liest keine Intent-Daten

## 9c. Vorlagen, Fragen, Übersetzen, Ordner-Auswertung (ab 1.3)
- ✅ Vorlagen aus fester Liste (Allgemein, Kundentermin, Kurs-Lektion, Interview, Beitrag); Anweisung fest im Code
- ✅ Fragen: Frage max. 300 Zeichen, bereinigt, als Daten markiert; Antwort max. 3.000 Zeichen, Belegstellen nur innerhalb der Aufnahme (max. 5), Sprung zur Stelle; max. 50 Antworten je Transkript
- ✅ Übersetzen: Zielsprache aus fester Liste, Blöcke zu 120 Zeilen, Antwort muss genau gleich viele Zeilen haben (sonst nichts übernommen); max. 3 Übersetzungen je Transkript; Original bleibt unverändert
- ✅ Ordner-Auswertung: max. 60 Einträge, je Eintrag Budget, gesamt max. 600.000 Zeichen; frühere Auswertungen werden nicht erneut ausgewertet; Ergebnis als eigener Eintrag
- ✅ Aufgaben in Kalender/E-Mail: über System-Intents (`ACTION_INSERT` Kalender, `ACTION_SENDTO mailto:`), **keine Kalender- oder Kontakte-Berechtigung**; Versand erst nach Bestätigung in der jeweiligen App

## 9d. Verschlüsselte Datensicherung (ab 1.4)
- ✅ PBKDF2-HMAC-SHA256 (310.000 Iterationen, 16 Byte Salt) → AES-256-GCM in Blöcken zu 1 MB; Nonce = 8 Byte Zufall + Blocknummer; AAD mit Blocknummer und „letzter Block“ → Vertauschen, Weglassen, Abschneiden und angehängte Daten werden erkannt
- ✅ Passwort mind. 8 Zeichen, zweimal einzugeben, Dialog mit `FLAG_SECURE`, nach Gebrauch überschrieben; nicht gespeichert
- ✅ Gemini-Schlüssel und Einstellungen sind nie enthalten; Ton/Bilder nur auf Wunsch
- ✅ Wiederherstellen: erst vollständig entschlüsseln und prüfen (bis zum letzten Block), dann übernehmen; nur Dateinamen nach Positivliste (`folders.json`, `t/<id>.json|aac|jpg`), Größen je Art begrenzt, max. 30.000 Einträge; jedes Transkript durchläuft `fromJson`; vorhandene Einträge werden nicht überschrieben; alte Datei-Freigaben werden verworfen
- ✅ Datei nur über die Android-Dateiauswahl (SAF), keine Speicher-Berechtigung
- ⚠️ Wer das Passwort vergisst, kann die Sicherung nicht mehr öffnen (bewusst)

## 9e. Offline-Transkription mit Whisper (ab 1.5)
- ✅ whisper.cpp (MIT-Lizenz) als Git-Submodul fest auf v1.9.5 (Commit d1be6fd), nur CPU, gebaut beim GitHub-Build; zwei Varianten (mit/ohne fp16), Auswahl nach `/proc/cpuinfo`; Bibliotheken 16-KB-ausgerichtet
- ✅ Modelle nur aus fester Liste (Base, Small, Large v3 Turbo) von `huggingface.co/ggerganov/whisper.cpp`; Download über den Android-DownloadManager in den App-eigenen Ordner, danach **SHA-256- und Größenprüfung** gegen feste Werte (aus den Git-LFS-Zeigern), erst dann Übernahme in den privaten Speicher; ungeprüfte Dateien werden nie geladen und gelöscht
- ✅ JNI: Modellpfad nur aus dem privaten Ordner, Sprache aus fester Liste, Threads begrenzt (1–8); Ausgabe als UTF-8-Bytes (keine ungültigen Java-Strings), in Java erneut geprüft (Zeiten, Längen, Geräuschmarken, Wiederholungen)
- ✅ Abbruch jederzeit (Abbruch-Rückruf in whisper.cpp); Modell wird nach dem Auftrag aus dem Speicher entfernt
- ✅ Offline verlässt der Ton das Gerät nicht; Zusammenfassung/Fragen nur, wenn ein Gemini-Schlüssel hinterlegt ist (in der App erklärt)
- ⚠️ Keine Sprechererkennung offline; Large v3 Turbo braucht viel Arbeitsspeicher (ca. 1 GB) und ist langsam – in der App als „Genau“ gekennzeichnet
- ⚠️ Modell-Download über Hugging Face: Hugging Face sieht IP-Adresse und Zeitpunkt (offengelegt im Download-Hinweis)

## 9f. CRM-Eintrag (ab 1.8)
- ✅ Vorlage „crm“ steht auf der festen Positivliste; unbekannte Werte fallen auf „standard“ zurück
- ✅ Prompt verbietet erfundene Firmen-/Personennamen; Ergebnis läuft durch dieselbe Prüfung (`fromJson`, Längen, Steuerzeichen) wie jede Zusammenfassung
- ✅ Weitergabe nur durch Nutzeraktion: Zwischenablage (sensibel markiert) oder E-Mail-Entwurf per `mailto:` – kein automatischer Versand, keine CRM-Schnittstelle, keine Zugangsdaten
- ✅ Hinweis „Von Gemini erstellt – bitte vor dem Weitergeben prüfen“ steht direkt über den Knöpfen
- ✅ Unit-Test `crmTemplateAndEntry`

## 9g. Aufgaben und Todoist (ab 1.10)
- ✅ Todoist ist optional und aus, solange kein Token eingetragen ist; automatisches Senden zusätzlich standardmäßig aus
- ✅ Token: AES-256-GCM mit eigenem Android-Keystore-Schlüssel, eigene Einstellungsdatei, nicht in der Datensicherung (allowBackup=false, nicht im verschlüsselten Export); Eingabe mit FLAG_SECURE, Passwortfeld, kein Autofill; wird vor dem Speichern gegen die API geprüft
- ✅ Nur HTTPS zu fest geprüftem Host `api.todoist.com`, keine Weiterleitungen, Zeitlimits, Antwort max. 1 MB, Token nur im Authorization-Header (nie in URL/Log)
- ✅ Datensparsam: übertragen werden nur Aufgabentext, Zuständiger, Titel und Datum des Gesprächs – kein Transkript, keine Zusammenfassung, kein Ton
- ✅ Gemini-Text wird vor dem Senden entschärft (Markdown-Links/Formatierung entfernt), Länge begrenzt
- ✅ IDs aus Todoist werden per Positivliste `[A-Za-z0-9_-]{1,64}` geprüft, bevor sie in Pfade oder Speicher gelangen; Pfade werden nur über `taskPath` gebaut
- ✅ Keine Duplikate bei Wiederholung: feste `X-Request-Id` je Gespräch und Aufgabentext
- ✅ Abgleich höchstens 1×/Minute und max. 60 Abfragen je Lauf; Abbruch bei 401/403/429 oder offline mit verständlicher Meldung
- ✅ Gleichzeitige Änderungen (Dienst schreibt Zusammenfassung, Nutzer hakt ab): `Store.update` übernimmt Haken/Verknüpfung aus dem aktuellen Stand; `Store.editTask` prüft Index und Text
- ✅ Teilen an andere Aufgaben-Apps nur per Android-Teilen-Dialog durch Nutzeraktion
- ✅ Unit-Tests `taskStateRoundTripAndCarry`, `todoistRequestsAndReplies`

## 9h. Papierkorb (ab 1.11)
- ✅ Nur im privaten App-Speicher (files/trash/<id>/), Dateien werden umbenannt statt kopiert – kein zusätzlicher Speicherort, kein externer Speicher
- ✅ Ordnernamen werden per `Text.isId` geprüft, bevor daraus Pfade entstehen; Inhalte beim Lesen wie immer über `Transcript.fromJson` validiert, Infodatei max. 64 KB
- ✅ Automatisches endgültiges Löschen (Standard 30 Tage, wählbar 7/30/90/nie) beim App-Start; endgültiges Löschen gibt auch die Dateifreigabe der Quelle frei
- ✅ „Alles löschen“ leert den Papierkorb mit (DSGVO: vollständige Löschung möglich)
- ✅ Papierkorb ist nicht in Datensicherung, Suche, Aufgaben, Widget oder Todoist-Abgleich enthalten
- ✅ Endgültiges Löschen und Leeren nur nach Rückfrage
- ⚠️ Gelöschte Inhalte bleiben bis zum endgültigen Löschen auf dem Handy – im Datenschutzhinweis erwähnt

## 9i. Links: Webseiten, PDFs, YouTube (ab 1.12)
- ✅ Adressen per `WebPage.cleanUrl`: nur http(s) → immer HTTPS, keine Zugangsdaten in der URL, nur Standardports, keine Hosts ohne Punkt/`localhost`/`.local`, max. 2000 Zeichen; gespeicherte URL wird beim Laden erneut geprüft
- ✅ Kein Zugriff aufs eigene Netz (SSRF-Schutz): jede Adresse und jede Weiterleitung wird aufgelöst und bei Loopback, privaten (10/8, 172.16/12, 192.168/16, fc00::/7), Link-local, CGNAT- und Multicast-Adressen abgelehnt
- ✅ Weiterleitungen manuell (max. 5), jede einzeln geprüft; keine Cookies; Zeitlimit 20 s; HTML max. 3 MB, PDF max. 15 MB (Cache-Datei wird immer gelöscht); nur HTML/Text/JSON/PDF
- ✅ HTML wird nie angezeigt oder ausgeführt (kein WebView): Skripte, Styles, iframes, Formulare entfernt, nur Klartext über `Text.block` (Steuer-/Bidi-Zeichen raus), Anzeige per setText
- ✅ Gemini-Ausweg (url_context) nur, wenn direkter Abruf scheitert; Antwort wird nur übernommen, wenn Gemini den erfolgreichen Abruf bestätigt (`URL_RETRIEVAL_STATUS_SUCCESS`) – sonst Fehlermeldung statt erfundenem Inhalt; Prompt: Inhalt = Daten, nicht Anweisungen
- ✅ YouTube nur mit geprüfter 11-stelliger Video-ID, kanonische URL wird neu gebaut; Titel über oEmbed (ohne Schlüssel) mit gleicher Prüfung
- ✅ „Quelle öffnen“ nur mit erneut geprüfter HTTPS-Adresse über ACTION_VIEW/CATEGORY_BROWSABLE
- ✅ Zwischenablage wird nur gelesen, wenn der Nutzer „Link“ antippt (Vorbelegung)
- ✅ Unit-Tests `webUrlsAreCheckedAndCleaned`, `htmlMainTextIsExtracted`, `geminiUrlReplyNeedsRetrievalAndText`
- ⚠️ Datenschutz: Die besuchte Seite sieht die IP-Adresse des Handys (wie im Browser); beim Gemini-Ausweg und bei YouTube erfährt Google die Adresse

## 9j. App-Browser (WebView) für Seiten mit Anmeldung (ab 1.13) – bewusste Ausnahme
Begründung: Inhalte hinter Logins (z. B. Lernplattform) sind sonst nicht lesbar. Ausnahme von „keine WebView“, daher abgesichert:
- ✅ Nur auf ausdrücklichen Wunsch (eigener Bildschirm), nicht exportiert, startet nie von selbst
- ✅ Nur HTTPS: http wird auf https gehoben; intent:, file:, content:, javascript:, mailto:, tel: werden blockiert; SSL-Fehler = Abbruch (keine Ausnahmen)
- ✅ `setAllowFileAccess(false)`, `setAllowContentAccess(false)`, keine Geolocation, keine neuen Fenster/Pop-ups, Mixed Content nie, Safe Browsing an, Formulardaten werden nicht gespeichert
- ✅ Keine JavaScript-Brücke (`addJavascriptInterface` wird nicht verwendet); das Leseskript liest nur (Titel, HTML, Text gleich-origin-Rahmen) und sendet nichts
- ✅ Kamera/Mikrofon-Anfragen werden abgelehnt, Dateiauswahl abgelehnt, Downloads nicht möglich
- ✅ Drittanbieter-Cookies aus; Anmeldungen lassen sich im Browser-Menü und in den Einstellungen löschen; „Alles löschen“ löscht sie mit
- ✅ FLAG_SECURE: keine Screenshots/Bildschirmaufnahme, kein Inhalt in der App-Übersicht
- ✅ Übernommener Text läuft durch dieselbe Bereinigung wie Webseiten (`WebPage.extract`/`Text.block`), max. 100.000 Zeichen
- ✅ Login-Seiten werden beim direkten Abruf erkannt → klare Meldung statt Gemini-Versuch
- ✅ Unit-Test `loginPagesAndBrowserResults`
- ⚠️ Rest-Risiko: JavaScript der besuchten Seite läuft (wie in jedem Browser) – isoliert vom Rest der App, ohne Brücke und ohne Dateizugriff

## 9k. PDF-Dateien (ab 1.13)
- ✅ Nur über Android-Dateiauswahl/Teilen (content://), max. 15 MB, im privaten Speicher; wandert mit in den Papierkorb und wird mit gelöscht

## 9l. Natürliche Vorlesestimme (ab 1.14)
- ✅ Gemini-TTS über denselben Client: nur HTTPS zu `generativelanguage.googleapis.com`, Schlüssel nur im Header, keine Weiterleitungen, Antwort max. 6 MB, Audio max. 8 MB je Stück
- ✅ Stimmen nur aus fester Positivliste, Sprachcode nur aus fester Liste; Prompt: Text = Inhalt, keine Anweisungen
- ✅ Audio nur als PCM (audio/L16/pcm, 8–48 kHz) übernommen, nur im Speicher, nie als Datei gespeichert
- ✅ Datensparsam umschaltbar: „Handy-Stimme (offline)“ sendet nichts; Hinweis in den Einstellungen, dass der Text bei der natürlichen Stimme an Google geht
- ✅ Ausfall (offline, Kontingent, Modell) → automatisch Handy-Stimme ab der Stelle, mit Hinweis
- ✅ Handy-Stimme: automatisch beste installierte Stimme (Qualität, offline bevorzugt); gespeicherter Stimmname per Positivliste geprüft
- ✅ Unit-Test `naturalVoiceRequestsAndAudio`

## 9m. CRM-Eintrag in Ich-Form (ab 1.15)
- ✅ Name des Nutzers nur lokal in den Einstellungen (max. 40 Zeichen, bereinigt), gelangt nur in den Prompt der CRM-Zusammenfassung; Sprecherwahl aus den vorhandenen Sprechern, im Prompt über `cleanForPrompt` (keine Anführungszeichen/Klammern)
- ✅ Felder werden beim Laden geprüft (Länge, Steuerzeichen); Ich-Form wirkt nur bei der CRM-Vorlage
- ✅ Unit-Test `crmFirstPersonPrompt`

## 9n. Gleichbleibende Ergebnisse, Drag & Drop (ab 1.16/1.17)
- ✅ Alle Gemini-Anfragen mit Temperatur 0 (Fragen 0,1) und festem Seed; immer zuerst das beste Modell, Ausweichmodelle nur mit Sperrzeit (429: 10 min, 404: 24 h) – keine dauerhafte Verschlechterung
- ✅ Verwendetes Modell wird gespeichert (Positivliste `[a-z0-9.-]{1,48}`) und angezeigt
- ✅ Drag & Drop nur innerhalb der App (kein `DRAG_FLAG_GLOBAL`, leere ClipData, IDs nur im lokalen Zustand); Ziel-Ordner über `Folders.clean` geprüft

## 9o. Servicebericht (ab 1.18)
- ✅ Vorlage „service“ auf der festen Positivliste; ein-/ausschaltbar (ausgeschaltet: nicht wählbar, keine Knöpfe; gespeicherte Berichte bleiben unverändert)
- ✅ Prompt verbietet erfundene Namen, Versionen, Zeiten und Preise; Ergebnis durch dieselbe Prüfung wie jede Zusammenfassung
- ✅ Weitergabe nur durch Nutzeraktion (Zwischenablage sensibel markiert, E-Mail-Entwurf)
- ✅ Unit-Test `serviceReport`

## 9p. Anrufaufnahmen übernehmen (ab 1.19)
- ✅ Keine Speicher-Berechtigung: Zugriff nur auf den einen vom Nutzer gewählten Ordner (Storage Access Framework, nur Lesen, dauerhafte Freigabe), wird beim Ausschalten/Wechsel freigegeben; entzogene Freigabe = Funktion aus
- ✅ Die App nimmt selbst keine Anrufe auf (Android verbietet es; nutzt nur Dateien der Telefon-App); Hinweis auf Einverständnis (§ 201 StGB) beim Einrichten
- ✅ Nur Audiodateien (MIME audio/* oder bekannte Endung, ≥ 2 KB), nur neuer als der Einschaltzeitpunkt, erst wenn 20 s unverändert; Dopplungsschutz über Dokument-IDs + Zeitstempel; max. 2000 Dateien, 1 Unterordnerebene
- ✅ Hintergrund nur über JobScheduler (Inhaltsänderung im Medienspeicher + alle 30 min, nach Neustart über RECEIVE_BOOT_COMPLETED); JobService nicht exportiert, nur BIND_JOB_SERVICE
- ✅ Transkription startet nur durch Nutzeraktion (Benachrichtigung, App öffnen) – kein heimlicher Upload; Benachrichtigung VISIBILITY_PRIVATE (Sperrbildschirm ohne Inhalt), PendingIntents IMMUTABLE
- ✅ Neuer Status „waiting“ auf der Positivliste; Unit-Test `waitingCallsSurviveReload`

## 9q. Mehrere Dateien zusammenführen (ab 1.20)
- ✅ Nur content://-Adressen, max. 20 Dateien, nur Audio/Video; Freigaben je Datei (dauerhaft, sonst vorübergehend per ClipData an den Dienst) und beim endgültigen Löschen wieder freigegeben; in der Sicherung entfernt
- ✅ Teile beim Laden geprüft (Name bereinigt/gekürzt, URI nur content://, Startzeit 0–24 h); Dateinamen im Prompt über `cleanForPrompt`
- ✅ Gesamtlänge weiterhin max. 10 h; ein gemeinsamer Encoder (16 kHz mono), keine Zwischendateien
- ✅ SEND_MULTIPLE nur für audio/* und video/*
- ✅ Unit-Test `joinedRecordings`

## 9r. Zielordner, Aufnahmen vorhören (ab 1.21/1.22)
- ✅ Zuletzt geöffneter Ordner nur als ID gespeichert und über `Folders.clean` geprüft (gelöschter Ordner → oberste Ebene)
- ✅ Vorhören nur aus dem einen freigegebenen Ordner (SAF, nur Lesen), Wiedergabe lokal mit MediaPlayer, nichts wird hochgeladen, bevor der Nutzer „transkribieren/übernehmen“ wählt
- ✅ Ergebnis nur an die eigene App (setResult), Aktivität nicht exportiert; max. 20 Dateien je Auswahl
- ✅ Dateinamen nur als Text angezeigt (setText), Länge begrenzt

## 9s. Einschätzung mit Websuche (ab 1.23)
- ✅ Nur auf ausdrücklichen Wunsch (Knopf „Aufwand schätzen“ oder Schalter „Mit Einschätzung“); Standard bleibt „nur aus dem Gespräch“
- ✅ Gemini-Werkzeug `google_search`; keine eigenen anderen Einträge werden mitgeschickt; Fakten zum Fall nur aus dem Transkript (Prompt)
- ✅ Quellen aus den Grounding-Metadaten, nur HTTPS über `WebPage.cleanUrl` (javascript:/file: verworfen), max. 8, beim Laden erneut geprüft; Öffnen nur per ACTION_VIEW/BROWSABLE
- ✅ Antwort als reiner Text (Markdown entfernt, `Text.block`, max. 5000 Zeichen), Anzeige per setText, klar als Einschätzung gekennzeichnet
- ✅ „Über mich“ lokal, max. 200 Zeichen, im Prompt über `cleanForPrompt`
- ⚠️ Google erhält Frage und Transkript-Ausschnitt wie bei jeder Gemini-Anfrage; Websuche kann zusätzliche Kosten/Kontingent verbrauchen
- ✅ Unit-Test `expertEstimatesUseWebSearch`

## 10. Datenschutz (DSGVO) und Barrierefreiheit
- ✅ Datensparsamkeit: kein Konto, kein Tracking, keine Werbung; an Google geht nur die verkleinerte Mono-Tonspur bzw. für die Zusammenfassung der Text, nur auf Knopfdruck; Titel und Begriffe als Teil der Anweisung
- ✅ Offengelegt vor der ersten Nutzung (Hinweis mit Bestätigung) und unter Einstellungen → Datenschutz: Empfänger Google Gemini, eigener Schlüssel, Bedingungen des Gratis-Kontingents, § 201 StGB
- ✅ Alles löschbar: einzelnes Transkript, nur den Ton, alles auf einmal; Option „Ton nicht behalten“
- ⚠️ Auftragsverarbeitung: private Nutzung. Für Kundengespräche (nerpcon) vorher bezahlte Gemini-Variante und Vertrag mit Google prüfen
- ✅ Bedienelemente beschriftet (`contentDescription`), Überschriften markiert, Hell/Dunkel in beiden Designs, Schrift in sp, Tippflächen ≥ 44–48 dp, Abschnitte für Screenreader mit Zeit und Sprecher vorgelesen
- ✅ README mit Zweck, Build, Einschränkungen, Sicherheit

---

## Offene Punkte
| Punkt | Risiko | Begründung | Erledigen bis |
|---|---|---|---|
| Gerätetest (Datei, Teilen, Aufnahme, Benachrichtigung, Export, Neustart) | mittel | nur auf Marios Handy möglich | vor dauerhafter Nutzung |
| Echte Gemini-Antworten (Zeitstempel, Sprecher über mehrere Teile) | mittel | in der Arbeitsumgebung gesperrt; Prüfung und Rückfälle sind getestet, die Qualität nicht | erster Gerätetest |
| Gratis-Kontingent nur für private Nutzung | mittel | Google nutzt im Gratis-Kontingent Daten ggf. anders; für Kundendaten bezahlte Variante | vor Nutzung mit Kunden |
| Offline-Erkennung auf dem Gerät testen (Geschwindigkeit, Speicher) | mittel | nur auf Marios Handy möglich; Modellgröße je nach Gerät wählen | erster Gerätetest |
| whisper.cpp-Updates | niedrig | Version fest; neue Versionen bewusst prüfen und Submodul anheben | bei Bedarf |
| Kein Certificate Pinning | niedrig | Zertifikatswechsel bei Google würde die App lahmlegen; System-CAs ohne Nutzer-CAs genügen | bei Weitergabe neu bewerten |
| Kein Screenshot-Schutz der Transkript-Ansicht | niedrig | eigene Nutzung; Schlüssel-Dialog ist geschützt | bei Weitergabe neu bewerten |
| Sprecherzuordnung über Teile hinweg | niedrig | Modell bekommt bekannte Sprecher und den letzten Satz als Kontext, kann aber verwechseln; Umbenennen und Bearbeiten möglich | – (akzeptiert) |
| Gradle ohne Wrapper-Prüfsumme | niedrig | Version fest, offizielle Action | Version 1.6 |
