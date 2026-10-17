package de.mca.pulsascribe.core;

import java.util.Locale;

/** Suche über Titel, Schlagwörter, Text, Zusammenfassung und Antworten. "#wort" sucht nur Schlagwörter. */
public final class Search {
    private Search() {}

    public static final class Hit {
        public final String snippet;  // Ausschnitt um den Treffer ("" bei Titel/Schlagwort)
        public final long timeMs;     // Zeit des Abschnitts, -1 wenn nicht aus dem Transkript

        Hit(String snippet, long timeMs) {
            this.snippet = snippet;
            this.timeMs = timeMs;
        }
    }

    public static String normalize(String q) {
        return Text.line(q, 60).toLowerCase(Locale.GERMANY);
    }

    /** Null, wenn kein Treffer. q bereits normalisiert, nicht leer. */
    public static Hit find(Transcript t, String q) {
        if (q == null || q.isEmpty()) return null;
        if (q.startsWith("#")) {
            String tag = q.substring(1).trim();
            if (tag.isEmpty()) return null;
            for (String x : t.tags) if (x.toLowerCase(Locale.GERMANY).contains(tag)) return new Hit("", -1);
            return null;
        }
        if (t.title.toLowerCase(Locale.GERMANY).contains(q)) return new Hit("", -1);
        for (String x : t.tags) if (x.toLowerCase(Locale.GERMANY).contains(q)) return new Hit("", -1);
        for (Transcript.Segment s : t.segments) {
            String snip = snippet(s.text, q);
            if (snip != null) return new Hit(snip, t.isAudio() ? s.startMs : -1);
        }
        if (t.summary != null) {
            String snip = snippet(t.summary.overview, q);
            if (snip != null) return new Hit(snip, -1);
            for (String p : t.summary.points) {
                snip = snippet(p, q);
                if (snip != null) return new Hit(snip, -1);
            }
        }
        for (Transcript.Answer a : t.answers) {
            String snip = snippet(a.answer, q);
            if (snip != null) return new Hit(snip, -1);
        }
        return null;
    }

    /** Ausschnitt von ca. 120 Zeichen um den ersten Treffer, mit … an den Rändern. */
    public static String snippet(String text, String q) {
        if (text == null) return null;
        int at = text.toLowerCase(Locale.GERMANY).indexOf(q);
        if (at < 0) return null;
        int from = Math.max(0, at - 50), to = Math.min(text.length(), at + q.length() + 70);
        if (from > 0) {
            int sp = text.indexOf(' ', from);
            if (sp > 0 && sp < at) from = sp + 1;
        }
        String s = text.substring(from, to).replace('\n', ' ');
        return (from > 0 ? "…" : "") + s + (to < text.length() ? "…" : "");
    }
}
