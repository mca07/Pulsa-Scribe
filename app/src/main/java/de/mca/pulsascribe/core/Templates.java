package de.mca.pulsascribe.core;

/** Vorlagen für die Zusammenfassung: feste Liste, eigene Anweisung und eigene Überschriften. */
public final class Templates {
    private Templates() {}

    public static final String STANDARD = "standard", MEETING = "meeting", LESSON = "lesson", INTERVIEW = "interview", POST = "post";

    public static final String[] IDS = {STANDARD, MEETING, LESSON, INTERVIEW, POST};
    public static final String[] LABELS = {"Allgemein", "Kundentermin / Besprechung", "Kurs-Lektion (Lernzettel)", "Interview", "Beitrag / Artikel"};

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
