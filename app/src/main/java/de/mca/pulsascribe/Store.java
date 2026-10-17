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
        File f = jsonFile(c, t.id);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(t.toJson().getBytes(StandardCharsets.UTF_8));
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
        save(c, t);
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
}
