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

    public static final String RECORDING = "recording";
    public static final String QUEUED = "queued", PREPARE = "prepare", TRANSCRIBE = "transcribe",
            SUMMARY = "summary", DONE = "done", ERROR = "error", CANCELED = "canceled";
    /** Automatisch übernommenes Telefonat, wartet auf „Transkribieren“. */
    public static final String WAITING = "waiting";

    public static final int MAX_SEGMENTS = 20000;
    public static final int MAX_TEXT = 4000;
    public static final int MAX_SPEAKER = 40;
    public static final int MAX_TITLE = 80;
    public static final int MAX_TERMS = 300;
    public static final int MAX_SPEAKERS = 30;
    public static final int MAX_CHUNKS = 200;
    public static final int MAX_TAGS = 10;
    public static final int MAX_TAG = 30;
    public static final int MAX_QA = 50;
    public static final int MAX_TRANSLATIONS = 3;

    /** Art der Quelle. */
    public static final String KIND_AUDIO = "audio", KIND_TEXT = "text", KIND_IMAGE = "image", KIND_WEB = "web", KIND_PDF = "pdf";

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
        /** Abgehakt (in der App oder in Todoist). */
        public boolean done;
        /** ID der verknüpften Todoist-Aufgabe, "" = nicht verknüpft. */
        public String remote = "";
        /** Zuletzt bekannter Stand in Todoist (zum Abgleich). */
        public boolean remoteDone;

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

        /**
         * Übernimmt Haken und Todoist-Verknüpfung aus einem älteren Stand für Aufgaben mit gleichem Text
         * (neue Zusammenfassung, gleichzeitige Änderung durch den Dienst).
         */
        public void carryTaskState(Summary from) {
            if (from == null) return;
            List<Task> pool = new ArrayList<>(from.tasks);
            for (Task k : tasks) {
                for (int i = 0; i < pool.size(); i++) {
                    Task o = pool.get(i);
                    if (o.what.equals(k.what)) {
                        k.done = o.done;
                        k.remote = o.remote;
                        k.remoteDone = o.remoteDone;
                        pool.remove(i);
                        break;
                    }
                }
            }
        }

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
    /** Mehrere Aufnahmen zu einem Eintrag zusammengeführt (Reihenfolge = Abspielreihenfolge). */
    public final List<Part> parts = new ArrayList<>();
    public static final int MAX_PARTS = 20;

    public static final class Part {
        public String name, uri;
        public long startMs;

        public Part(String name, String uri, long startMs) {
            this.name = name == null ? "" : name;
            this.uri = uri == null ? "" : uri;
            this.startMs = startMs;
        }
    }
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
    public boolean recorded;      // in der App aufgenommen (nicht aus einer Datei)
    public final List<Segment> segments = new ArrayList<>();
    public final Map<String, String> speakerNames = new LinkedHashMap<>();
    public Summary summary;
    public String kind = KIND_AUDIO;
    /** Webseite oder YouTube-Video (nur bei KIND_WEB), geprüft über WebPage.cleanUrl. */
    public String url = "";
    public String folder = "";                 // Ordner-ID, "" = oberste Ebene
    public boolean aggregate;
    public static final String ENGINE_GEMINI = "gemini", ENGINE_WHISPER = "whisper";
    public String engine = ENGINE_GEMINI;      // Transkription online (Gemini) oder offline (Whisper)                  // Ordner-Auswertung (wird nicht erneut mit ausgewertet)
    public final List<String> tags = new ArrayList<>();
    public String template = Templates.STANDARD;
    public String summaryLength = Templates.MEDIUM;
    /** CRM-Eintrag aus Ich-Sicht; me = Sprecherbezeichnung des Nutzers ("" = automatisch), meName = Name des Nutzers. */
    public boolean firstPerson;
    /** Modell der Zusammenfassung (zur Anzeige). */
    public String sumModel = "";
    public String me = "";
    public String meName = "";
    public final List<Answer> answers = new ArrayList<>();          // Fragen an das Transkript
    public final Map<String, Translation> translations = new LinkedHashMap<>(); // Sprachcode → Übersetzung

    /** Frage mit Antwort und Belegstellen (Zeit in ms). */
    public static final class Answer {
        public String question, answer;
        public final List<Long> refs = new ArrayList<>();
        public long askedMs;
    }

    /** Übersetzung: ein Text je Abschnitt (gleiche Reihenfolge) und eine übersetzte Zusammenfassung. */
    public static final class Translation {
        public final List<String> segments = new ArrayList<>();
        public Summary summary;
    }

    public boolean isAudio() {
        return KIND_AUDIO.equals(kind);
    }

    public boolean isWorking() {
        return RECORDING.equals(status) || QUEUED.equals(status) || PREPARE.equals(status) || TRANSCRIBE.equals(status)
                || SUMMARY.equals(status);
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
        if (!parts.isEmpty()) {
            List<Object> pl = new ArrayList<>();
            for (Part pt : parts) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("n", pt.name);
                x.put("u", pt.uri);
                x.put("s", pt.startMs);
                pl.add(x);
            }
            m.put("parts", pl);
        }
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
        m.put("recorded", recorded);
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
        m.put("kind", kind);
        if (!url.isEmpty()) m.put("url", url);
        m.put("folder", folder);
        m.put("aggregate", aggregate);
        m.put("engine", engine);
        m.put("tags", new ArrayList<Object>(tags));
        m.put("template", template);
        m.put("sumLength", summaryLength);
        if (firstPerson) m.put("firstPerson", true);
        if (!sumModel.isEmpty()) m.put("sumModel", sumModel);
        if (!me.isEmpty()) m.put("me", me);
        if (!meName.isEmpty()) m.put("meName", meName);
        List<Object> qa = new ArrayList<>();
        for (Answer a : answers) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("q", a.question);
            o.put("a", a.answer);
            o.put("at", a.askedMs);
            o.put("refs", new ArrayList<Object>(a.refs));
            qa.add(o);
        }
        m.put("qa", qa);
        Map<String, Object> tr = new LinkedHashMap<>();
        for (Map.Entry<String, Translation> e : translations.entrySet()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("segments", new ArrayList<Object>(e.getValue().segments));
            if (e.getValue().summary != null) o.put("summary", summaryToMap(e.getValue().summary));
            tr.put(e.getKey(), o);
        }
        m.put("translations", tr);
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
            if (k.done) x.put("done", true);
            if (!k.remote.isEmpty()) {
                x.put("todoist", k.remote);
                if (k.remoteDone) x.put("todoistDone", true);
            }
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
        List<Object> pl = Json.arr(m, "parts");
        if (pl != null) {
            for (Object o : pl) {
                if (t.parts.size() >= MAX_PARTS) break;
                Map<String, Object> x = Json.obj(o);
                if (x == null) continue;
                long st = Json.num(x, "s", 0);
                t.parts.add(new Part(Text.line(Json.str(x, "n"), 120), cleanUri(Json.str(x, "u")),
                        st < 0 || st > Times.MAX_MS ? 0 : st));
            }
        }
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
        t.recorded = Json.bool(m, "recorded", false);
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
        String kind = Json.str(m, "kind");
        t.kind = KIND_TEXT.equals(kind) || KIND_IMAGE.equals(kind) || KIND_WEB.equals(kind) || KIND_PDF.equals(kind) ? kind : KIND_AUDIO;
        String url = WebPage.cleanUrl(Json.str(m, "url"));
        t.url = url == null ? "" : url;
        String folder = Json.str(m, "folder");
        t.folder = folder != null && Text.isId(folder) ? folder : "";
        t.aggregate = Json.bool(m, "aggregate", false);
        t.engine = ENGINE_WHISPER.equals(Json.str(m, "engine")) ? ENGINE_WHISPER : ENGINE_GEMINI;
        setTags(t.tags, Json.arr(m, "tags"));
        t.template = Templates.clean(Json.str(m, "template"));
        t.summaryLength = Templates.cleanLength(Json.str(m, "sumLength"));
        t.firstPerson = Json.bool(m, "firstPerson", false);
        String sm = Json.str(m, "sumModel");
        t.sumModel = sm != null && sm.matches("[a-z0-9.\\-]{1,48}") ? sm : "";
        t.me = Text.line(Json.str(m, "me"), MAX_SPEAKER);
        t.meName = Text.line(Json.str(m, "meName"), MAX_SPEAKER);
        List<Object> qa = Json.arr(m, "qa");
        if (qa != null) {
            for (Object o : qa) {
                if (t.answers.size() >= MAX_QA) break;
                Answer a = answerFromMap(Json.obj(o), Times.MAX_MS);
                if (a != null) {
                    a.askedMs = clamp(Json.num(Json.obj(o), "at", 0), 0, now + 86_400_000L);
                    t.answers.add(a);
                }
            }
        }
        Map<String, Object> tr = Json.obj(m, "translations");
        if (tr != null) {
            for (Map.Entry<String, Object> e : tr.entrySet()) {
                if (t.translations.size() >= MAX_TRANSLATIONS) break;
                String code = Languages.clean(e.getKey());
                if (Languages.AUTO.equals(code) || t.translations.containsKey(code)) continue;
                Translation x = translationFromMap(Json.obj(e.getValue()), t.segments.size());
                if (x != null) t.translations.put(code, x);
            }
        }
        // Ein unterbrochener Lauf (App beendet) bleibt nicht als "läuft" stehen – das setzt der Store.
        return t;
    }

    /** Schlagwörter: einzeilig, ohne #, max. 10 × 30 Zeichen, keine Doppelten (ohne Groß/Klein). */
    public static void setTags(List<String> out, List<Object> in) {
        out.clear();
        if (in == null) return;
        for (Object o : in) {
            if (out.size() >= MAX_TAGS) break;
            if (!(o instanceof String)) continue;
            String tag = cleanTag((String) o);
            if (tag.isEmpty()) continue;
            boolean dup = false;
            for (String x : out) if (x.equalsIgnoreCase(tag)) { dup = true; break; }
            if (!dup) out.add(tag);
        }
    }

    public static String cleanTag(String s) {
        String t = Text.line(s, MAX_TAG + 5).replace("#", "").replace(",", " ").trim();
        return Text.line(t, MAX_TAG);
    }

    /** Antwort prüfen (gespeichert oder von Gemini). refs nur innerhalb der Dauer, max. 5. */
    public static Answer answerFromMap(Map<String, Object> m, long maxMs) {
        if (m == null) return null;
        Answer a = new Answer();
        a.question = Text.line(Json.str(m, "q"), 300);
        a.answer = Text.block(Json.str(m, "a"), 3000);
        if (a.question.isEmpty() || a.answer.isEmpty()) return null;
        List<Object> refs = Json.arr(m, "refs");
        if (refs != null) {
            for (Object r : refs) {
                if (a.refs.size() >= 5) break;
                long ms = -1;
                if (r instanceof Double) {
                    double d = (Double) r;
                    if (d >= 0 && d <= maxMs) ms = (long) d;
                } else if (r instanceof String) {
                    ms = Times.parse((String) r);
                    if (ms > maxMs) ms = -1;
                }
                if (ms >= 0 && !a.refs.contains(ms)) a.refs.add(ms);
            }
        }
        return a;
    }

    /** Übersetzung prüfen: genau so viele Texte wie Abschnitte, sonst verworfen. */
    public static Translation translationFromMap(Map<String, Object> m, int segmentCount) {
        if (m == null) return null;
        List<Object> segs = Json.arr(m, "segments");
        if (segs == null || segs.size() != segmentCount) return null;
        Translation t = new Translation();
        for (Object o : segs) t.segments.add(o instanceof String ? Text.block((String) o, MAX_TEXT) : "");
        Summary s = summaryFromMap(Json.obj(m, "summary"));
        if (s != null && !s.isEmpty()) t.summary = s;
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
                Task task = new Task(what, Text.line(Json.str(k, "who"), 60));
                task.done = Json.bool(k, "done", false);
                String r = Json.str(k, "todoist");
                if (Todoist.validId(r)) {
                    task.remote = r;
                    task.remoteDone = Json.bool(k, "todoistDone", false);
                }
                s.tasks.add(task);
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
        return RECORDING.equals(s) || QUEUED.equals(s) || PREPARE.equals(s) || TRANSCRIBE.equals(s) || SUMMARY.equals(s)
                || DONE.equals(s) || ERROR.equals(s) || CANCELED.equals(s) || WAITING.equals(s);
    }

    static long clamp(long v, long lo, long hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
