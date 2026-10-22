package de.mca.pulsascribe;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.mca.pulsascribe.core.Text;
import de.mca.pulsascribe.core.Transcript;

/**
 * Anrufaufnahmen automatisch übernehmen: Der Nutzer gibt genau einen Ordner frei (Android-Ordnerauswahl,
 * nur Lesen). Neue Audiodateien darin werden als Eintrag angelegt („wartet“) und per Benachrichtigung
 * zum Transkribieren angeboten. Android erlaubt einer App im Hintergrund nicht, selbst eine lange
 * Transkription zu starten – ein Tipp auf die Benachrichtigung oder das Öffnen der App startet sie.
 */
final class CallImport {
    private CallImport() {}

    private static final String PREFS = "pulsa_scribe_calls";
    private static final String K_TREE = "tree", K_SINCE = "since", K_SEEN = "seen", K_AUTO = "auto_start", K_TEMPLATE = "template";
    static final int JOB_CONTENT = 7001, JOB_PERIODIC = 7002;
    private static final String CHANNEL = "calls";
    private static final long SETTLE_MS = 20_000; // Datei muss so lange unverändert sein (Aufnahme fertig)
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final String[] AUDIO_EXT = {".m4a", ".mp3", ".amr", ".3gp", ".aac", ".wav", ".ogg", ".opus", ".awb"};

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Uri tree(Context c) {
        String s = p(c).getString(K_TREE, "");
        if (!s.startsWith("content://")) return null;
        Uri u = Uri.parse(s);
        for (android.content.UriPermission perm : c.getContentResolver().getPersistedUriPermissions()) {
            if (perm.getUri().equals(u) && perm.isReadPermission()) return u;
        }
        return null; // Freigabe wurde entzogen
    }

    static boolean enabled(Context c) {
        return tree(c) != null;
    }

    /** Ordner übernehmen (nach ACTION_OPEN_DOCUMENT_TREE). Nur ab jetzt neue Aufnahmen. */
    static boolean setTree(Context c, Uri u) {
        try {
            c.getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            return false;
        }
        Uri old = tree(c);
        if (old != null && !old.equals(u)) release(c, old);
        p(c).edit().putString(K_TREE, u.toString()).putLong(K_SINCE, System.currentTimeMillis()).remove(K_SEEN).apply();
        schedule(c);
        return true;
    }

    static void disable(Context c) {
        Uri u = tree(c);
        if (u != null) release(c, u);
        p(c).edit().remove(K_TREE).remove(K_SEEN).apply();
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js != null) { js.cancel(JOB_CONTENT); js.cancel(JOB_PERIODIC); }
    }

    private static void release(Context c, Uri u) {
        if (u.equals(BackupPlace.storedTree(c))) return; // derselbe Ordner dient auch als Sicherungsort
        try {
            c.getContentResolver().releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { }
    }

    static boolean autoStart(Context c) {
        SharedPreferences sp = p(c);
        if (!sp.getBoolean("auto_v2", false)) {
            // ab 1.29: nie mehr ungefragt starten – einmalig ausschalten, danach gilt die eigene Wahl
            sp.edit().putBoolean(K_AUTO, false).putBoolean("auto_v2", true).apply();
        }
        return sp.getBoolean(K_AUTO, false);
    }
    static void setAutoStart(Context c, boolean v) { p(c).edit().putBoolean(K_AUTO, v).apply(); }
    /** Zielordner für übernommene Telefonate ("" = oberste Ebene). */
    static String folder(Context c) { return Store.folders(c).clean(p(c).getString("folder", de.mca.pulsascribe.core.Folders.ROOT)); }
    static void setFolder(Context c, String f) { p(c).edit().putString("folder", f == null ? "" : f).apply(); }

    static String template(Context c) { return de.mca.pulsascribe.core.Templates.clean(p(c).getString(K_TEMPLATE, Settings.template(c))); }
    static void setTemplate(Context c, String t) { p(c).edit().putString(K_TEMPLATE, de.mca.pulsascribe.core.Templates.clean(t)).apply(); }

    /** Lesbarer Ordnername für die Anzeige. */
    static String folderName(Context c) {
        Uri u = tree(c);
        if (u == null) return "";
        try {
            String id = DocumentsContract.getTreeDocumentId(u);
            int colon = id.indexOf(':');
            return Text.line(colon >= 0 ? id.substring(colon + 1) : id, 80);
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ Planen

    /** Hintergrund: Benachrichtigung von Android, sobald eine neue Audiodatei im Medienspeicher auftaucht, plus Prüfung alle 30 Minuten. */
    static void schedule(Context c) {
        if (!enabled(c)) return;
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        ComponentName svc = new ComponentName(c, CallJob.class);
        try {
            js.schedule(new JobInfo.Builder(JOB_CONTENT, svc)
                    .addTriggerContentUri(new JobInfo.TriggerContentUri(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                    .addTriggerContentUri(new JobInfo.TriggerContentUri(MediaStore.Files.getContentUri("external"),
                            JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                    .setTriggerContentUpdateDelay(SETTLE_MS)
                    .setTriggerContentMaxDelay(2 * 60_000)
                    .build());
            if (js.getPendingJob(JOB_PERIODIC) == null) {
                js.schedule(new JobInfo.Builder(JOB_PERIODIC, svc)
                        .setPeriodic(30 * 60_000L)
                        .setPersisted(true)
                        .build());
            }
        } catch (RuntimeException ignored) { }
    }

    /** Beim Öffnen der App: prüfen und (wenn eingestellt) wartende Telefonate gleich starten – die App ist im Vordergrund. */
    static void scanAsync(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (!enabled(c)) return;
        schedule(c);
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        EXEC.execute(() -> {
            scan(c);
            main.post(() -> startWaiting(c));
        });
    }

    // ------------------------------------------------------------------ Prüfen und übernehmen

    /** Neue, fertige Aufnahmen im freigegebenen Ordner (auch in Unterordnern, 1 Ebene) übernehmen. */
    static synchronized int scan(Context c) {
        Uri tree = tree(c);
        if (tree == null) return 0;
        long since = p(c).getLong(K_SINCE, System.currentTimeMillis());
        Set<String> seen = new HashSet<>(p(c).getStringSet(K_SEEN, new HashSet<>()));
        long now = System.currentTimeMillis();
        List<String[]> found = new ArrayList<>(); // {docId, name, lastModified}
        try {
            String root = DocumentsContract.getTreeDocumentId(tree);
            list(c, tree, root, found, 0, false);
        } catch (RuntimeException e) {
            return 0;
        }
        int added = 0;
        long newest = since;
        for (String[] f : found) {
            long mod = Long.parseLong(f[2]);
            if (mod <= since || seen.contains(f[0])) continue;
            if (now - mod < SETTLE_MS) continue; // vermutlich noch in Arbeit
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, f[0]);
            if (create(c, doc, f[1], mod)) added++;
            seen.add(f[0]);
            newest = Math.max(newest, mod);
        }
        if (seen.size() > 300) seen.clear(); // „since“ verhindert Dopplungen danach
        p(c).edit().putStringSet(K_SEEN, seen).putLong(K_SINCE, Math.max(since, newest - 1)).apply();
        return added;
    }

    private static void list(Context c, Uri tree, String parentId, List<String[]> out, int depth, boolean any) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE};
        try (Cursor cur = c.getContentResolver().query(children, cols, null, null, null)) {
            if (cur == null) return;
            while (cur.moveToNext() && out.size() < 2000) {
                String id = cur.getString(0), name = cur.getString(1), mime = cur.getString(2);
                long mod = cur.isNull(3) ? 0 : cur.getLong(3), size = cur.isNull(4) ? 0 : cur.getLong(4);
                if (id == null || name == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (depth < 1) list(c, tree, id, out, depth + 1, any);
                    continue;
                }
                if ((!any && size < 2048) || !isAudio(name, mime)) continue;
                out.add(new String[]{id, name, Long.toString(mod), Long.toString(size)});
            }
        } catch (RuntimeException ignored) { }
    }

    /** Eintrag für die Vorhör-Auswahl. */
    static final class Rec {
        Uri uri;
        String name;
        long modified, size;
    }

    /** Alle Audiodateien im freigegebenen Ordner (1 Unterordnerebene), neueste zuerst, max. 2000. */
    static List<Rec> listAll(Context c) {
        List<Rec> out = new ArrayList<>();
        Uri tree = tree(c);
        if (tree == null) return out;
        List<String[]> found = new ArrayList<>();
        try {
            list(c, tree, DocumentsContract.getTreeDocumentId(tree), found, 0, true);
        } catch (RuntimeException e) {
            return out;
        }
        for (String[] f : found) {
            Rec r = new Rec();
            r.uri = DocumentsContract.buildDocumentUriUsingTree(tree, f[0]);
            r.name = Text.line(f[1], 160);
            r.modified = Long.parseLong(f[2]);
            r.size = Long.parseLong(f[3]);
            out.add(r);
        }
        out.sort((a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    static boolean isAudio(String name, String mime) {
        if (mime != null && mime.startsWith("audio/")) return true;
        String l = name.toLowerCase(Locale.ROOT);
        for (String e : AUDIO_EXT) if (l.endsWith(e)) return true;
        return false;
    }

    private static boolean create(Context c, Uri doc, String name, long mod) {
        Transcript t = new Transcript();
        t.id = Store.newId();
        String base = Text.titleFromFileName(Text.line(name, 120));
        t.title = Text.line(c.getString(R.string.call_title, base), Transcript.MAX_TITLE);
        t.createdMs = mod > 0 ? mod : System.currentTimeMillis();
        t.language = Settings.language(c);
        t.template = template(c);
        t.summaryLength = Settings.summaryLength(c);
        t.diarize = Settings.diarize(c);
        t.cleanFillers = Settings.cleanFillers(c);
        t.summarize = Settings.summarize(c);
        t.firstPerson = Settings.crmFirstPerson(c);
        t.meName = Settings.myName(c);
        t.engine = Settings.engine(c);
        t.source = Text.line(name, 120);
        t.folder = folder(c);
        t.sourceUri = Transcript.cleanUri(doc.toString());
        if (t.sourceUri.isEmpty()) return false;
        t.status = Transcript.WAITING;
        try {
            Store.save(c, t);
        } catch (RuntimeException e) {
            return false;
        }
        notifyNew(c, t);
        return true;
    }

    /** Benachrichtigung: Tippen öffnet den Eintrag, „Transkribieren“ startet sofort (erlaubter Start durch Nutzeraktion). */
    private static void notifyNew(Context c, Transcript t) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.ch_calls), NotificationManager.IMPORTANCE_DEFAULT));
        int req = t.id.hashCode();
        Intent open = new Intent(c, TranscriptActivity.class).putExtra(TranscribeService.EXTRA_ID, t.id)
                .putExtra(TranscriptActivity.EXTRA_START_WAITING, autoStart(c))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent piOpen = PendingIntent.getActivity(c, req, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent go = new Intent(c, TranscribeService.class).setAction(TranscribeService.ACTION_START).putExtra(TranscribeService.EXTRA_ID, t.id);
        PendingIntent piGo = PendingIntent.getForegroundService(c, req + 1, go, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification n = new android.app.Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(c.getString(R.string.call_new_title))
                .setContentText(t.title)
                .setContentIntent(piOpen)
                .addAction(new android.app.Notification.Action.Builder(null, c.getString(R.string.call_transcribe), piGo).build())
                .setAutoCancel(true)
                .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
                .build();
        nm.notify(req, n);
    }

    /** Wartende Telefonate starten (beim Öffnen der App, wenn „automatisch“ an ist). */
    static void startWaiting(Context a) {
        if (!autoStart(a) || !AiKeys.ready(a) && !"whisper".equals(Settings.engine(a))) return;
        for (Transcript t : Store.all(a)) {
            if (!Transcript.WAITING.equals(t.status)) continue;
            try {
                TranscribeService.start(a, t.id, null);
            } catch (RuntimeException ignored) {
                return;
            }
            NotificationManager nm = a.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(t.id.hashCode());
        }
    }
}
