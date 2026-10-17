# Prüfbericht: Pulsa Scribe (Android)

| | |
|---|---|
| **Projekt** | Pulsa Scribe – transkribiert Audio- und Videodateien sowie live aufgenommene Gespräche mit Google Gemini: Sprecher, Zeitstempel, Zusammenfassung (Kernpunkte, Aufgaben, Entscheidungen, offene Fragen), Suche, Wiedergabe mit Sprung zur Stelle, Export als PDF, Word, Text und SRT; Designs Pulsa und Academy |
| **Art** | Android (Java, native Oberfläche, keine WebView, minSdk 29 / targetSdk 35) |
| **Version / Stand** | 1.1, 17.10.2026 |
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
- ✅ Berechtigungen: `INTERNET`, `POST_NOTIFICATIONS` (erst beim ersten Start), `RECORD_AUDIO` (erst bei der ersten Aufnahme), `FOREGROUND_SERVICE` + `_DATA_SYNC` + `_MICROPHONE`, `WAKE_LOCK`. **Keine Speicher-Berechtigung** (Datei-Auswahl und Speichern über SAF)
- ✅ PendingIntents explizit und `FLAG_IMMUTABLE`; „Beenden“ in der Aufnahme-Benachrichtigung öffnet die Aufnahme-Ansicht (Start der Transkription immer aus einer sichtbaren Ansicht)
- ✅ Aufnahme: Bestätigung „Alle Beteiligten sind einverstanden“ vor jedem Start, Hinweis auf § 201 StGB, dauerhaft sichtbare Benachrichtigung mit Pause/Beenden, Mikrofon nur während der Aufnahme
- – WebView: nicht verwendet
- ✅ Keine sensiblen Daten in externem Speicher; Zwischenablage nur auf ausdrücklichen Wunsch („Text kopieren“)
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
- ✅ Keine Bibliotheken in der App (eigener JSON-Leser, eigener Export-Provider, DOCX selbst gebaut, PDF über Android `PdfDocument`); JUnit nur für Tests. Schrift Inter (SIL OFL, `tools/fonts/OFL-Inter.txt`) als Ressource, nicht aus dem Netz
- – Schwachstellen-Scan: keine App-Abhängigkeiten
- ✅ Release: R8 (Minify + Ressourcen-Shrink), `debuggable=false`, Debug-Logs entfernt
- ✅ Eigener Signierschlüssel (RSA 4096, SHA-256 89:BD:78:DF:…:16:C9) nur als GitHub-Secret; Datei wird nach dem Build gelöscht; Signatur wird im Build angezeigt
- ✅ Build-Fehler erscheinen als Hinweise in GitHub (ohne Schlüsselzeilen)
- ⚠️ Gradle-Version fest (8.11.1), aber ohne Wrapper-Prüfsumme

## 9. Tests und Verifikation
- ✅ 40 automatische Tests: JSON (Rundreise, kaputte Eingaben, Tiefe, Steuerzeichen), Textbereinigung (Bidi, Steuerzeichen, halbe Unicode-Paare), Dateinamen (Pfadangriffe), Zeiten (gültig/ungültig/zu groß), ADTS (Kopf, Index, kaputte Daten, Rahmenlimit, abgebrochene Aufnahme), Teilung (kurze Aufnahme, leiseste Stelle, ohne Lautstärke), Transkript laden (Rundreise, manipulierte Datei, falsche ID, nur content://), Gemini (Schlüsselformat, Modellname, Prompt ohne ungeprüfte Zeichen, gültiges Anfrage-JSON, Antwort mit „thought“, Sperre, Fehlerstatus, Zeitprüfung und Ergänzung, ohne Sprecher, Endlosschleife, abgeschnittene Antwort, Zusammenfassung manipuliert, Kontext für den nächsten Teil, Modell-Rückfall), Export (TXT, SRT-Aufteilung, DOCX als gültiges ZIP mit kodiertem XML, Wortgrenzen), Sprach-Positivliste
- ✅ Tests ausgeführt: 40/40 lokal und bei jedem GitHub-Build; GitHub-Build 1.0 (Build 1) und 1.1 (Build 2) erfolgreich
- ⚠️ Android-Code nur im GitHub-Build kompiliert (keine Android-Stubs in der Arbeitsumgebung); echte Gemini-Antworten in der Arbeitsumgebung nicht abrufbar (gesperrt), Antwortformat nach Dokumentation umgesetzt; Gerätetest steht aus (siehe `GERAETETEST.md`)
- ✅ Code nach dem Schreiben erneut gelesen; dabei behoben 🔧: Rückstau im Encoder (mehrere Blöcke je Runde), Reihenfolge „Aufnahme fertig“ → Transkription, gelöschtes Transkript wurde vom Dienst wieder angelegt, Titeländerung während der Arbeit ging verloren, unsichtbare Unicode-Zeichen im Quelltext durch `\u`-Schreibweise ersetzt

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
| Offline-Transkription (Whisper) | niedrig | gewünscht („Beides wählbar“); braucht native Bibliothek und Modell-Download | Version 1.2 |
| Kein Certificate Pinning | niedrig | Zertifikatswechsel bei Google würde die App lahmlegen; System-CAs ohne Nutzer-CAs genügen | bei Weitergabe neu bewerten |
| Kein Screenshot-Schutz der Transkript-Ansicht | niedrig | eigene Nutzung; Schlüssel-Dialog ist geschützt | bei Weitergabe neu bewerten |
| Sprecherzuordnung über Teile hinweg | niedrig | Modell bekommt bekannte Sprecher und den letzten Satz als Kontext, kann aber verwechseln; Umbenennen und Bearbeiten möglich | – (akzeptiert) |
| Gradle ohne Wrapper-Prüfsumme | niedrig | Version fest, offizielle Action | Version 1.2 |
