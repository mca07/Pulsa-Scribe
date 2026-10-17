package de.mca.pulsascribe.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ein Transkript mit Abschnitten, Sprechernamen, Zusammenfassung und Bearbeitungsstand.
 * Laden geht immer über {@link #fromJson}, das jeden Wert prüft (gespeicherte Daten gelten als fremd).
 */
public final class Transcript {

    public static final String QUEUED = "queued", PREPARE = "prepare", TRANSCRIBE = "transcribe",
            SUMMARY = "summary", DONE = "done", ERROR = "error", CANCELED = "canceled";

    public static final int MAX_SEGMENTS = 20000;
    public static final int MAX_TEXT = 4000;
    public static final int MAX_SPEAKER = 40;
    public static final int MAX_TITLE = 80;
    public static final int MAX_TERMS = 300;
    public static final int MAX_SPEAKERS = 30;
    public static final int MAX_CHUNKS = 200;

    public static final class Segment {
        public long startMs;
        public String speaker; // Bezeichnung aus der Erkennung, z. B. "Sprecher 1"; "" ohne Sprechererkennung
        public String text;

        public Segment(long startMs, String speaker, String text) {
            this.startMs = startMs;
            this.speaker = speaker == null ? "" : speaker;
            this.text = text == null ? "" : text;
        }
    }

    public static final class Task {
        public String what, who;

        public Task(String what, String who) {
            this.what = what == null ? "" : what;
            this.who = who == null ? "" : who;
        }
    }

    public static final class Summary {
        public String overview = "";
        public final List<String> points = new ArrayList<>();
        public final List<Task> tasks = new ArrayList<>();
        public final List<String> decisions = new ArrayList<>();
        public final List<String> questions = new ArrayList<>();

        public boolean isEmpty() {
            return overview.isEmpty() && points.isEmpty() && tasks.isEmpty() && decisions.isEmpty() && questions.isEmpty();
        }
    }

    public String id;
    public String title = "Transkript";
    public long createdMs;
    public long durationMs;
    public String source = "";
    public String sourceUri = ""; // content://-Adresse mit dauerhafter Freigabe (nur für erneute Versuche)
    public String language = Languages.AUTO;
    public boolean diarize = true;
    public boolean summarize = true;
    public boolean cleanFillers = true;
    public String terms = "";
    public String status = QUEUED;
    public int progress;          // 0–100 im aktuellen Schritt
    public String error = "";
    public boolean audioReady;    // Tonspur vollständig aufbereitet
    public int[] cuts = new int[0]; // Startrahmen der Teile
    public int chunksDone;
    public String model = "";
    public boolean incomplete;    // ein Teil fehlt (Text wurde trotzdem gespeichert)
    public final List<Segment> segments = new ArrayList<>();
    public final Map<String, String> speakerNames = new LinkedHashMap<>();
    public Summary summary;

    public boolean isWorking() {
        return QUEUED.equals(status) || PREPARE.equals(status) || TRANSCRIBE.equals(status) || SUMMARY.equals(status);
    }

    /** Anzeigename eines Sprechers (umbenannt oder wie erkannt). */
    public String speakerName(String label) {
        if (label == null || label.isEmpty()) return "";
        String n = speakerNames.get(label);
        return n == null || n.isEmpty() ? label : n;
    }

    /** Sprecherbezeichnungen in der Reihenfolge des ersten Auftretens. */
    public List<String> speakers() {
        List<String> out = new ArrayList<>();
        for (Segment s : segments) {
            if (!s.speaker.isEmpty() && !out.contains(s.speaker)) out.add(s.speaker);
        }
        return out;
    }

    public int chunks() {
        return Math.max(1, cuts.length);
    }

    /** Sortiert stabil nach Startzeit. */
    public void sortSegments() {
        List<Segment> copy = new ArrayList<>(segments);
        Collections.sort(copy, (a, b) -> Long.compare(a.startMs, b.startMs));
        segments.clear();
        segments.addAll(copy);
    }

    // ------------------------------------------------------------------ Speichern

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1);
        m.put("id", id);
        m.put("title", title);
        m.put("created", createdMs);
        m.put("duration", durationMs);
        m.put("source", source);
        m.put("src", sourceUri);
        m.put("language", language);
        m.put("diarize", diarize);
        m.put("summarize", summarize);
        m.put("clean", cleanFillers);
        m.put("terms", terms);
        m.put("status", status);
        m.put("progress", progress);
        m.put("error", error);
        m.put("audioReady", audioReady);
        List<Object> c = new ArrayList<>();
        for (int x : cuts) c.add(x);
        m.put("cuts", c);
        m.put("chunksDone", chunksDone);
        m.put("model", model);
        m.put("incomplete", incomplete);
        List<Object> segs = new ArrayList<>();
        for (Segment s : segments) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("t", s.startMs);
            o.put("s", s.speaker);
            o.put("x", s.text);
            segs.add(o);
        }
        m.put("segments", segs);
        m.put("speakers", new LinkedHashMap<>(speakerNames));
        if (summary != null) m.put("summary", summaryToMap(summary));
        return Json.write(m);
    }

    static Map<String, Object> summaryToMap(Summary s) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("overview", s.overview);
        o.put("points", new ArrayList<Object>(s.points));
        List<Object> t = new ArrayList<>();
        for (Task k : s.tasks) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("what", k.what);
            x.put("who", k.who);
            t.add(x);
        }
        o.put("tasks", t);
        o.put("decisions", new ArrayList<Object>(s.decisions));
        o.put("questions", new ArrayList<Object>(s.questions));
        return o;
    }

    /** Lädt und prüft. Gibt null zurück, wenn Grunddaten fehlen oder ungültig sind. */
    public static Transcript fromJson(String json) {
        Map<String, Object> m;
        try {
            m = Json.obj(Json.parse(json));
        } catch (Json.ParseException e) {
            return null;
        }
        if (m == null) return null;
        Transcript t = new Transcript();
        t.id = Json.str(m, "id");
        if (!Text.isId(t.id)) return null;
        String title = Text.line(Json.str(m, "title"), MAX_TITLE);
        t.title = title.isEmpty() ? "Transkript" : title;
        long now = System.currentTimeMillis();
        t.createdMs = clamp(Json.num(m, "created", 0), 0, now + 86_400_000L);
        t.durationMs = clamp(Json.num(m, "duration", 0), 0, Times.MAX_MS);
        t.source = Text.line(Json.str(m, "source"), 120);
        t.sourceUri = cleanUri(Json.str(m, "src"));
        t.language = Languages.clean(Json.str(m, "language"));
        t.diarize = Json.bool(m, "diarize", true);
        t.summarize = Json.bool(m, "summarize", true);
        t.cleanFillers = Json.bool(m, "clean", true);
        t.terms = Text.line(Json.str(m, "terms"), MAX_TERMS);
        String st = Json.str(m, "status");
        t.status = isStatus(st) ? st : ERROR;
        t.progress = (int) clamp(Json.num(m, "progress", 0), 0, 100);
        t.error = Text.line(Json.str(m, "error"), 300);
        t.audioReady = Json.bool(m, "audioReady", false);
        List<Object> c = Json.arr(m, "cuts");
        if (c != null && c.size() <= MAX_CHUNKS) {
            int[] cuts = new int[c.size()];
            int prev = -1;
            boolean ok = true;
            for (int i = 0; i < cuts.length; i++) {
                Object v = c.get(i);
                if (!(v instanceof Double)) { ok = false; break; }
                double d = (Double) v;
                if (d < 0 || d > 100_000_000 || d != Math.rint(d) || (int) d <= prev || (i == 0 && d != 0)) { ok = false; break; }
                cuts[i] = (int) d;
                prev = cuts[i];
            }
            if (ok) t.cuts = cuts;
        }
        t.chunksDone = (int) clamp(Json.num(m, "chunksDone", 0), 0, t.cuts.length);
        String model = Json.str(m, "model");
        t.model = model != null && model.matches("[a-z0-9.\\-]{1,48}") ? model : "";
        t.incomplete = Json.bool(m, "incomplete", false);
        List<Object> segs = Json.arr(m, "segments");
        if (segs != null) {
            for (Object o : segs) {
                if (t.segments.size() >= MAX_SEGMENTS) break;
                Map<String, Object> s = Json.obj(o);
                if (s == null) continue;
                long ms = Json.num(s, "t", -1);
                if (ms < 0 || ms > Times.MAX_MS) continue;
                String x = Text.block(Json.str(s, "x"), MAX_TEXT);
                if (x.isEmpty()) continue;
                t.segments.add(new Segment(ms, Text.line(Json.str(s, "s"), MAX_SPEAKER), x));
            }
            t.sortSegments();
        }
        Map<String, Object> sp = Json.obj(m, "speakers");
        if (sp != null) {
            for (Map.Entry<String, Object> e : sp.entrySet()) {
                if (t.speakerNames.size() >= MAX_SPEAKERS) break;
                String k = Text.line(e.getKey(), MAX_SPEAKER);
                String v = e.getValue() instanceof String ? Text.line((String) e.getValue(), MAX_SPEAKER) : "";
                if (!k.isEmpty() && !v.isEmpty()) t.speakerNames.put(k, v);
            }
        }
        Map<String, Object> sum = Json.obj(m, "summary");
        if (sum != null) {
            Summary s = summaryFromMap(sum);
            if (s != null && !s.isEmpty()) t.summary = s;
        }
        // Ein unterbrochener Lauf (App beendet) bleibt nicht als "läuft" stehen – das setzt der Store.
        return t;
    }

    /** Prüft eine Zusammenfassung (gespeichert oder von Gemini). */
    public static Summary summaryFromMap(Map<String, Object> m) {
        if (m == null) return null;
        Summary s = new Summary();
        s.overview = Text.block(Json.str(m, "overview"), 3000);
        addLines(s.points, Json.arr(m, "points"), 40, 600);
        addLines(s.decisions, Json.arr(m, "decisions"), 30, 600);
        addLines(s.questions, Json.arr(m, "questions"), 30, 600);
        List<Object> tasks = Json.arr(m, "tasks");
        if (tasks != null) {
            for (Object o : tasks) {
                if (s.tasks.size() >= 40) break;
                Map<String, Object> k = Json.obj(o);
                if (k == null) continue;
                String what = Text.line(Json.str(k, "what"), 400);
                if (what.isEmpty()) continue;
                s.tasks.add(new Task(what, Text.line(Json.str(k, "who"), 60)));
            }
        }
        return s;
    }

    private static void addLines(List<String> out, List<Object> in, int maxItems, int maxLen) {
        if (in == null) return;
        for (Object o : in) {
            if (out.size() >= maxItems) break;
            if (!(o instanceof String)) continue;
            String l = Text.line((String) o, maxLen);
            if (!l.isEmpty()) out.add(l);
        }
    }

    /** Nur content://-Adressen ohne Steuer- und Leerzeichen, max. 2000 Zeichen. */
    public static String cleanUri(String u) {
        if (u == null || u.length() > 2000 || !u.startsWith("content://")) return "";
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c <= 0x20 || c >= 0x7f) return "";
        }
        return u;
    }

    static boolean isStatus(String s) {
        return QUEUED.equals(s) || PREPARE.equals(s) || TRANSCRIBE.equals(s) || SUMMARY.equals(s)
                || DONE.equals(s) || ERROR.equals(s) || CANCELED.equals(s);
    }

    static long clamp(long v, long lo, long hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
