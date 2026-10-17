package de.mca.pulsascribe.core;

import java.util.regex.Pattern;

/** Bereinigt fremde Texte: Steuer- und Bidi-Zeichen raus, Länge begrenzt. */
public final class Text {
    private Text() {}

    /** Einzeilig: alle Steuerzeichen (auch Zeilenumbrüche) werden zu Leerzeichen. */
    public static String line(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(Math.min(s.length(), max + 8));
        boolean space = false;
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            if (isBidi(c) || c == '\uFEFF' || c == '\u200B') continue;
            boolean breakLike = c == '\n' || c == '\r' || c == '\t' || c == '\u2028' || c == '\u2029';
            if (Character.isISOControl(c) && !breakLike) continue; // sonstige Steuerzeichen entfallen
            if (breakLike || Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                if (!space && b.length() > 0) { b.append(' '); space = true; }
                continue;
            }
            if (Character.isSurrogate(c)) {
                // Nur vollständige Paare übernehmen
                if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    if (b.length() + 2 > max) break;
                    b.append(c).append(s.charAt(++i));
                    space = false;
                }
                continue;
            }
            b.append(c);
            space = false;
        }
        return b.toString().trim();
    }

    /** Mehrzeilig: Zeilenumbrüche bleiben (höchstens zwei hintereinander), sonst wie {@link #line}. */
    public static String block(String s, int max) {
        if (s == null) return "";
        String[] parts = s.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder b = new StringBuilder();
        int empty = 0;
        for (String p : parts) {
            String l = line(p, max);
            if (l.isEmpty()) {
                if (++empty > 1 || b.length() == 0) continue;
            } else {
                empty = 0;
            }
            if (b.length() > 0) b.append('\n');
            b.append(l);
            if (b.length() >= max) break;
        }
        String r = b.length() > max ? b.substring(0, max) : b.toString();
        return r.trim();
    }

    static boolean isBidi(char c) {
        return (c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069')
                || c == '\u200E' || c == '\u200F' || c == '\u061C';
    }

    private static final Pattern ID = Pattern.compile("[a-z0-9]{8,32}");

    public static boolean isId(String s) {
        return s != null && ID.matcher(s).matches();
    }

    /** Dateiname für Export: nur Buchstaben, Ziffern, Leer, - _ . ( ) ; max. 60 Zeichen; nie leer. */
    public static String fileName(String title) {
        String t = line(title, 120);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length() && b.length() < 60; i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '(' || c == ')') b.append(c);
            else if (c == '.' || c == ',' || c == ':' || c == '/' || c == '\\') b.append('-');
        }
        String r = b.toString().trim().replaceAll(" +", " ").replaceAll("-{2,}", "-");
        while (r.startsWith("-") || r.startsWith(".")) r = r.substring(1);
        return r.isEmpty() ? "Transkript" : r;
    }

    /** Titel aus einem Dateinamen: Endung weg, Unterstriche zu Leerzeichen. */
    public static String titleFromFileName(String name) {
        String n = line(name, 200);
        int dot = n.lastIndexOf('.');
        if (dot > 0 && n.length() - dot <= 6) n = n.substring(0, dot);
        n = n.replace('_', ' ').trim();
        n = line(n, 80);
        return n.isEmpty() ? "Transkript" : n;
    }
}
