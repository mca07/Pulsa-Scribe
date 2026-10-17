# Pulsa Scribe – Gerätetest (Version 1.1)

Jeden Punkt am Handy ausprobieren und ✅ oder ❌ notieren. Bei ❌ bitte einen Screenshot und den genauen Text der Meldung in den Chat schicken.

## 1. Installation und Einrichtung
- [ ] APK aus dem GitHub-Release „neueste“ installiert (signiert, nicht die Test-APK)
- [ ] Einstellungen → Version zeigt „1.1.x“
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
