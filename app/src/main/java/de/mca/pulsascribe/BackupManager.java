package de.mca.pulsascribe;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import de.mca.pulsascribe.core.Backup;
import de.mca.pulsascribe.core.Folders;
import de.mca.pulsascribe.core.Text;
import de.mca.pulsascribe.core.Transcript;

/**
 * Datensicherung über die Android-Dateiauswahl (keine Speicher-Berechtigung): ZIP mit Transkripten,
 * Ordnern und wahlweise Ton/Bildern, verschlüsselt mit {@link Backup}. Der Gemini-Schlüssel ist nie enthalten.
 * Wiederherstellen ergänzt nur, was fehlt (vorhandene Einträge bleiben unverändert).
 */
final class BackupManager {
    private BackupManager() {}

    interface Progress {
        void on(int done, int total);
    }

    static final class Result {
        int added, skipped, rejected;
    }

    static void create(Context c, Uri target, char[] password, String hint, boolean withMedia, Progress p) throws IOException {
        List<Transcript> all = Store.all(c);
        int total = all.size() + 1, done = 0;
        try (OutputStream raw = c.getContentResolver().openOutputStream(target, "wt")) {
            if (raw == null) throw new IOException("Ziel");
            try (ZipOutputStream z = new ZipOutputStream(Backup.encrypt(new BufferedOutputStream(raw, 64 * 1024), password, hint))) {
                z.setLevel(5);
                put(z, "folders.json", Store.folders(c).toJson().getBytes(StandardCharsets.UTF_8));
                p.on(++done, total);
                for (Transcript t : all) {
                    if (t.isWorking()) { p.on(++done, total); continue; } // halbfertige nicht sichern
                    put(z, "t/" + t.id + ".json", t.toJson().getBytes(StandardCharsets.UTF_8));
                    if (withMedia) {
                        putFile(z, "t/" + t.id + ".aac", Store.audioFile(c, t.id));
                        putFile(z, "t/" + t.id + ".jpg", Store.imageFile(c, t.id));
                    }
                    p.on(++done, total);
                }
            }
        }
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    private static void putFile(ZipOutputStream z, String name, File f) throws IOException {
        if (!f.isFile()) return;
        z.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
        }
        z.closeEntry();
    }

    /**
     * Erst alles in einen Zwischenordner entschlüsseln und prüfen; erst wenn die ganze Datei
     * echt und vollständig ist, werden fehlende Einträge übernommen.
     */
    /** Passwort-Hinweis aus der Datei ("" wenn keiner oder nicht lesbar). */
    static String hint(Context c, Uri source) {
        try (InputStream raw = c.getContentResolver().openInputStream(source)) {
            return raw == null ? "" : Backup.readHint(new BufferedInputStream(raw, 1024));
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    static Result restore(Context c, Uri source, char[] password, Progress p) throws IOException {
        File tmp = new File(c.getCacheDir(), "restore");
        deleteTree(tmp);
        if (!new File(tmp, "t").mkdirs()) throw new IOException("tmp");
        Result r = new Result();
        int entries = 0;
        try (InputStream raw = c.getContentResolver().openInputStream(source)) {
            if (raw == null) throw new IOException("Quelle");
            InputStream dec = Backup.decrypt(new BufferedInputStream(raw, 64 * 1024), password);
            try (ZipInputStream z = new ZipInputStream(dec)) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (++entries > Backup.MAX_ENTRIES) throw new Backup.FormatException("Zu viele Einträge");
                    String name = e.getName();
                    if (e.isDirectory() || !Backup.validEntry(name)) { r.rejected++; continue; }
                    File out = new File(tmp, name);
                    long max = Backup.maxSize(name);
                    try (OutputStream os = new FileOutputStream(out)) {
                        byte[] buf = new byte[64 * 1024];
                        long size = 0;
                        int n;
                        while ((n = z.read(buf)) > 0) {
                            size += n;
                            if (size > max) throw new Backup.FormatException("Eintrag zu groß");
                            os.write(buf, 0, n);
                        }
                    }
                    if (entries % 10 == 0) p.on(entries, 0);
                }
                // Restliche Blöcke (ZIP-Verzeichnis) direkt lesen, damit auch das Ende geprüft ist
                byte[] skip = new byte[8192];
                while (dec.read(skip) > 0) { /* nur prüfen */ }
            }
            mergeInto(c, tmp, r);
        } finally {
            deleteTree(tmp);
        }
        Store.reload(c);
        return r;
    }

    private static void mergeInto(Context c, File tmp, Result r) throws IOException {
        // Ordner: nur fehlende übernehmen (gleiche ID = schon vorhanden)
        File ff = new File(tmp, "folders.json");
        if (ff.isFile()) {
            Folders incoming;
            try (InputStream in = new FileInputStream(ff)) {
                incoming = Folders.fromJson(Backup.utf8(Backup.readLimited(in, Backup.maxSize("folders.json"))));
            }
            Store.editFolders(c, cur -> {
                // Eltern zuerst: Baumreihenfolge
                for (Folders.Folder f : incoming.tree()) {
                    if (cur.exists(f.id)) continue;
                    String parent = cur.exists(f.parent) ? f.parent : Folders.ROOT;
                    if (cur.add(f.id, f.name, parent, null) != Folders.OK) {
                        cur.add(f.id, f.name + " (2)", parent, null);
                    }
                }
                return Folders.OK;
            });
        }
        Folders now = Store.folders(c);
        File[] files = new File(tmp, "t").listFiles();
        if (files == null) return;
        Set<String> existing = new HashSet<>();
        for (Transcript t : Store.all(c)) existing.add(t.id);
        List<File> jsons = new ArrayList<>();
        for (File f : files) if (f.getName().endsWith(".json")) jsons.add(f);
        File dir = Store.dir(c);
        for (File f : jsons) {
            String id = f.getName().substring(0, f.getName().length() - 5);
            if (!Text.isId(id)) { r.rejected++; continue; }
            if (existing.contains(id)) { r.skipped++; continue; }
            Transcript t;
            try (InputStream in = new FileInputStream(f)) {
                t = Transcript.fromJson(Backup.utf8(Backup.readLimited(in, Backup.maxSize(f.getName()))));
            }
            if (t == null || !id.equals(t.id)) { r.rejected++; continue; }
            if (t.isWorking()) t.status = Transcript.ERROR;
            t.folder = now.clean(t.folder);
            t.sourceUri = ""; // Freigaben gelten nur auf dem alten Gerät
            for (Transcript.Part pt : t.parts) pt.uri = "";
            File aac = new File(tmp, "t/" + id + ".aac"), jpg = new File(tmp, "t/" + id + ".jpg");
            t.audioReady = t.audioReady && aac.isFile() && moveFile(aac, new File(dir, id + ".aac"));
            if (jpg.isFile()) moveFile(jpg, new File(dir, id + ".jpg"));
            try (OutputStream os = new FileOutputStream(new File(dir, id + ".json"))) {
                os.write(t.toJson().getBytes(StandardCharsets.UTF_8));
            }
            r.added++;
        }
    }

    private static boolean moveFile(File from, File to) {
        if (from.renameTo(to)) return true;
        try (InputStream in = new FileInputStream(from); OutputStream os = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            return true;
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            to.delete();
            return false;
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
