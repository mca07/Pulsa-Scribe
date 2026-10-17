package de.mca.pulsascribe.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Export als Text, SRT-Untertitel und Word (.docx). PDF baut die App mit Android-Mitteln. */
public final class Export {
    private Export() {}

    public static final class Options {
        public boolean times = true;
        public boolean speakers = true;
        public boolean summary = true;
    }

    /** Zeilen für alle Ausgaben, die einem Lesetext folgen (TXT, Word, PDF). */
    public static final class Block {
        public final int kind; // 0 Überschrift, 1 Unterüberschrift, 2 Absatz, 3 Aufzählung, 4 Transkriptzeile, 5 Meta
        public final String time, speaker, text;

        Block(int kind, String time, String speaker, String text) {
            this.kind = kind;
            this.time = time;
            this.speaker = speaker;
            this.text = text;
        }
    }

    public static final int H1 = 0, H2 = 1, PARA = 2, BULLET = 3, LINE = 4, META = 5;

    public static String meta(Transcript t) {
        String date = new SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY).format(new Date(t.createdMs));
        StringBuilder b = new StringBuilder(date);
        if (t.durationMs > 0) b.append(" · ").append(Times.duration(t.durationMs));
        int sp = t.speakers().size();
        if (sp > 0) b.append(" · ").append(sp).append(sp == 1 ? " Sprecher" : " Sprecher");
        return b.toString();
    }

    public static List<Block> blocks(Transcript t, Options o) {
        List<Block> out = new ArrayList<>();
        out.add(new Block(H1, "", "", t.title));
        out.add(new Block(META, "", "", meta(t)));
        if (o.summary && t.summary != null && !t.summary.isEmpty()) {
            Transcript.Summary s = t.summary;
            String[] h = Templates.headings(t.template);
            out.add(new Block(H2, "", "", "Zusammenfassung"));
            if (!s.overview.isEmpty()) out.add(new Block(PARA, "", "", s.overview));
            list(out, h[1], s.points);
            if (!s.tasks.isEmpty()) {
                out.add(new Block(H2, "", "", h[2]));
                for (Transcript.Task k : s.tasks) {
                    out.add(new Block(BULLET, "", "", k.who.isEmpty() ? k.what : k.what + " (" + k.who + ")"));
                }
            }
            list(out, h[3], s.decisions);
            list(out, h[4], s.questions);
            out.add(new Block(H2, "", "", t.isAudio() ? "Transkript" : "Text"));
        }
        String lastSpeaker = null;
        for (Transcript.Segment s : t.segments) {
            String name = o.speakers ? t.speakerName(s.speaker) : "";
            // Sprecher nur nennen, wenn er wechselt (lesbarer), Zeit immer
            String show = name.equals(lastSpeaker) && !o.times ? "" : name;
            out.add(new Block(LINE, o.times && t.isAudio() ? Times.clock(s.startMs) : "", show, s.text));
            lastSpeaker = name;
        }
        return out;
    }

    private static void list(List<Block> out, String title, List<String> items) {
        if (items.isEmpty()) return;
        out.add(new Block(H2, "", "", title));
        for (String i : items) out.add(new Block(BULLET, "", "", i));
    }

    // ------------------------------------------------------------------ Text

    public static String txt(Transcript t, Options o) {
        StringBuilder b = new StringBuilder();
        for (Block k : blocks(t, o)) {
            switch (k.kind) {
                case H1: b.append(k.text).append('\n'); break;
                case META: b.append(k.text).append("\n\n"); break;
                case H2: b.append('\n').append(k.text.toUpperCase(Locale.GERMANY)).append('\n'); break;
                case PARA: b.append(k.text).append('\n'); break;
                case BULLET: b.append("• ").append(k.text).append('\n'); break;
                default:
                    if (!k.time.isEmpty()) b.append('[').append(k.time).append("] ");
                    if (!k.speaker.isEmpty()) b.append(k.speaker).append(": ");
                    b.append(k.text).append('\n');
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ SRT

    /** Untertitel: lange Abschnitte werden in Stücke von max. ~84 Zeichen bzw. 7 s zerlegt. */
    public static String srt(Transcript t, boolean speakers) {
        StringBuilder b = new StringBuilder();
        int n = 1;
        List<Transcript.Segment> segs = t.segments;
        for (int i = 0; i < segs.size(); i++) {
            Transcript.Segment s = segs.get(i);
            long start = s.startMs;
            long end = i + 1 < segs.size() ? segs.get(i + 1).startMs : (t.durationMs > start ? t.durationMs : start + estimate(s.text));
            if (end <= start) end = start + estimate(s.text);
            end = Math.min(end, start + Math.max(estimate(s.text), 1000) + 2000);
            String name = speakers ? t.speakerName(s.speaker) : "";
            List<String> pieces = split(s.text, 84);
            long span = end - start;
            int total = 0;
            for (String p : pieces) total += p.length();
            long cursor = start;
            for (int k = 0; k < pieces.size(); k++) {
                String p = pieces.get(k);
                long d = total == 0 ? span : span * p.length() / total;
                long e = k == pieces.size() - 1 ? end : cursor + Math.max(d, 700);
                b.append(n++).append('\n');
                b.append(Times.srt(cursor)).append(" --> ").append(Times.srt(e)).append('\n');
                if (k == 0 && !name.isEmpty()) b.append(name).append(": ");
                b.append(p).append("\n\n");
                cursor = e;
            }
        }
        return b.toString();
    }

    /** Sprechdauer grob: ~15 Zeichen pro Sekunde. */
    static long estimate(String text) {
        return Math.max(1200, text.length() * 1000L / 15);
    }

    static List<String> split(String text, int max) {
        List<String> out = new ArrayList<>();
        String rest = text.replace('\n', ' ').trim();
        while (rest.length() > max) {
            int cut = -1;
            for (int i = max; i > max / 2; i--) {
                char c = rest.charAt(i);
                if (c == '.' || c == '!' || c == '?' || c == ',' || c == ';') { cut = i + 1; break; }
            }
            if (cut < 0) cut = rest.lastIndexOf(' ', max);
            if (cut <= 0) cut = max;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    // ------------------------------------------------------------------ Vorlesen

    /** Teilt Text für die Sprachausgabe an Satzgrenzen in Stücke von höchstens max Zeichen. */
    public static List<String> splitForSpeech(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String rest = text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        while (rest.length() > max) {
            int cut = -1;
            for (int i = max - 1; i > max / 3; i--) {
                char c = rest.charAt(i);
                if (c == '.' || c == '!' || c == '?' || c == ';' || c == ':') { cut = i + 1; break; }
            }
            if (cut < 0) cut = rest.lastIndexOf(' ', max - 1);
            if (cut <= 0) cut = max;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    /** Zusammenfassung als Vorlesetext mit Überschriften der Vorlage. */
    public static List<String> speechSummary(Transcript t, Transcript.Summary s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        String[] h = Templates.headings(t.template);
        out.add(t.title + ".");
        if (!s.overview.isEmpty()) out.add(s.overview);
        speechList(out, h[1], s.points);
        if (!s.tasks.isEmpty()) {
            List<String> l = new ArrayList<>();
            for (Transcript.Task k : s.tasks) l.add(k.who.isEmpty() ? k.what : k.what + ", zuständig: " + k.who);
            speechList(out, h[2], l);
        }
        speechList(out, h[3], s.decisions);
        speechList(out, h[4], s.questions);
        return out;
    }

    private static void speechList(List<String> out, String heading, List<String> items) {
        if (items.isEmpty()) return;
        out.add(heading + ":");
        for (String i : items) out.add(i.endsWith(".") || i.endsWith("?") || i.endsWith("!") ? i : i + ".");
    }

    // ------------------------------------------------------------------ Word

    public static byte[] docx(Transcript t, Options o) throws IOException {
        StringBuilder d = new StringBuilder();
        d.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        d.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
        for (Block k : blocks(t, o)) {
            switch (k.kind) {
                case H1: para(d, run(k.text, true, 36, null), 120); break;
                case META: para(d, run(k.text, false, 20, "6B7280"), 240); break;
                case H2: para(d, run(k.text, true, 26, null), 80); break;
                case PARA: para(d, run(k.text, false, 22, null), 120); break;
                case BULLET: para(d, run("•  " + k.text, false, 22, null), 60); break;
                default: {
                    StringBuilder r = new StringBuilder();
                    if (!k.time.isEmpty()) r.append(run(k.time + "   ", false, 18, "6B7280"));
                    if (!k.speaker.isEmpty()) r.append(run(k.speaker + ": ", true, 22, null));
                    r.append(run(k.text, false, 22, null));
                    para(d, r.toString(), 100);
                }
            }
        }
        d.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"709\" w:footer=\"709\" w:gutter=\"0\"/></w:sectPr>");
        d.append("</w:body></w:document>");

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            entry(z, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "</Types>");
            entry(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                    + "</Relationships>");
            entry(z, "word/document.xml", d.toString());
        }
        return bos.toByteArray();
    }

    private static void entry(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static void para(StringBuilder d, String runs, int after) {
        d.append("<w:p><w:pPr><w:spacing w:after=\"").append(after).append("\"/></w:pPr>").append(runs).append("</w:p>");
    }

    private static String run(String text, boolean bold, int halfPoints, String color) {
        StringBuilder r = new StringBuilder("<w:r><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/>");
        if (bold) r.append("<w:b/>");
        if (color != null) r.append("<w:color w:val=\"").append(color).append("\"/>");
        r.append("<w:sz w:val=\"").append(halfPoints).append("\"/></w:rPr>");
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) r.append("<w:br/>");
            r.append("<w:t xml:space=\"preserve\">").append(xml(lines[i])).append("</w:t>");
        }
        r.append("</w:r>");
        return r.toString();
    }

    /** XML-sicher: Sonderzeichen kodiert, in XML 1.0 verbotene Zeichen entfernt. */
    public static String xml(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                default:
                    if (c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD)) b.append(c);
                    else if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        b.append(c).append(s.charAt(++i));
                    }
            }
        }
        return b.toString();
    }
}
