package de.mca.pulsascribe.core;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Todoist REST API v1: Anfragen bauen und Antworten streng prüfen. Netzwerk macht die App.
 * Übertragen werden nur Aufgabentext, Zuständiger und Titel/Datum des Gesprächs – nie das Transkript.
 */
public final class Todoist {
    private Todoist() {}

    public static final String HOST = "api.todoist.com";
    public static final String BASE = "https://api.todoist.com/api/v1/";
    public static final String PROJECT_NAME = "Pulsa Scribe";
    public static final String TOKEN_URL = "https://app.todoist.com/app/settings/integrations/developer";

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{20,100}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public static boolean validToken(String t) {
        return t != null && TOKEN.matcher(t).matches();
    }

    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** Pfad mit geprüfter ID, z. B. tasks/123/close. */
    public static String taskPath(String id, String action) {
        if (!validId(id)) throw new IllegalArgumentException("id");
        return "tasks/" + id + (action == null ? "" : "/" + action);
    }

    /** Gleiche Aufgabe = gleiche Request-ID: Todoist legt bei Wiederholung kein Duplikat an. */
    public static String requestId(String transcriptId, String what) {
        return UUID.nameUUIDFromBytes(("pulsa-scribe:" + transcriptId + ":" + what).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Text für Todoist: Markdown-Links und Formatierung entschärfen (Inhalt stammt von Gemini). */
    static String plain(String s, int max) {
        String t = Text.line(s, max);
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '[' || c == ']' || c == '*' || c == '_' || c == '`' || c == '#' || c == '<' || c == '>') b.append(' ');
            else b.append(c);
        }
        return b.toString().replaceAll(" {2,}", " ").trim();
    }

    public static String taskBody(Transcript.Task k, Transcript t, String projectId) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("content", plain(k.what, 400));
        StringBuilder d = new StringBuilder();
        if (!k.who.isEmpty()) d.append("Zuständig: ").append(plain(k.who, 60)).append('\n');
        d.append("Aus Pulsa Scribe: ").append(plain(t.title, 120)).append(", ")
                .append(new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(new Date(t.createdMs)));
        o.put("description", d.toString());
        if (validId(projectId)) o.put("project_id", projectId);
        return Json.write(o);
    }

    public static String projectBody() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", PROJECT_NAME);
        return Json.write(o);
    }

    /** Zustand einer Aufgabe aus Todoist. */
    public static final class State {
        public final String id;
        public final boolean checked, deleted;

        State(String id, boolean checked, boolean deleted) {
            this.id = id;
            this.checked = checked;
            this.deleted = deleted;
        }
    }

    /** null = Antwort unbrauchbar. */
    public static State parseTask(String json) {
        try {
            Map<String, Object> m = Json.obj(Json.parse(json));
            if (m == null) return null;
            String id = Json.str(m, "id");
            if (!validId(id)) return null;
            return new State(id, Json.bool(m, "checked", false), Json.bool(m, "is_deleted", false));
        } catch (Exception e) {
            return null;
        }
    }

    /** ID des Projekts "Pulsa Scribe" in einer Seite der Projektliste, sonst "". */
    public static String findProject(String json) {
        try {
            Map<String, Object> m = Json.obj(Json.parse(json));
            List<Object> r = Json.arr(m, "results");
            if (r == null) return "";
            for (Object o : r) {
                Map<String, Object> p = Json.obj(o);
                if (p == null || Json.bool(p, "is_deleted", false) || Json.bool(p, "is_archived", false)) continue;
                String id = Json.str(p, "id");
                if (PROJECT_NAME.equals(Json.str(p, "name")) && validId(id)) return id;
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** Cursor für die nächste Seite oder "". */
    public static String nextCursor(String json) {
        try {
            String c = Json.str(Json.obj(Json.parse(json)), "next_cursor");
            return c != null && c.length() <= 200 && c.matches("[A-Za-z0-9_=.:+/-]+") ? c : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Projekt-ID aus der Antwort auf das Anlegen. */
    public static String parseId(String json) {
        try {
            String id = Json.str(Json.obj(Json.parse(json)), "id");
            return validId(id) ? id : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ Offene Aufgaben über alle Transkripte

    public static final class Item {
        public final Transcript transcript;
        public final int index;
        public final Transcript.Task task;

        Item(Transcript t, int index, Transcript.Task task) {
            this.transcript = t;
            this.index = index;
            this.task = task;
        }
    }

    /** Aufgaben aller Transkripte, neueste Gespräche zuerst; erledigte nur auf Wunsch. */
    public static List<Item> tasks(List<Transcript> all, boolean withDone) {
        List<Transcript> sorted = new ArrayList<>(all);
        sorted.sort((a, b) -> Long.compare(b.createdMs, a.createdMs));
        List<Item> out = new ArrayList<>();
        for (Transcript t : sorted) {
            if (t.summary == null) continue;
            for (int i = 0; i < t.summary.tasks.size(); i++) {
                Transcript.Task k = t.summary.tasks.get(i);
                if (withDone || !k.done) out.add(new Item(t, i, k));
            }
        }
        return out;
    }

    public static int openCount(List<Transcript> all) {
        int n = 0;
        for (Transcript t : all) {
            if (t.summary == null) continue;
            for (Transcript.Task k : t.summary.tasks) if (!k.done) n++;
        }
        return n;
    }
}
