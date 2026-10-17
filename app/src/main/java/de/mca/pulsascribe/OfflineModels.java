package de.mca.pulsascribe;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

import de.mca.pulsascribe.core.Offline;

/**
 * Modelle für die Offline-Erkennung: Download über den Android-DownloadManager (eigene Benachrichtigung,
 * Wiederaufnahme), danach SHA-256-Prüfung gegen die feste Prüfsumme und Ablage im privaten Speicher.
 * Nur geprüfte Dateien werden je geladen.
 */
final class OfflineModels {
    private OfflineModels() {}

    private static final String PREFS = "pulsa_scribe_offline";

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "models");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File file(Context c, Offline.Model m) {
        return new File(dir(c), m.file);
    }

    /** Installiert = Datei vorhanden, Größe stimmt und Prüfung war erfolgreich (Marker). */
    static boolean installed(Context c, Offline.Model m) {
        File f = file(c, m);
        return f.isFile() && f.length() == m.size
                && m.sha256.equals(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ok_" + m.id, ""));
    }

    static String selected(Context c) {
        String id = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("selected", "small");
        return Offline.model(id) != null ? id : "small";
    }

    static void setSelected(Context c, String id) {
        if (Offline.model(id) != null) c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("selected", id).apply();
    }

    /** Erstes installiertes Modell, bevorzugt das gewählte. */
    static Offline.Model usable(Context c) {
        Offline.Model sel = Offline.model(selected(c));
        if (sel != null && installed(c, sel)) return sel;
        for (Offline.Model m : Offline.MODELS) if (installed(c, m)) return m;
        return null;
    }

    static boolean anyInstalled(Context c) {
        return usable(c) != null;
    }

    static long download(Context c, Offline.Model m) {
        DownloadManager dm = c.getSystemService(DownloadManager.class);
        File ext = c.getExternalFilesDir("models");
        if (dm == null || ext == null) return -1;
        //noinspection ResultOfMethodCallIgnored
        new File(ext, m.file).delete();
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(m.url()))
                .setTitle(c.getString(R.string.off_dl_title, m.label))
                .setDescription(c.getString(R.string.app_name))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(c, "models", m.file)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false);
        long id = dm.enqueue(r);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("dl_" + m.id, id).apply();
        return id;
    }

    /** Status: -1 kein Download, sonst Prozent (0–100); 101 = fertig heruntergeladen (Prüfung offen); -2 Fehler. */
    static int downloadState(Context c, Offline.Model m) {
        long id = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("dl_" + m.id, -1);
        if (id < 0) return -1;
        DownloadManager dm = c.getSystemService(DownloadManager.class);
        if (dm == null) return -1;
        try (Cursor cur = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (cur == null || !cur.moveToFirst()) return -1;
            int status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            if (status == DownloadManager.STATUS_SUCCESSFUL) return 101;
            if (status == DownloadManager.STATUS_FAILED) return -2;
            return (int) Math.min(100, Math.max(0, done * 100 / Math.max(1, m.size)));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Fertigen Download prüfen und übernehmen (im Hintergrund aufrufen: SHA-256 über bis zu 574 MB).
     * true = installiert.
     */
    static boolean verifyAndInstall(Context c, Offline.Model m) {
        File ext = c.getExternalFilesDir("models");
        if (ext == null) return false;
        File src = new File(ext, m.file);
        boolean ok = false;
        try {
            if (src.isFile() && src.length() == m.size && m.sha256.equals(sha256(src))) {
                File dst = file(c, m);
                File tmp = new File(dst.getPath() + ".tmp");
                copy(src, tmp);
                ok = tmp.length() == m.size && tmp.renameTo(dst);
                if (!ok) //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
            }
        } catch (IOException ignored) {
            ok = false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            src.delete(); // externe Kopie immer entfernen
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("dl_" + m.id).apply();
        }
        if (ok) c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("ok_" + m.id, m.sha256).apply();
        return ok;
    }

    static void delete(Context c, Offline.Model m) {
        //noinspection ResultOfMethodCallIgnored
        file(c, m).delete();
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("ok_" + m.id).apply();
    }

    static void cancelDownload(Context c, Offline.Model m) {
        long id = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("dl_" + m.id, -1);
        DownloadManager dm = c.getSystemService(DownloadManager.class);
        if (id >= 0 && dm != null) dm.remove(id);
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("dl_" + m.id).apply();
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest()) b.append(String.format(Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); java.io.OutputStream os = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }
}
