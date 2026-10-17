package de.mca.pulsascribe;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

import de.mca.pulsascribe.core.Todoist;
import de.mca.pulsascribe.core.Transcript;

/**
 * Abgleich mit Todoist (REST API v1), nur wenn ein Token eingetragen ist.
 * Alles läuft nacheinander auf einem Hintergrund-Thread; Ergebnis als Text-Ressource auf dem Hauptthread.
 * Regeln: Anlegen nur auf Wunsch (Knopf oder Schalter „automatisch“); Abhaken in der App → in Todoist erledigen
 * und umgekehrt; in Todoist gelöschte Aufgaben werden nur entkoppelt, in der App bleibt die Aufgabe.
 */
final class TodoistSync {
    private TodoistSync() {}

    interface Done {
        void done(int messageRes);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int CONNECT_TIMEOUT = 15_000, READ_TIMEOUT = 30_000, MAX_RESPONSE = 1 << 20;
    private static final int MAX_CHECKS = 60;
    private static final long PULL_EVERY_MS = 60_000;
    private static volatile long lastPull = -PULL_EVERY_MS;

    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean ok() { return code >= 200 && code < 300; }
    }

    /** Fehler, bei dem der ganze Lauf endet (Token ungültig, zu viele Anfragen, offline). */
    private static final class Stop extends Exception {
        final int res;

        Stop(int res) {
            super(null, null, false, false);
            this.res = res;
        }
    }

    // ------------------------------------------------------------------ öffentlich

    /** Token prüfen (beim Eintragen). */
    static void test(String token, Done done) {
        EXEC.execute(() -> {
            int res;
            try {
                Response r = call(token, "GET", "projects?limit=1", null, null);
                res = r.ok() ? R.string.td_test_ok : message(r.code);
            } catch (IOException e) {
                res = R.string.td_offline;
            }
            post(done, res);
        });
    }

    /** Offene, noch nicht verknüpfte Aufgaben eines Transkripts anlegen; index -1 = alle. */
    static void push(Context ctx, String transcriptId, int index, Done done) {
        Context c = ctx.getApplicationContext();
        String token = TodoistToken.get(c);
        if (token == null) { post(done, R.string.td_no_token); return; }
        EXEC.execute(() -> {
            int res;
            try {
                res = pushNow(c, token, transcriptId, index);
            } catch (Stop s) {
                res = s.res;
            }
            post(done, res);
        });
    }

    /** Nach einer neuen Zusammenfassung, wenn „automatisch senden“ an ist. Still, ohne Meldung. */
    static void autoPush(Context ctx, String transcriptId) {
        if (!Settings.todoistAuto(ctx) || !TodoistToken.has(ctx)) return;
        push(ctx, transcriptId, -1, null);
    }

    /** Haken in der App setzen/entfernen und, falls verknüpft, an Todoist melden. */
    static void setDone(Context ctx, String transcriptId, int index, String what, boolean done) {
        Context c = ctx.getApplicationContext();
        if (!Store.editTask(c, transcriptId, index, what, k -> {
            if (k.done == done) return false;
            k.done = done;
            return true;
        })) return;
        String token = TodoistToken.get(c);
        if (token == null) return;
        EXEC.execute(() -> {
            try {
                syncState(c, token, transcriptId, index, what);
            } catch (Stop ignored) {
                // wird beim nächsten Abgleich nachgeholt
            }
        });
    }

    /** Abgleich aller verknüpften Aufgaben; höchstens einmal pro Minute, außer force. */
    static void pull(Context ctx, boolean force, Done done) {
        Context c = ctx.getApplicationContext();
        String token = TodoistToken.get(c);
        if (token == null) { if (done != null) post(done, R.string.td_no_token); return; }
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastPull < PULL_EVERY_MS) return;
        lastPull = now;
        EXEC.execute(() -> {
            int res;
            try {
                res = pullNow(c, token);
            } catch (Stop s) {
                res = s.res;
            }
            post(done, res);
        });
    }

    // ------------------------------------------------------------------ Ablauf

    private static int pushNow(Context c, String token, String tid, int only) throws Stop {
        Transcript t = Store.get(c, tid);
        if (t == null || t.summary == null) return R.string.td_nothing;
        String project = ensureProject(c, token);
        int sent = 0;
        for (int i = 0; i < t.summary.tasks.size(); i++) {
            if (only >= 0 && i != only) continue;
            Transcript.Task k = t.summary.tasks.get(i);
            if (k.done || !k.remote.isEmpty()) continue;
            Response r = request(token, "POST", "tasks", Todoist.taskBody(k, t, project), Todoist.requestId(tid, k.what));
            if (!r.ok() && (r.code == 400 || r.code == 404) && !project.isEmpty()) {
                // Projekt in Todoist gelöscht → neu suchen/anlegen, einmal wiederholen
                TodoistToken.setProject(c, "");
                project = ensureProject(c, token);
                r = request(token, "POST", "tasks", Todoist.taskBody(k, t, project), Todoist.requestId(tid + "#2", k.what));
            }
            if (!r.ok()) throw new Stop(message(r.code));
            Todoist.State s = Todoist.parseTask(r.body);
            if (s == null) throw new Stop(R.string.td_bad_reply);
            if (Store.editTask(c, tid, i, k.what, x -> {
                x.remote = s.id;
                x.remoteDone = false;
                return true;
            })) sent++;
        }
        return sent == 0 ? R.string.td_nothing : (sent == 1 ? R.string.td_sent_one : R.string.td_sent);
    }

    private static int pullNow(Context c, String token) throws Stop {
        int checks = 0, changed = 0;
        for (Transcript t : Store.all(c)) {
            if (t.summary == null) continue;
            for (int i = 0; i < t.summary.tasks.size(); i++) {
                Transcript.Task k = t.summary.tasks.get(i);
                if (k.remote.isEmpty()) continue;
                if (k.done != k.remoteDone) {
                    if (syncState(c, token, t.id, i, k.what)) changed++;
                    continue;
                }
                if (k.done || checks >= MAX_CHECKS) continue;
                checks++;
                Response r = request(token, "GET", Todoist.taskPath(k.remote, null), null, null);
                if (r.code == 404) {
                    unlink(c, t.id, i, k.what);
                    changed++;
                    continue;
                }
                if (!r.ok()) throw new Stop(message(r.code));
                Todoist.State s = Todoist.parseTask(r.body);
                if (s == null || !s.id.equals(k.remote)) continue;
                if (s.deleted) {
                    unlink(c, t.id, i, k.what);
                    changed++;
                } else if (s.checked) {
                    if (Store.editTask(c, t.id, i, k.what, x -> {
                        x.done = true;
                        x.remoteDone = true;
                        return true;
                    })) changed++;
                }
            }
        }
        return changed > 0 ? R.string.td_synced_changes : R.string.td_synced;
    }

    /** Lokalen Haken nach Todoist übertragen (erledigen bzw. wieder öffnen). */
    private static boolean syncState(Context c, String token, String tid, int i, String what) throws Stop {
        Transcript t = Store.get(c, tid);
        if (t == null || t.summary == null || i >= t.summary.tasks.size()) return false;
        Transcript.Task k = t.summary.tasks.get(i);
        if (!k.what.equals(what) || k.remote.isEmpty() || k.done == k.remoteDone) return false;
        boolean want = k.done;
        Response r = request(token, "POST", Todoist.taskPath(k.remote, want ? "close" : "reopen"), null, null);
        if (r.code == 404) {
            unlink(c, tid, i, what);
            return true;
        }
        if (!r.ok()) throw new Stop(message(r.code));
        return Store.editTask(c, tid, i, what, x -> {
            if (x.done != want) return false; // inzwischen wieder geändert → nächster Abgleich
            x.remoteDone = want;
            return true;
        });
    }

    private static void unlink(Context c, String tid, int i, String what) {
        Store.editTask(c, tid, i, what, x -> {
            x.remote = "";
            x.remoteDone = false;
            return true;
        });
    }

    /** Projekt "Pulsa Scribe" finden oder anlegen; "" = Eingang verwenden. */
    private static String ensureProject(Context c, String token) throws Stop {
        String cached = TodoistToken.project(c);
        if (!cached.isEmpty()) return cached;
        String cursor = "";
        for (int page = 0; page < 10; page++) {
            Response r = request(token, "GET", "projects?limit=200" + (cursor.isEmpty() ? "" : "&cursor=" + enc(cursor)), null, null);
            if (!r.ok()) throw new Stop(message(r.code));
            String id = Todoist.findProject(r.body);
            if (!id.isEmpty()) {
                TodoistToken.setProject(c, id);
                return id;
            }
            cursor = Todoist.nextCursor(r.body);
            if (cursor.isEmpty()) break;
        }
        Response r = request(token, "POST", "projects", Todoist.projectBody(), null);
        if (!r.ok()) return ""; // z. B. Projektlimit im kostenlosen Tarif → Eingang
        String id = Todoist.parseId(r.body);
        TodoistToken.setProject(c, id);
        return id;
    }

    // ------------------------------------------------------------------ HTTP

    private static Response request(String token, String method, String path, String json, String requestId) throws Stop {
        try {
            Response r = call(token, method, path, json, requestId);
            if (r.code == 401 || r.code == 403 || r.code == 429) throw new Stop(message(r.code));
            return r;
        } catch (IOException e) {
            throw new Stop(R.string.td_offline);
        }
    }

    private static Response call(String token, String method, String path, String json, String requestId) throws IOException {
        URL url = new URL(Todoist.BASE + path);
        if (!"https".equals(url.getProtocol()) || !Todoist.HOST.equals(url.getHost())) throw new IOException("Host");
        HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setUseCaches(false);
            c.setRequestMethod(method);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/json");
            if (requestId != null) c.setRequestProperty("X-Request-Id", requestId);
            if ("POST".equals(method)) {
                byte[] body = (json == null ? "" : json).getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                if (json != null) c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            return new Response(code, in == null ? "" : read(in));
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                if (bos.size() + n > MAX_RESPONSE) throw new IOException("Antwort zu groß");
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        }
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }

    private static int message(int code) {
        if (code == 401 || code == 403) return R.string.td_bad_token;
        if (code == 429) return R.string.td_rate;
        return R.string.td_failed;
    }

    private static void post(Done d, int res) {
        if (d != null) MAIN.post(() -> d.done(res));
    }
}
