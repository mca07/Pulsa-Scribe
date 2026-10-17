package de.mca.pulsascribe.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kleiner, strenger JSON-Leser und -Schreiber ohne Abhängigkeiten.
 * Objekte werden zu {@link Map}, Arrays zu {@link List}, Zahlen zu {@link Double},
 * dazu String, Boolean und null. Tiefe und Länge sind begrenzt (Schutz vor manipulierten Daten).
 */
public final class Json {
    private Json() {}

    public static final int MAX_DEPTH = 32;

    public static final class ParseException extends Exception {
        public ParseException(String m) { super(m); }
    }

    // ------------------------------------------------------------------ Lesen

    public static Object parse(String s) throws ParseException {
        if (s == null) throw new ParseException("null");
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != s.length()) throw new ParseException("Zeichen nach dem Ende");
        return v;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) { this.s = s; }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                else break;
            }
        }

        Object value(int depth) throws ParseException {
            if (depth > MAX_DEPTH) throw new ParseException("zu tief verschachtelt");
            if (i >= s.length()) throw new ParseException("unerwartetes Ende");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object(depth);
                case '[': return array(depth);
                case '"': return string();
                case 't': word("true"); return Boolean.TRUE;
                case 'f': word("false"); return Boolean.FALSE;
                case 'n': word("null"); return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return number();
                    throw new ParseException("unerwartetes Zeichen");
            }
        }

        void word(String w) throws ParseException {
            if (!s.startsWith(w, i)) throw new ParseException("ungültiger Wert");
            i += w.length();
        }

        Map<String, Object> object(int depth) throws ParseException {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw new ParseException("Schlüssel erwartet");
                String k = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw new ParseException("':' erwartet");
                i++;
                ws();
                m.put(k, value(depth + 1));
                ws();
                if (i >= s.length()) throw new ParseException("unerwartetes Ende");
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new ParseException("',' erwartet");
            }
        }

        List<Object> array(int depth) throws ParseException {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                if (i >= s.length()) throw new ParseException("unerwartetes Ende");
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw new ParseException("',' erwartet");
            }
        }

        String string() throws ParseException {
            i++; // "
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new ParseException("Text nicht beendet");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw new ParseException("Text nicht beendet");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw new ParseException("ungültiges \\u");
                            int cp = 0;
                            for (int k = 0; k < 4; k++) {
                                int d = Character.digit(s.charAt(i++), 16);
                                if (d < 0) throw new ParseException("ungültiges \\u");
                                cp = cp * 16 + d;
                            }
                            b.append((char) cp);
                            break;
                        default: throw new ParseException("ungültiges Escape");
                    }
                } else if (c < 0x20) {
                    throw new ParseException("Steuerzeichen im Text");
                } else {
                    b.append(c);
                }
            }
        }

        Double number() throws ParseException {
            int start = i;
            if (s.charAt(i) == '-') i++;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++;
                else break;
            }
            if (i - start > 40) throw new ParseException("Zahl zu lang");
            try {
                double d = Double.parseDouble(s.substring(start, i));
                if (Double.isNaN(d) || Double.isInfinite(d)) throw new ParseException("ungültige Zahl");
                return d;
            } catch (NumberFormatException e) {
                throw new ParseException("ungültige Zahl");
            }
        }
    }

    // ------------------------------------------------------------------ Zugriff (typsicher, nie null-Fehler)

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static Map<String, Object> obj(Map<String, Object> m, String k) {
        return m == null ? null : obj(m.get(k));
    }

    public static List<Object> arr(Map<String, Object> m, String k) {
        return m == null ? null : arr(m.get(k));
    }

    public static String str(Map<String, Object> m, String k) {
        if (m == null) return null;
        Object v = m.get(k);
        return v instanceof String ? (String) v : null;
    }

    /** Zahl als long, wenn vorhanden, endlich und ganzzahlig darstellbar; sonst def. */
    public static long num(Map<String, Object> m, String k, long def) {
        if (m == null) return def;
        Object v = m.get(k);
        if (!(v instanceof Double)) return def;
        double d = (Double) v;
        if (d > 9.0e15 || d < -9.0e15) return def;
        return (long) d;
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        if (m == null) return def;
        Object v = m.get(k);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    // ------------------------------------------------------------------ Schreiben

    public static String quote(String s) {
        StringBuilder b = new StringBuilder(s == null ? 4 : s.length() + 8);
        quoteTo(b, s);
        return b.toString();
    }

    public static void quoteTo(StringBuilder b, String s) {
        if (s == null) { b.append("null"); return; }
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }

    /** Schreibt Map/List/String/Number/Boolean/null als JSON. */
    public static String write(Object o) {
        StringBuilder b = new StringBuilder();
        writeTo(b, o, 0);
        return b.toString();
    }

    private static void writeTo(StringBuilder b, Object o, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("zu tief");
        if (o == null) b.append("null");
        else if (o instanceof String) quoteTo(b, (String) o);
        else if (o instanceof Boolean) b.append(o.toString());
        else if (o instanceof Integer || o instanceof Long) b.append(o.toString());
        else if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("0");
            else if (d == Math.rint(d) && Math.abs(d) < 9.0e15) b.append((long) d);
            else b.append(d);
        } else if (o instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quoteTo(b, String.valueOf(e.getKey()));
                b.append(':');
                writeTo(b, e.getValue(), depth + 1);
            }
            b.append('}');
        } else if (o instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object v : (List<?>) o) {
                if (!first) b.append(',');
                first = false;
                writeTo(b, v, depth + 1);
            }
            b.append(']');
        } else {
            quoteTo(b, o.toString());
        }
    }
}
