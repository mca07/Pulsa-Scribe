package de.mca.pulsascribe.core;

import java.util.Locale;

/** Zeitangaben lesen und formatieren. Alle Werte in Millisekunden. */
public final class Times {
    private Times() {}

    public static final long MAX_MS = 24L * 3600 * 1000; // mehr als 24 h ist unplausibel

    /**
     * Liest "SS", "MM:SS", "H:MM:SS", jeweils optional mit ",mmm" oder ".mmm", sowie "12.5s".
     * Gibt -1 zurück, wenn ungültig.
     */
    public static long parse(String s) {
        if (s == null) return -1;
        String t = s.trim();
        if (t.isEmpty() || t.length() > 16) return -1;
        if (t.endsWith("s") && t.indexOf(':') < 0) t = t.substring(0, t.length() - 1);
        t = t.replace(',', '.');
        String[] p = t.split(":", -1);
        if (p.length > 3) return -1;
        try {
            long total = 0;
            for (int i = 0; i < p.length; i++) {
                String part = p[i];
                if (part.isEmpty()) return -1;
                boolean last = i == p.length - 1;
                if (!last) {
                    if (!part.matches("\\d{1,3}")) return -1;
                    long v = Long.parseLong(part);
                    if (i > 0 && v >= 60) return -1; // Minuten bei H:MM:SS
                    total = total * 60 + v;
                } else {
                    if (!part.matches("\\d{1,6}(\\.\\d{1,3})?")) return -1;
                    double sec = Double.parseDouble(part);
                    if (p.length > 1 && sec >= 60) return -1;
                    long ms = Math.round((total * 60 + sec) * 1000);
                    if (p.length == 1) ms = Math.round(sec * 1000);
                    return ms > MAX_MS ? -1 : ms;
                }
            }
        } catch (NumberFormatException e) {
            return -1;
        }
        return -1;
    }

    /** 75000 → "1:15", 3725000 → "1:02:05". */
    public static String clock(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    /** Für den Prompt: immer "MM:SS" bzw. "H:MM:SS" mit zweistelligen Minuten. */
    public static String mmss(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.ROOT, "%02d:%02d", m, sec);
    }

    /** SRT-Format "HH:MM:SS,mmm". */
    public static String srt(long ms) {
        if (ms < 0) ms = 0;
        long h = ms / 3600000, m = (ms % 3600000) / 60000, s = (ms % 60000) / 1000, r = ms % 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, r);
    }

    /** Lesbare Dauer: "42 Min", "1 Std 5 Min", "35 Sek". */
    public static String duration(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s < 60) return s + " Sek";
        long m = (s + 30) / 60;
        if (m < 60) return m + " Min";
        long h = m / 60, rm = m % 60;
        return rm == 0 ? h + " Std" : h + " Std " + rm + " Min";
    }
}
