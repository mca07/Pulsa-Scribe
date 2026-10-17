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
