package de.mca.pulsascribe.core;

/** Vorlagen für die Zusammenfassung: feste Liste, eigene Anweisung und eigene Überschriften. */
public final class Templates {
    private Templates() {}

    public static final String STANDARD = "standard", MEETING = "meeting", LESSON = "lesson", INTERVIEW = "interview", POST = "post", CRM = "crm", SERVICE = "service";

    public static final String[] IDS = {STANDARD, MEETING, CRM, SERVICE, LESSON, INTERVIEW, POST};
    public static final String[] LABELS = {"Allgemein", "Kundentermin / Besprechung", "CRM-Eintrag (Kundenkontakt)", "Servicebericht (Support / Einsatz)", "Kurs-Lektion (Lernzettel)", "Interview", "Beitrag / Artikel"};

    // Länge der Zusammenfassung
    public static final String SHORT = "short", MEDIUM = "medium", LONG = "long";
    public static final String[] LENGTHS = {SHORT, MEDIUM, LONG};
    public static final String[] LENGTH_LABELS = {"Kurz (das Wichtigste auf einen Blick)", "Mittel", "Ausführlich (mit Details)"};

    public static String cleanLength(String id) {
        for (String l : LENGTHS) if (l.equals(id)) return l;
        return MEDIUM;
    }

    public static int lengthIndex(String id) {
        String c = cleanLength(id);
        for (int i = 0; i < LENGTHS.length; i++) if (LENGTHS[i].equals(c)) return i;
        return 1;
    }

    /** Anweisung zur Länge; überschreibt die Zahlen aus den Feldbeschreibungen. */
    public static String lengthDirective(String id) {
        switch (cleanLength(id)) {
            case SHORT:
                return "LENGTH: SHORT. Keep it brief: overview 2 to 3 sentences, at most 5 entries in each list, each entry one short sentence. This overrides any numbers given above.";
            case LONG:
                return "LENGTH: DETAILED. Be thorough: overview 6 to 10 sentences, up to 25 entries in points with concrete details, names, numbers and examples, "
                        + "list every task, decision and open question. This overrides any maximum numbers given above.";
            default:
                return "LENGTH: MEDIUM. Use the numbers given above.";
        }
    }

    /** Berichtsvorlagen mit festen Feldern (Kopieren/E-Mail als Bericht, Ich-Form möglich). */
    public static boolean isReport(String id) {
        String c = clean(id);
        return CRM.equals(c) || SERVICE.equals(c);
    }

    /** Wählbare Vorlagen; ausgeschaltete Berichtsarten fehlen (außer sie sind gerade gewählt). */
    public static java.util.List<String> available(boolean crm, boolean service, String current) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String cur = clean(current);
        for (String id : IDS) {
            if (CRM.equals(id) && !crm && !CRM.equals(cur)) continue;
            if (SERVICE.equals(id) && !service && !SERVICE.equals(cur)) continue;
            out.add(id);
        }
        return out;
    }

    public static String clean(String id) {
        for (String i : IDS) if (i.equals(id)) return i;
        return STANDARD;
    }

    public static int index(String id) {
        String c = clean(id);
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(c)) return i;
        return 0;
    }

    public static String label(String id) {
        return LABELS[index(id)];
    }

    /** Überschriften: {Überblick, Punkte, Aufgaben, Entscheidungen, Fragen}. */
    public static String[] headings(String id) {
        switch (clean(id)) {
            case MEETING: return new String[]{"Überblick", "Besprochene Punkte", "Aufgaben", "Entscheidungen", "Offene Fragen"};
            case SERVICE: return new String[]{"Kurzbeschreibung", "Berichtsfelder", "Nächste Schritte", "Durchgeführte Arbeiten", "Offene Punkte"};
            case CRM: return new String[]{"Notiz", "CRM-Felder", "Nächste Schritte / Wiedervorlage", "Vereinbarungen", "Offen / im CRM aktualisieren"};
            case LESSON: return new String[]{"Worum es geht", "Lernzettel", "Übungen / To-dos", "Merksätze und Begriffe", "Prüfungsfragen"};
            case INTERVIEW: return new String[]{"Überblick", "Wichtigste Aussagen", "Nächste Schritte", "Zitate", "Offene Punkte"};
            case POST: return new String[]{"Kurzfassung", "Kernaussagen", "Handlungsempfehlungen", "Fakten und Zahlen", "Fragen zum Nachdenken"};
            default: return new String[]{"Überblick", "Kernpunkte", "Aufgaben", "Entscheidungen", "Offene Fragen"};
        }
    }

    /** Feldbeschreibung für die Anweisung an Gemini (englisch, eindeutig). */
    public static String fields(String id) {
        switch (clean(id)) {
            case MEETING:
                return "This is a business meeting or customer appointment. overview = 3 to 6 sentences: purpose, participants' roles if clear, outcome; "
                        + "points = topics discussed with the key facts (at most 12); tasks = action items (what, and who = person responsible as named, empty if unclear, "
                        + "include deadlines in 'what' if mentioned); decisions = agreements and decisions; questions = open issues and questions to clarify.";
            case SERVICE:
                return "This is a support call, remote session or on-site service job to be documented as a service report (Servicebericht) "
                        + "for the customer file and for billing. Write factually, precise and technical, no filler. "
                        + "overview = short description for the report: 2 to 5 sentences on the customer's issue or request, the cause if found, and the outcome; "
                        + "points = report fields, each entry exactly in the form 'Field name: value', in this order and only if the information is in the transcript "
                        + "(never guess company or person names, versions, times or prices; use the speaker names as given): "
                        + "Topic (short, max 8 words), Type of service (phone support, remote session, on-site, training, consulting), Company, Contact person, "
                        + "System / product / module (with version if mentioned), Issue / request, Cause, Solution, Status (solved / partially solved / open), "
                        + "Time spent (only if explicitly mentioned), Billable (only if mentioned, e.g. under maintenance contract or billable), "
                        + "Write the field names in the output language (e.g. German: Thema, Einsatzart, Firma, Ansprechpartner, System / Modul, Anliegen, "
                        + "Ursache, Lösung, Status, Zeitaufwand, Abrechnung). "
                        + "decisions = the work actually carried out, as concrete steps in the order they were done (settings changed, data corrected, "
                        + "updates installed, explanations given), max 15; "
                        + "tasks = follow-up actions still to be done (what, including deadline if mentioned; who = person responsible as named, empty if unclear); "
                        + "questions = open points, risks or recommendations for the customer (e.g. backup, update, training).";
            case CRM:
                return "This is a customer contact (call, visit, voice message or meeting) to be logged in a CRM system. Write factually, in third person, no filler. "
                        + "overview = the activity note for the CRM: 3 to 6 sentences on reason for contact, what was discussed and the result; "
                        + "points = CRM fields, each entry exactly in the form 'Field name: value', in this order and only if the information is in the transcript "
                        + "(never guess company or person names; use the speaker names as given): "
                        + "Subject (short, max 8 words), Contact type (phone call, visit, video call, e-mail, voice message), Company, Contact person (name and role), "
                        + "Other persons mentioned (name and role), Reason for contact, Result, Customer satisfaction, Products / systems in use, "
                        + "Interest / sales opportunity (product or service, status), Follow-up date (date or time frame if mentioned), Priority (only if clear). "
                        + "Write the field names in the output language (e.g. German: Betreff, Kontaktart, Firma, Ansprechpartner, Weitere Personen, Anlass, Ergebnis, "
                        + "Zufriedenheit, Eingesetzte Produkte, Interesse / Verkaufschance, Wiedervorlage, Priorität). "
                        + "tasks = next steps and follow-ups (what, including deadline if mentioned; who = person responsible as named, empty if unclear); "
                        + "decisions = agreements made with the customer; "
                        + "questions = open points the customer or team still has to clarify, and master data that should be updated in the CRM "
                        + "(e.g. a contact person who left the company, new responsibilities).";
            case LESSON:
                return "This is a lesson, lecture or training video. overview = 2 to 4 sentences on the topic and learning goals; "
                        + "points = a study sheet: the key learning content as clear, self-contained statements (at most 15); "
                        + "tasks = exercises or recommended actions mentioned (who = empty); decisions = key terms and definitions as 'Term: definition'; "
                        + "questions = 5 to 8 exam-style questions WITH the answer after ' – ', based only on the content.";
            case INTERVIEW:
                return "This is an interview. overview = 3 to 5 sentences: who was interviewed about what, main message; points = the most important statements (at most 12); "
                        + "tasks = next steps or follow-ups (who as named, empty if unclear); decisions = 3 to 6 short verbatim quotes worth keeping (with speaker name); "
                        + "questions = topics not answered or worth following up.";
            case POST:
                return "This is a post, article or document. overview = 2 to 4 sentences summary; points = the key statements (at most 10); "
                        + "tasks = recommended actions for the reader (who = empty); decisions = concrete facts, figures and dates mentioned; "
                        + "questions = 2 to 4 critical questions to think about (e.g. missing evidence).";
            default:
                return "overview = 3 to 6 sentences on what it was about and the outcome; points = key points (at most 12); "
                        + "tasks = action items (what, and who = person responsible as named in the transcript, empty if unclear); "
                        + "decisions = decisions that were made; questions = questions left open.";
        }
    }
}
