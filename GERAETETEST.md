# Pulsa Scribe – Gerätetest (Version 1.7)

Jeden Punkt am Handy ausprobieren und ✅ oder ❌ notieren. Bei ❌ bitte einen Screenshot und den genauen Text der Meldung in den Chat schicken.

## 1. Installation und Einrichtung
- [ ] APK aus dem GitHub-Release „neueste“ installiert (signiert, nicht die Test-APK)
- [ ] Einstellungen → Version zeigt „1.7.x“
- [ ] Einstellungen → „Schlüssel bei Google AI Studio holen“ öffnet den Browser
- [ ] Einstellungen → Gemini-Schlüssel einfügen → „Hinterlegt“; Screenshot im Schlüssel-Dialog ist gesperrt
- [ ] Falscher Schlüssel (z. B. nur „abc“) → Meldung „sieht nicht wie ein Gemini-Schlüssel aus“
- [ ] Design „Academy“ und zurück zu „Pulsa“, Hell/Dunkel des Handys – alles gut lesbar

## 2. Datei transkribieren
- [ ] „Datei wählen“ → kurze Sprachnachricht oder Audio (1–3 Min) → Hinweis „Kurz vorab“ erscheint einmal → Startdialog mit Titel, Sprache, Schaltern, Begriffen
- [ ] Liste zeigt „Ton wird vorbereitet … %“, dann „Wird transkribiert …“, dann „Zusammenfassung wird erstellt …“
- [ ] Benachrichtigung zeigt Fortschritt; am Ende „Fertig: …“, Antippen öffnet das Transkript
- [ ] Transkript: Sprecher farbig, Zeitstempel plausibel (stimmen beim Antippen ungefähr mit der Stelle im Ton überein)
- [ ] Video (MP4 aus der Galerie oder aus Pulsa Loader) → wird transkribiert
- [ ] Längere Datei (> 20 Min, z. B. Kursvideo) → „Teil 1 von 2 …“; Übergänge zwischen den Teilen ohne Lücke oder Doppelung, Sprecher behalten ihre Bezeichnung
- [ ] Wie lange dauert ein 15-Minuten-Teil ungefähr? – bitte notieren
- [ ] Aus einer anderen App teilen (z. B. WhatsApp-Sprachnachricht oder Pulsa Loader → Teilen → „Mit Pulsa Scribe transkribieren“)
- [ ] Datei ohne Ton (z. B. stummes Video) → Meldung „enthält keine Tonspur“

## 3. Aufnahme
- [ ] „Aufnehmen“ → Hinweis zu § 201 StGB, Start nur mit Häkchen „Alle Beteiligten … einverstanden“
- [ ] Erste Aufnahme fragt nach der Mikrofon-Berechtigung
- [ ] Uhr läuft, Pegel bewegt sich beim Sprechen; Benachrichtigung „Pulsa Scribe nimmt auf“ mit Pause/Beenden
- [ ] Bildschirm aus, 2 Minuten warten, wieder an → Aufnahme lief weiter
- [ ] Pause → Uhr steht; Weiter → läuft weiter
- [ ] „Beenden“ in der Benachrichtigung → App öffnet sich, Transkription startet
- [ ] „Beenden und transkribieren“ → Transkript öffnet sich und füllt sich
- [ ] „Verwerfen“ → Rückfrage → Aufnahme verschwindet
- [ ] Optional: App während der Aufnahme aus den letzten Apps wegwischen → App neu öffnen → Eintrag „Aufnahme wurde unterbrochen … gesichert“ → „Erneut versuchen“ transkribiert den bisherigen Ton

## 4. Transkript bearbeiten
- [ ] Zeile antippen → Ton springt dorthin und spielt; aktuelle Zeile ist hinterlegt und läuft mit
- [ ] „10 s zurück“ und Schieberegler funktionieren
- [ ] ⋮ → „Sprecher umbenennen“ → „Sprecher 1“ = echter Name → überall geändert
- [ ] Zeile lange drücken → Text korrigieren, Sprecher wechseln, Abschnitt löschen
- [ ] Lupe → Suchbegriff → Treffer markiert, Anzahl stimmt
- [ ] Reiter „Zusammenfassung“: Überblick, Kernpunkte, Aufgaben (mit Person), Entscheidungen, offene Fragen
- [ ] ⋮ → „Zusammenfassung neu erstellen“ (nach dem Umbenennen) → Namen stehen in der Zusammenfassung
- [ ] Titel antippen → umbenennen

## 5. Export
- [ ] Teilen-Symbol → PDF → Teilen (z. B. an Gmail oder Drive) → Datei öffnet sich, Umlaute korrekt, Seitenzahlen
- [ ] Word (.docx) → „Speichern unter …“ → in Word/Google Docs öffnen
- [ ] Text (.txt) mit/ohne Zeitstempel
- [ ] Untertitel (.srt) → im Videoplayer (z. B. VLC) zusammen mit dem Video laden → Text passt ungefähr
- [ ] ⋮ → „Text kopieren“ → in einer Notiz einfügen

## 6. Fehler und Grenzfälle
- [ ] Flugmodus während der Transkription → Meldung „Keine Verbindung …“; danach „Erneut versuchen“ macht beim offenen Teil weiter
- [ ] Transkription über die Benachrichtigung abbrechen → „Abgebrochen“, „Erneut versuchen“ geht weiter
- [ ] Gemini-Schlüssel löschen und transkribieren → Hinweis auf fehlenden Schlüssel
- [ ] Transkript während der Transkription löschen → verschwindet und kommt nicht wieder
- [ ] Einstellungen → „Ton behalten“ aus → nach dem Transkribieren ist kein Abspielen mehr möglich, Text bleibt
- [ ] ⋮ → „Ton löschen (Text bleibt)“
- [ ] Handy neu starten → alle Transkripte sind noch da

## 7. Ordner und Schlagwörter (neu in 1.2)
- [ ] Ordner-Symbol oben rechts → „Kunden“ anlegen → antippen → darin „Müller GmbH“ anlegen → Pfad „Alle › Kunden › Müller GmbH“
- [ ] In „Müller GmbH“ aufnehmen oder Datei wählen → neuer Eintrag landet automatisch dort
- [ ] Eintrag lange drücken → „Verschieben …“ → anderer Ordner; „Schlagwörter …“ → „Vertrieb, KI“ → erscheinen als #Vertrieb #KI
- [ ] Ordner ⋮ → Umbenennen, Verschieben (nicht in sich selbst möglich), Löschen → Inhalt wandert eine Ebene hoch
- [ ] Zurück-Taste geht eine Ordnerebene hoch
- [ ] Lupe auf der Startseite → Suchbegriff findet Treffer in allen Ordnern, „#vertrieb“ sucht nur Schlagwörter; Treffer antippen → Transkript öffnet mit markierter Stelle

## 8. Teilen → Zusammenfassen und Vorlesen (neu in 1.2)
- [ ] In LinkedIn/Browser einen Beitragstext markieren → Teilen → „Zusammenfassen“ (Pulsa Scribe) → Dialog → „Zusammenfassen“ → Zusammenfassung erscheint und wird vorgelesen
- [ ] Screenshot eines Beitrags → Teilen → Pulsa Scribe → Bild oben, darunter gelesener Text, Zusammenfassung, Vorlesen
- [ ] Nur einen Link teilen → Hinweis, dass Links nicht geöffnet werden
- [ ] Im Transkript ⋮ → „Vorlesen …“ → Zusammenfassung bzw. Transkript ab der markierten Stelle; Leiste „Wird vorgelesen …“ mit Stopp
- [ ] Einstellungen → Vorlesegeschwindigkeit ändern → hörbar schneller/langsamer
- [ ] Schnelleinstellungen bearbeiten → Kachel „Aufnehmen“ hinzufügen → antippen öffnet die Aufnahme
- [ ] Startbildschirm → Widgets → „Pulsa Scribe: Schnellstart“ → „Aufnehmen“ und „Pulsa Scribe“ funktionieren

## 9. Vorlagen, Fragen, Übersetzen, Aufgaben (neu in 1.3)
- [ ] Beim Start „Kurs-Lektion (Lernzettel)“ wählen (z. B. BZKI-Video) → Zusammenfassung mit „Lernzettel“, „Merksätze und Begriffe“, „Prüfungsfragen“ mit Antworten
- [ ] ⋮ → „Vorlage der Zusammenfassung …“ → „Kundentermin“ → neu zusammenfassen
- [ ] Reiter „Fragen“ → „Was wurde zum Preis gesagt?“ → Antwort mit ▶-Zeiten → Antippen springt im Ton dorthin
- [ ] ⋮ → „Übersetzen …“ → Englisch → nach kurzer Zeit erscheint der englische Text, Hinweis oben; ⋮ → „Anzeigen in …“ → Original
- [ ] Zusammenfassung → Aufgabe antippen → „In den Kalender“ (Kalender-App öffnet sich vorausgefüllt) bzw. „Per E-Mail senden“
- [ ] Ordner ⋮ → „Ordner auswerten …“ → neuer Eintrag „Auswertung: …“ mit gemeinsamer Zusammenfassung

## 10. Datensicherung (neu in 1.4)
- [ ] Einstellungen → „Sicherung erstellen“ → Passwort zweimal → Speicherort (z. B. Google Drive) → „Sicherung gespeichert“
- [ ] Mit Ton mitsichern → Datei deutlich größer
- [ ] „Sicherung wiederherstellen“ mit falschem Passwort → Meldung „Falsches Passwort …“
- [ ] Ein Transkript löschen → wiederherstellen → es ist wieder da, alle anderen bleiben unverändert („… schon vorhanden“)

## 11. Offline-Erkennung (neu in 1.5)
- [ ] Einstellungen → Offline-Erkennung → „Ausgewogen (Small, 190 MB)“ → Herunterladen → Fortschritt, danach „Wird geprüft …“ → „Installiert ✓ · wird verwendet“
- [ ] Datei wählen → „Transkription: Offline (Whisper)“ → Benachrichtigung „Wird offline transkribiert … %“ → Text ohne Sprecher
- [ ] Wie lange braucht 1 Minute Ton offline ungefähr? – bitte notieren (Small und ggf. Base)
- [ ] Flugmodus an → offline transkribieren klappt trotzdem; die Zusammenfassung kommt erst mit Internet (oder Hinweis)
- [ ] Offline-Transkription abbrechen → „Abgebrochen“, „Erneut versuchen“ geht weiter
- [ ] Optional: „Genau (Large v3 Turbo)“ laden – läuft es auf dem Handy, oder kommt „Modell konnte nicht geladen werden“?

## 12. Mehrfachauswahl (neu in 1.6)
- [ ] Transkript lange drücken → wird markiert, oben erscheint „1 ausgewählt“ mit Alle / Ordner / Papierkorb
- [ ] Weitere antippen → zählt mit; „Alle“ markiert alle sichtbaren, danach heißt der Knopf „Keine“
- [ ] Papierkorb → Rückfrage „… Transkripte löschen?“ → gelöscht
- [ ] Ordner-Symbol → Ziel wählen → alle markierten werden verschoben
- [ ] Zurück-Taste oder ✕ beendet die Auswahl

## 13. Sprache und Länge der Zusammenfassung (neu in 1.7)
- [ ] Deutsches Gespräch mit Sprache „Automatisch erkennen“ → Zusammenfassung ist auf Deutsch
- [ ] Beim Start „Länge der Zusammenfassung“ = Kurz → höchstens ~5 Punkte je Abschnitt
- [ ] Im Transkript ⋮ → „Zusammenfassung neu erstellen …“ → „Ausführlich“ → deutlich mehr Details; Sprache dort ebenfalls wählbar
- [ ] Einstellungen → „Länge der Zusammenfassung“ als Standard ändern

## 14. CRM-Eintrag (neu in 1.8)
- [ ] Vorhandenes Kundengespräch öffnen → Tab Zusammenfassung → „Als CRM-Eintrag erstellen“ → Rückfrage bestätigen → neue Zusammenfassung mit Notiz, CRM-Feldern (Betreff, Kontaktart, Ansprechpartner …), Nächste Schritte, Vereinbarungen, Offen
- [ ] Keine erfundenen Firmen- oder Personennamen; fehlende Felder fehlen einfach
- [ ] „CRM-Eintrag kopieren“ → in myFactory (oder Notizen-App) einfügen → Text vollständig, Betreff oben, nur einmal
- [ ] Android 13+: Kopier-Vorschau unten links zeigt den Text nicht an (als sensibel markiert)
- [ ] „CRM-Eintrag per E-Mail“ → Mail-App mit Betreff „CRM-Eintrag: …“ und dem Text
- [ ] Neue Aufnahme mit Vorlage „CRM-Eintrag (Kundenkontakt)“ starten → direkt im CRM-Format

## 15. Mehrere verschieben (neu in 1.9)
- [ ] Lange drücken → mehrere markieren → oben Knopf „Verschieben“ (mit Ordner-Symbol) gut sichtbar
- [ ] „Verschieben“ → Titel „2 Transkripte verschieben nach“; der gerade geöffnete Ordner steht nicht in der Liste
- [ ] Oben „＋ Neuer Ordner …“ → Name eingeben → Ordner wird im aktuellen Ordner angelegt und alle markierten landen sofort darin
- [ ] Hinweis „2 verschoben nach …“, Auswahl ist beendet, Liste aktualisiert

## 16. Aufgaben und Todoist (neu in 1.10)
Ohne Todoist:
- [ ] Startbildschirm: neues Haken-Symbol oben → „Aufgaben“ zeigt offene Aufgaben aller Gespräche, gruppiert nach Gespräch
- [ ] Kästchen antippen → Aufgabe durchgestrichen, Zähler sinkt; „Erledigte“ einschalten → wieder sichtbar, Haken entfernen geht
- [ ] Aufgabe antippen → Gespräch öffnet im Tab Zusammenfassung
- [ ] Im Gespräch: Kästchen vor jeder Aufgabe; Zeile antippen → Menü mit Kalender / E-Mail / „An Aufgaben-App teilen“
- [ ] „Offene Aufgaben an Aufgaben-App teilen“ → z. B. Microsoft To Do oder Todoist-App wählen → Aufgabe(n) dort angelegt
- [ ] „Zusammenfassung neu erstellen“ → Haken bei unveränderten Aufgaben bleiben erhalten
Mit Todoist:
- [ ] Einstellungen → „Token in Todoist holen“ → Token kopieren → „Todoist-Token“ → einfügen → „Todoist verbunden“ (falscher Token → Fehlermeldung im Feld, nichts gespeichert)
- [ ] Bildschirmfoto im Token-Dialog ist gesperrt
- [ ] Im Gespräch „Offene Aufgaben an Todoist senden“ → in Todoist erscheint Projekt „Pulsa Scribe“ mit den Aufgaben (Beschreibung: Zuständig + Gespräch/Datum); in der App steht „Todoist ✓“
- [ ] Noch einmal senden → „Nichts zu senden“, keine Doppelten in Todoist
- [ ] In der App abhaken → in Todoist erledigt; in Todoist abhaken → App öffnen/„Aufgaben“ → nach max. 1 Minute bzw. ↻ abgehakt
- [ ] In Todoist eine Aufgabe löschen → Abgleich → in der App bleibt sie, aber ohne „Todoist ✓“
- [ ] Schalter „Neue Aufgaben automatisch senden“ an → neues Gespräch → Aufgaben landen ohne Knopfdruck in Todoist
- [ ] Flugmodus → Senden → „Todoist nicht erreichbar“, App bleibt bedienbar
- [ ] „Todoist-Token“ → „Entfernen“ → Knöpfe für Todoist verschwinden

## 17. Papierkorb (neu in 1.11)
- [ ] Transkript löschen (⋮ oder lange drücken → Papierkorb-Symbol) → Rückfrage „In den Papierkorb“ → verschwindet aus der Liste
- [ ] Startbildschirm: graues Papierkorb-Symbol neben „Neuer Ordner“ → Papierkorb öffnet
- [ ] Oben: belegter Platz (z. B. „45,2 MB“), Anzahl Einträge, Balken „Papierkorb … von insgesamt …“, Hinweis „nach 30 Tagen endgültig gelöscht“
- [ ] Pro Eintrag: Gelöscht am, Größe (mit Ton), Dauer, Erstellt am, War in Ordner, Endgültig gelöscht am
- [ ] „Wiederherstellen“ → wieder im ursprünglichen Ordner (Ordner inzwischen gelöscht → oberste Ebene), Ton abspielbar, Zusammenfassung/Aufgaben/Haken da
- [ ] „Endgültig löschen“ und „Leeren“ fragen nach und löschen dann wirklich; Größe sinkt
- [ ] Einstellungen → Daten: „Papierkorb“ zeigt Anzahl und Größe; „Papierkorb automatisch leeren“ (7 / 30 / 90 Tage / nie)
- [ ] Gelöschte Transkripte tauchen nicht in Suche, Aufgaben-Übersicht, Widget oder Datensicherung auf
- [ ] Während einer laufenden Transkription löschen → Abbruch, im Papierkorb; nach Wiederherstellen „Erneut versuchen“ möglich
- [ ] Einstellungen → „Alles löschen“ leert auch den Papierkorb

## 18. Links: Webseiten, PDFs, YouTube (neu in 1.12)
- [ ] Startbildschirm unten jetzt drei Knöpfe: Aufnehmen · Link · Datei
- [ ] Link in Chrome kopieren → „Link“ → Adresse ist vorausgefüllt → „Weiter“
- [ ] Nachrichtenartikel (z. B. tagesschau.de) → „Zusammenfassen“ → Tab Transkript zeigt nur den Artikeltext (kein Menü, keine Cookie-Hinweise), Titel = Artikeltitel, Zusammenfassung „Beitrag / Artikel“
- [ ] Gleicher Artikel → „Vorlesen“ → ganzer Text wird vorgelesen, keine Zusammenfassung
- [ ] In Chrome „Teilen“ → Pulsa Scribe → gleicher Dialog (früher kam „nur ein Link geteilt“)
- [ ] Seite mit Cookie-Wand/nur JavaScript → klappt trotzdem über Gemini oder verständliche Meldung
- [ ] Bezahlschranke/Login → Meldung „kein lesbarer Haupttext“ – es wird nichts erfunden
- [ ] PDF-Link (z. B. Datenblatt) → Text wird gelesen und zusammengefasst
- [ ] YouTube-App „Teilen“ → Pulsa Scribe → „Transkribieren“ → Transkript mit Zeiten und Sprechern, Titel „Video – Kanal“
- [ ] Privates Video → verständliche Fehlermeldung
- [ ] Link auf http://192.168.… oder Router → „Adressen im eigenen Netzwerk werden … nicht geöffnet“
- [ ] ⋮ → „Quelle öffnen“ öffnet die Seite im Browser; Export (TXT/Word/PDF) enthält „Quelle: …“

## 19. App-Browser für Seiten mit Anmeldung, PDF-Dateien (neu in 1.13)
- [ ] lms.bzki.de per Link → Fehlermeldung nennt jetzt „verlangt eine Anmeldung“ und den Weg über den App-Browser
- [ ] Im fehlgeschlagenen Eintrag ⋮ → „Im App-Browser öffnen (mit Anmeldung)“ → Login-Seite erscheint
- [ ] Anmelden → Lektion öffnen → „Diese Seite lesen“ → Hinweis „x Absätze gefunden“ → „Zusammenfassen“ bzw. „Vorlesen“
- [ ] Zurück → wieder im Browser, noch angemeldet → nächste Lektion lesen
- [ ] App schließen und neu öffnen → im App-Browser weiterhin angemeldet
- [ ] Lernmodul mit eingebettetem Inhalt (H5P/Video-Seite) → Text aus dem Modul wird mit übernommen (falls gleiche Domain)
- [ ] Startbildschirm „Link“ → „Im App-Browser öffnen“ geht auch direkt
- [ ] Screenshot im App-Browser ist gesperrt (schwarz), App-Übersicht zeigt keinen Inhalt
- [ ] Download-Link → Hinweis, kein Download; mailto:/tel:-Link → „wird nicht geöffnet“
- [ ] ⋮ → „In Chrome öffnen“, „Neu laden“, „Anmeldungen im App-Browser löschen“ → danach abgemeldet
- [ ] Einstellungen → „Anmeldungen im App-Browser löschen“ funktioniert ebenso
- [ ] „Datei“ → PDF auswählen (bzw. PDF aus anderer App teilen) → Text gelesen, Zusammenfassung erstellt

## 20. Natürliche Stimme (neu in 1.14)
- [ ] Zusammenfassung vorlesen → klingt natürlich (Gemini-Stimme), Start nach wenigen Sekunden, keine hörbaren Pausen zwischen den Abschnitten
- [ ] Langer Artikel vorlesen → läuft durch, markierter Absatz wandert mit
- [ ] Stopp-Knopf → sofort still
- [ ] Einstellungen → Vorlesen → „Stimme“: Natürlich / Handy-Stimme umschalten → Hörprobe spielt automatisch
- [ ] „Sprecher“ (natürlich) → verschiedene Stimmen antippen → jeweils Hörprobe, Lieblingsstimme bleibt gespeichert
- [ ] Vorlesegeschwindigkeit (z. B. 1,2×) wirkt auch bei der natürlichen Stimme, Tonhöhe bleibt normal
- [ ] Flugmodus → Vorlesen → Hinweis „Kein Internet – lese mit der Handy-Stimme weiter“, liest offline weiter
- [ ] Handy-Stimme gewählt → „Sprecher“ zeigt installierte Stimmen mit Qualität (sehr gut/gut) → beste klingt deutlich besser als vorher; „Weitere Stimmen laden“ öffnet Android-Einstellungen
- [ ] Notiz: Wie viele Minuten pro Tag gehen, bevor „Kontingent erschöpft“ kommt?

## 21. CRM-Eintrag in Ich-Form (neu in 1.15)
- [ ] Einstellungen → Standardwerte: „Mein Name“ setzen (z. B. Mario), „CRM-Eintrag in Ich-Form“ einschalten
- [ ] Bestehendes CRM-Gespräch → Tab Zusammenfassung → „Perspektive: neutral – in Ich-Form umstellen …“ → Ich-Form, „Ich bin im Gespräch“ = richtiger Sprecher → Neu erstellen
- [ ] Notiz lautet „Ich habe mit … telefoniert …“, der Kunde in der 3. Person; eigene Aufgaben „— Ich“, andere mit Namen
- [ ] „Automatisch (nach Name …)“ → Gemini erkennt den eigenen Sprecher am Namen
- [ ] Zurück auf „Neutral“ → wieder neutrale Beschreibung
- [ ] „CRM-Eintrag kopieren“ übernimmt die Ich-Form

## 22. Gleichbleibende Ergebnisse (neu in 1.16)
- [ ] Dieselbe Datei zweimal transkribieren → unter der Zusammenfassung steht beide Male dasselbe „Modell: …“
- [ ] Ergebnisse deutlich ähnlicher als vorher (kleine Wortunterschiede sind möglich)
- [ ] Nach „Kontingent erschöpft“ beim besten Modell: spätestens nach 10 Minuten wird wieder das beste Modell genutzt
