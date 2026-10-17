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

    // Häufige kurze Wörter je Sprache (zur Erkennung der Sprache eines Textes)
    private static final String[][] STOP = {
            {"de", "und", "der", "die", "das", "ist", "nicht", "ich", "wir", "sie", "mit", "auf", "für", "ein", "eine", "auch", "dass", "es", "zu", "den", "von", "haben", "wird", "aber", "noch"},
            {"en", "the", "and", "is", "to", "of", "that", "it", "you", "we", "for", "with", "this", "are", "be", "have", "not", "on", "was", "they", "what", "can"},
            {"es", "el", "la", "que", "de", "y", "los", "las", "es", "en", "un", "una", "por", "para", "con", "no", "pero", "muy", "este", "esta"},
            {"fr", "le", "la", "les", "et", "est", "que", "des", "une", "pour", "pas", "dans", "nous", "vous", "avec", "sur", "mais", "très", "cette"},
            {"it", "il", "che", "di", "e", "la", "non", "per", "una", "sono", "con", "anche", "questo", "della", "molto", "ma", "gli"},
            {"nl", "de", "het", "een", "en", "is", "dat", "niet", "ik", "we", "voor", "met", "op", "ook", "maar", "zijn", "van"},
            {"pl", "i", "w", "nie", "na", "to", "jest", "się", "że", "z", "do", "jak", "ale", "co", "tak", "czy"},
            {"pt", "o", "a", "que", "de", "e", "não", "um", "uma", "para", "com", "os", "as", "mas", "muito", "isso", "está"},
            {"ro", "și", "este", "nu", "în", "că", "de", "la", "cu", "pe", "o", "un", "care", "mai", "pentru", "sunt"},
            {"tr", "ve", "bir", "bu", "da", "de", "için", "ile", "çok", "ama", "ne", "var", "yok", "olarak", "gibi"},
    };

    /** Erkennt die Sprache eines Textes grob über häufige Wörter. "" = unsicher. */
    public static String detect(String text) {
        if (text == null || text.isEmpty()) return "";
        String sample = text.length() > 6000 ? text.substring(0, 6000) : text;
        String[] words = sample.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}]+");
        if (words.length < 5) return "";
        java.util.Map<String, Integer> score = new java.util.HashMap<>();
        for (String[] lang : STOP) {
            java.util.Set<String> set = new java.util.HashSet<>(java.util.Arrays.asList(lang).subList(1, lang.length));
            int n = 0;
            for (String w : words) if (set.contains(w)) n++;
            score.put(lang[0], n);
        }
        String best = "";
        int bestN = 0, second = 0;
        for (java.util.Map.Entry<String, Integer> e : score.entrySet()) {
            if (e.getValue() > bestN) { second = bestN; bestN = e.getValue(); best = e.getKey(); }
            else if (e.getValue() > second) second = e.getValue();
        }
        // Nur bei klarem Vorsprung und genug Treffern
        return bestN >= 3 && bestN >= second * 1.3 ? best : "";
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
