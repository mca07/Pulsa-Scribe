# Pulsa Scribe

Android-App, die Videos, Sprachaufnahmen und Gespräche in Text umwandelt – mit Sprechern, Zeitstempeln,
Zusammenfassung (Kernpunkte, Aufgaben, Entscheidungen, offene Fragen) und Export als PDF, Word, Text oder SRT-Untertitel.
Designs Pulsa und Academy, hell und dunkel.

## So funktioniert es
1. Datei wählen (oder aus einer anderen App teilen, z. B. aus Pulsa Loader).
2. Die App liest die Tonspur, mischt sie auf Mono und speichert sie verkleinert als AAC (48 kbit/s) im privaten App-Speicher.
3. Lange Aufnahmen werden in Teile von ca. 15 Minuten geschnitten – an der leisesten Stelle kurz vor der Grenze.
4. Jeder Teil geht mit einer festen Anweisung an Google Gemini (eigener API-Schlüssel). Die Antwort ist ein festes JSON-Format
   und wird streng geprüft (Zeiten, Längen, Sprecher, Wiederholungen).
5. Danach wird die Zusammenfassung erstellt. Alles bleibt auf dem Handy; abgebrochene Läufe machen beim offenen Teil weiter.

## Build
GitHub Actions baut bei jedem Push auf `main` (Workflow „APK bauen“): Tests, dann signierte Release-APK als Release „neueste“.
Benötigte Secrets: `SCRIBE_KEYSTORE_B64` (PKCS12, Base64) und `SCRIBE_KEYSTORE_PASSWORD`. Ohne Secrets entsteht eine Debug-Test-APK.

Lokal: `gradle :app:testDebugUnitTest :app:assembleDebug` (Gradle 8.11.1, JDK 17, Android SDK 35).

## Einschränkungen
- Transkription nur online über Gemini; Offline-Erkennung (Whisper) und Live-Aufnahme sind für die nächsten Versionen geplant.
- Sprechererkennung über mehrere Teile hinweg ist eine Schätzung des Modells; Namen lassen sich umbenennen.
- Geschützte Dateien (DRM) werden nicht unterstützt.

## Sicherheit und Datenschutz
Siehe `SECURITY_CHECK.md`. Kurz: Schlüssel AES-256-GCM im Android Keystore, nur https zu `generativelanguage.googleapis.com`,
keine Weiterleitungen, kein Backup, keine Bibliotheken, keine Speicher-Berechtigung, Teilen nur mit befristeter Freigabe.
Gespräche nur mit Einverständnis aller Beteiligten aufnehmen und transkribieren (§ 201 StGB).

Schrift Inter: SIL Open Font License, siehe `tools/fonts/OFL-Inter.txt`.
