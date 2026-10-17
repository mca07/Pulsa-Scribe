package de.mca.pulsascribe.core;

/** Feste Liste der Sprachen. Nur diese Werte gelangen in die Anweisung an Gemini. */
public final class Languages {
    private Languages() {}

    public static final String AUTO = "auto";

    public static final String[] CODES = {AUTO, "de", "en", "es", "fr", "it", "nl", "pl", "pt", "ro", "ru", "tr", "uk"};
    public static final String[] LABELS = {"Automatisch erkennen", "Deutsch", "Englisch", "Spanisch", "Französisch",
            "Italienisch", "Niederländisch", "Polnisch", "Portugiesisch", "Rumänisch", "Russisch", "Türkisch", "Ukrainisch"};
    /** Name der Sprache für den Prompt (englisch, eindeutig für das Modell). */
    private static final String[] PROMPT = {"", "German", "English", "Spanish", "French", "Italian", "Dutch",
            "Polish", "Portuguese", "Romanian", "Russian", "Turkish", "Ukrainian"};

    public static String clean(String code) {
        for (String c : CODES) if (c.equals(code)) return c;
        return AUTO;
    }

    public static int index(String code) {
        String c = clean(code);
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(c)) return i;
        return 0;
    }

    public static String label(String code) {
        return LABELS[index(code)];
    }

    public static String promptName(String code) {
        return PROMPT[index(code)];
    }

    // Sprache der Zusammenfassung
    public static final String SUM_SAME = "same";
    public static final String[] SUM_CODES = {SUM_SAME, "de", "en"};
    public static final String[] SUM_LABELS = {"Wie die Aufnahme", "Deutsch", "Englisch"};

    public static String cleanSummary(String code) {
        for (String c : SUM_CODES) if (c.equals(code)) return c;
        return SUM_SAME;
    }

    public static int summaryIndex(String code) {
        String c = cleanSummary(code);
        for (int i = 0; i < SUM_CODES.length; i++) if (SUM_CODES[i].equals(c)) return i;
        return 0;
    }
}
