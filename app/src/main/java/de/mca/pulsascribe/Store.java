package de.mca.pulsascribe;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import de.mca.pulsascribe.core.Folders;
import de.mca.pulsascribe.core.Text;
import de.mca.pulsascribe.core.Transcript;

/**
 * Transkripte als JSON im privaten App-Speicher (files/t/&lt;id&gt;.json, Ton als &lt;id&gt;.aac).
 * Ein Zwischenspeicher im Prozess hält alles für Liste und Dienst synchron; Änderungen werden an
 * angemeldete Beobachter gemeldet (immer im Hauptthread).
 */
final class Store {
    private Store() {}

    private static final long MAX_FILE = 16L * 1024 * 1024;
    private static final Map<String, Transcript> cache = new HashMap<>();
    private static boolean loaded;
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final SecureRandom rnd = new SecureRandom();

    static File dir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), "t");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File audioFile(Context c, String id) {
        if (!Text.isId(id)) throw new IllegalArgumentException("id");
        return new File(dir(c), id + ".aac");
    }

    static File imageFile(Context c, String id) {
        if (!Text.isId(id)) throw new IllegalArgumentException("id");
        return new File(dir(c), id + ".jpg");
    }

    private static File jsonFile(Context c, String id) {
        if (!Text.isId(id)) throw new IllegalArgumentException("id");
        return new File(dir(c), id + ".json");
    }

    static String newId() {
        String a = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder b = new StringBuilder(16);
        for (int i = 0; i < 16; i++) b.append(a.charAt(rnd.nextInt(a.length())));
        return b.toString();
    }

    private static Folders folders;

    private static File foldersFile(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "folders.json");
    }

    private static synchronized void ensureLoaded(Context c) {
        if (loaded) return;
        loaded = true;
        purgeOld(c, Settings.trashDays(c));
        File ff = foldersFile(c);
        folders = new Folders();
        if (ff.isFile() && ff.length() < MAX_FILE) {
            try {
                folders = Folders.fromJson(new String(Files.readAllBytes(ff.toPath()), StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException ignored) { }
        }
        File[] files = dir(c).listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".json")) continue;
            String id = name.substring(0, name.length() - 5);
            if (!Text.isId(id) || f.length() > MAX_FILE) continue;
            try {
                Transcript t = Transcript.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                if (t == null || !id.equals(t.id)) continue;
                if (t.audioReady && !audioFile(c, id).isFile()) t.audioReady = false;
                if (!folders.exists(t.folder)) t.folder = Folders.ROOT; // Ordner gibt es nicht mehr
                // Beim Start kann nichts mehr laufen: ein voriger Lauf wurde unterbrochen.
                if (Transcript.RECORDING.equals(t.status)) {
                    // Aufnahme abgebrochen (App beendet): Bisheriger Ton bleibt nutzbar
                    File a = audioFile(c, id);
                    t.audioReady = a.isFile() && a.length() > 1024;
                    t.cuts = new int[0];
                    t.chunksDone = 0;
                    t.status = Transcript.ERROR;
                    t.error = c.getString(t.audioReady ? R.string.err_rec_interrupted : R.string.err_rec_lost);
                    write(c, t);
                } else if (t.isWorking()) {
                    t.status = Transcript.ERROR;
                    t.error = c.getString(R.string.err_interrupted);
                    write(c, t);
                }
                cache.put(id, t);
            } catch (IOException | RuntimeException ignored) {
                // Unlesbare Datei: wird nicht angezeigt, aber auch nicht gelöscht
            }
        }
    }

    /** Alle Transkripte, neueste zuerst. Liefert die gemeinsamen Objekte (nur im Hauptthread ändern!). */
    static synchronized List<Transcript> all(Context c) {
        ensureLoaded(c);
        List<Transcript> l = new ArrayList<>(cache.values());
        Collections.sort(l, (a, b) -> Long.compare(b.createdMs, a.createdMs));
        return l;
    }

    static synchronized Transcript get(Context c, String id) {
        ensureLoaded(c);
        return id == null ? null : cache.get(id);
    }

    static synchronized void save(Context c, Transcript t) {
        ensureLoaded(c);
        if (!Text.isId(t.id)) throw new IllegalArgumentException("id");
        cache.put(t.id, t);
        try {
            write(c, t);
        } catch (IOException ignored) {
            // Speicher voll o. ä.: Stand bleibt im Prozess erhalten, wird beim nächsten Speichern erneut versucht
        }
        changed();
    }

    private static void write(Context c, Transcript t) throws IOException {
        writeFile(jsonFile(c, t.id), t.toJson());
    }

    private static void writeFile(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
            os.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("rename");
        }
    }

    /**
     * Speichern aus dem Dienst: nur wenn das Transkript noch existiert (nicht inzwischen gelöscht);
     * den Titel übernimmt es aus dem aktuellen Stand, weil er sich während der Arbeit ändern darf.
     */
    static synchronized boolean update(Context c, Transcript t) {
        Transcript cur = get(c, t.id);
        if (cur == null) return false;
        t.title = cur.title;
        t.folder = cur.folder; // darf während der Arbeit verschoben werden
        // Haken und Todoist-Verknüpfung gehören der Oberfläche bzw. dem Abgleich
        if (t.summary != null) t.summary.carryTaskState(cur.summary);
        save(c, t);
        return true;
    }

    interface TaskEdit {
        /** true = geändert, speichern. */
        boolean edit(Transcript.Task k);
    }

    /** Eine Aufgabe ändern; index und Text müssen noch passen (sonst wurde die Zusammenfassung ersetzt). */
    static synchronized boolean editTask(Context c, String id, int index, String what, TaskEdit e) {
        Transcript t = get(c, id);
        if (t == null || t.summary == null || index < 0 || index >= t.summary.tasks.size()) return false;
        if (!t.summary.tasks.get(index).what.equals(what)) return false;
        Transcript n = copy(t);
        if (!e.edit(n.summary.tasks.get(index))) return false;
        save(c, n);
        return true;
    }

    /** Unabhängige Kopie zum Bearbeiten (Dienst und Oberfläche ändern nie dasselbe Objekt). */
    static Transcript copy(Transcript t) {
        Transcript c = Transcript.fromJson(t.toJson());
        if (c == null) throw new IllegalStateException("copy");
        return c;
    }

    static synchronized void delete(Context c, String id) {
        ensureLoaded(c);
        cache.remove(id);
        //noinspection ResultOfMethodCallIgnored
        jsonFile(c, id).delete();
        //noinspection ResultOfMethodCallIgnored
        audioFile(c, id).delete();
        //noinspection ResultOfMethodCallIgnored
        imageFile(c, id).delete();
        changed();
    }

    // ------------------------------------------------------------------ Ordner

    /** Kopie des Ordnerbaums (nur lesen). */
    static synchronized Folders folders(Context c) {
        ensureLoaded(c);
        return folders.copy();
    }

    private static void writeFolders(Context c) throws IOException {
        File f = foldersFile(c);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(folders.toJson().getBytes(StandardCharsets.UTF_8));
            os.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("rename");
        }
    }

    /** Änderung am Ordnerbaum: wird auf einer Kopie geprüft und nur bei Erfolg übernommen. */
    interface FolderEdit {
        int apply(Folders f);
    }

    static synchronized int editFolders(Context c, FolderEdit edit) {
        ensureLoaded(c);
        Folders work = folders.copy();
        int r = edit.apply(work);
        if (r != Folders.OK) return r;
        folders = work;
        try {
            writeFolders(c);
        } catch (IOException ignored) { }
        changed();
        return r;
    }

    /** Ordner löschen: Inhalt (Transkripte) wandert in den Elternordner. */
    static synchronized void deleteFolder(Context c, String id) {
        ensureLoaded(c);
        Folders work = folders.copy();
        String parent = work.delete(id);
        if (parent == null) return;
        folders = work;
        try {
            writeFolders(c);
        } catch (IOException ignored) { }
        for (Transcript t : new ArrayList<>(cache.values())) {
            if (!id.equals(t.folder)) continue;
            Transcript n = copy(t);
            n.folder = parent;
            cache.put(n.id, n);
            try { write(c, n); } catch (IOException ignored) { }
        }
        changed();
    }

    static synchronized void moveTranscript(Context c, String id, String folder) {
        Transcript t = get(c, id);
        if (t == null) return;
        Transcript n = copy(t);
        n.folder = folders.clean(folder);
        save(c, n);
    }

    static synchronized void deleteAudio(Context c, String id) {
        Transcript t = get(c, id);
        if (t == null || t.isWorking()) return;
        Transcript n = copy(t);
        //noinspection ResultOfMethodCallIgnored
        audioFile(c, id).delete();
        n.audioReady = false;
        save(c, n);
    }

    /** Nach einer Wiederherstellung: alles neu von der Platte laden. */
    static synchronized void reload(Context c) {
        cache.clear();
        loaded = false;
        ensureLoaded(c);
        changed();
    }

    static File foldersFileFor(Context c) {
        return foldersFile(c);
    }

    static synchronized boolean anyWorking(Context c) {
        ensureLoaded(c);
        for (Transcript t : cache.values()) if (t.isWorking()) return true;
        return false;
    }

    static void addListener(Runnable r) { listeners.add(r); }

    static void removeListener(Runnable r) { listeners.remove(r); }

    static void changed() {
        main.post(() -> { for (Runnable r : listeners) r.run(); });
    }

    /** Nur für Fortschrittsanzeigen: meldet ohne zu speichern. */
    static void progress() {
        changed();
    }

    // ------------------------------------------------------------------ Papierkorb

    /** Eintrag im Papierkorb (nur lesen). */
    static final class TrashItem {
        String id, title, kind, folder;
        long createdMs, durationMs, deletedMs, bytes;
        boolean audio;
    }

    private static final String TRASH_INFO = "info.json";

    private static File trashDir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), "trash");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private static File trashItem(Context c, String id) {
        if (!Text.isId(id)) throw new IllegalArgumentException("id");
        return new File(trashDir(c), id);
    }

    /**
     * In den Papierkorb: JSON, Ton und Bild wandern (umbenennen, kein Kopieren) nach files/trash/&lt;id&gt;/.
     * Die Dateifreigabe der Quelle bleibt bestehen, damit „Erneut versuchen“ nach dem Wiederherstellen geht.
     */
    static synchronized boolean trash(Context c, String id) {
        ensureLoaded(c);
        Transcript t = cache.get(id);
        if (t == null) return false;
        File d = trashItem(c, id);
        deleteTree(d);
        if (!d.mkdirs()) return false;
        Transcript n = copy(t);
        if (n.isWorking()) {
            n.status = Transcript.ERROR;
            n.error = c.getString(R.string.err_interrupted);
        }
        Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("deletedMs", System.currentTimeMillis());
        info.put("folder", folders.exists(t.folder) ? folders.pathText(t.folder) : "");
        try {
            writeFile(new File(d, TRASH_INFO), de.mca.pulsascribe.core.Json.write(info));
            writeFile(new File(d, id + ".json"), n.toJson());
        } catch (IOException e) {
            deleteTree(d);
            return false;
        }
        moveFile(audioFile(c, id), new File(d, id + ".aac"));
        moveFile(imageFile(c, id), new File(d, id + ".jpg"));
        //noinspection ResultOfMethodCallIgnored
        jsonFile(c, id).delete();
        cache.remove(id);
        changed();
        return true;
    }

    static synchronized List<TrashItem> trashList(Context c) {
        List<TrashItem> out = new ArrayList<>();
        File[] dirs = trashDir(c).listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            String id = d.getName();
            if (!d.isDirectory() || !Text.isId(id)) continue;
            File j = new File(d, id + ".json");
            if (!j.isFile() || j.length() > MAX_FILE) continue;
            try {
                Transcript t = Transcript.fromJson(new String(Files.readAllBytes(j.toPath()), StandardCharsets.UTF_8));
                if (t == null || !id.equals(t.id)) continue;
                TrashItem it = new TrashItem();
                it.id = id;
                it.title = t.title;
                it.kind = t.kind;
                it.createdMs = t.createdMs;
                it.durationMs = t.durationMs;
                it.audio = new File(d, id + ".aac").isFile();
                it.bytes = treeSize(d);
                it.deletedMs = d.lastModified();
                it.folder = "";
                File inf = new File(d, TRASH_INFO);
                if (inf.isFile() && inf.length() < 64 * 1024) {
                    Map<String, Object> m = de.mca.pulsascribe.core.Json.obj(de.mca.pulsascribe.core.Json.parse(
                            new String(Files.readAllBytes(inf.toPath()), StandardCharsets.UTF_8)));
                    it.deletedMs = de.mca.pulsascribe.core.Json.num(m, "deletedMs", it.deletedMs);
                    it.folder = Text.line(de.mca.pulsascribe.core.Json.str(m, "folder"), 200);
                }
                out.add(it);
            } catch (Exception ignored) { }
        }
        out.sort((a, b) -> Long.compare(b.deletedMs, a.deletedMs));
        return out;
    }

    /** Belegter Platz im Papierkorb bzw. aller aktiven Transkripte. */
    static long trashBytes(Context c) { return treeSize(trashDir(c)); }

    static long activeBytes(Context c) { return treeSize(dir(c)); }

    /** Zurückholen; true = ok. Ordner, den es nicht mehr gibt → oberste Ebene. */
    static synchronized boolean restore(Context c, String id) {
        ensureLoaded(c);
        if (cache.containsKey(id)) return false;
        File d = trashItem(c, id);
        File j = new File(d, id + ".json");
        if (!j.isFile() || j.length() > MAX_FILE) return false;
        Transcript t;
        try {
            t = Transcript.fromJson(new String(Files.readAllBytes(j.toPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
        if (t == null || !id.equals(t.id)) return false;
        moveFile(new File(d, id + ".aac"), audioFile(c, id));
        moveFile(new File(d, id + ".jpg"), imageFile(c, id));
        if (t.audioReady && !audioFile(c, id).isFile()) t.audioReady = false;
        if (!folders.exists(t.folder)) t.folder = Folders.ROOT;
        try {
            write(c, t);
        } catch (IOException e) {
            return false;
        }
        cache.put(id, t);
        deleteTree(d);
        changed();
        return true;
    }

    /** Endgültig löschen (gibt auch die Dateifreigabe der Quelle frei). */
    static synchronized void purge(Context c, String id) {
        File d = trashItem(c, id);
        File j = new File(d, id + ".json");
        if (j.isFile() && j.length() <= MAX_FILE) {
            try {
                Transcript t = Transcript.fromJson(new String(Files.readAllBytes(j.toPath()), StandardCharsets.UTF_8));
                if (t != null && !t.sourceUri.isEmpty()) {
                    c.getContentResolver().releasePersistableUriPermission(android.net.Uri.parse(t.sourceUri),
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } catch (Exception ignored) { }
        }
        deleteTree(d);
        changed();
    }

    static synchronized void emptyTrash(Context c) {
        for (TrashItem it : trashList(c)) purge(c, it.id);
        deleteTree(trashDir(c)); // auch Reste unlesbarer Einträge
        changed();
    }

    /** Ältere Einträge als days Tage endgültig löschen; days &lt;= 0 = nie. */
    static synchronized void purgeOld(Context c, int days) {
        if (days <= 0) return;
        long limit = System.currentTimeMillis() - days * 86_400_000L;
        for (TrashItem it : trashList(c)) if (it.deletedMs < limit) purge(c, it.id);
    }

    private static void moveFile(File from, File to) {
        if (!from.isFile()) return;
        //noinspection ResultOfMethodCallIgnored
        to.delete();
        if (!from.renameTo(to)) {
            //noinspection ResultOfMethodCallIgnored
            from.delete();
        }
    }

    private static long treeSize(File f) {
        if (f.isFile()) return f.length();
        File[] l = f.listFiles();
        long n = 0;
        if (l != null) for (File x : l) n += treeSize(x);
        return n;
    }

    private static void deleteTree(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File x : l) deleteTree(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
