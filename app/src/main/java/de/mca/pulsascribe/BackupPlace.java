package de.mca.pulsascribe;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import de.mca.pulsascribe.core.Backup;
import de.mca.pulsascribe.core.BackupFiles;

/**
 * Sicherung wie in Pulsa Fastflow: einmal gewählter Ordner (Freigabe nur für diesen Ordner), optional gemerktes
 * Passwort (AES-256-GCM mit eigenem Keystore-Schlüssel, verlässt das Gerät nie), „Jetzt sichern“ mit einem Tipp,
 * Liste der Sicherungen im Ordner und einmal täglich automatisch mit Aufbewahrung 7/14/30 Tage.
 */
final class BackupPlace {
    private BackupPlace() {}

    private static final String PREFS = "pulsa_scribe_backup";
    private static final String PW_PREFS = "pulsa_scribe_backup_pw";
    private static final String ALIAS = "pulsa_scribe_backup_pw_v1";
    static final int JOB_ID = 4242;
    static final int MAX_LIST = 20;
    static final long REMIND_MS = 30L * 24 * 60 * 60 * 1000;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    // ------------------------------------------------------------------ Ordner

    /** Gemerkter Ordner, nur wenn die Freigabe noch besteht. */
    static Uri tree(Context c) {
        String s = p(c).getString("tree", null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission perm : c.getContentResolver().getPersistedUriPermissions()) {
            if (perm.getUri().equals(u) && perm.isReadPermission() && perm.isWritePermission()) return u;
        }
        return null;
    }

    static Uri storedTree(Context c) {
        String s = p(c).getString("tree", null);
        return s == null ? null : Uri.parse(s);
    }

    /** Ordner war gewählt, Freigabe ist aber weg (z. B. Ordner gelöscht). */
    static boolean lost(Context c) {
        return p(c).getString("tree", null) != null && tree(c) == null;
    }

    static boolean setTree(Context c, Uri u) {
        if (u == null || !"content".equals(u.getScheme()) || !DocumentsContract.isTreeUri(u)) return false;
        ContentResolver r = c.getContentResolver();
        try {
            r.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            return false;
        }
        String old = p(c).getString("tree", null);
        if (old != null && !old.equals(u.toString())) release(c, Uri.parse(old));
        p(c).edit().putString("tree", u.toString()).apply();
        return true;
    }

    static void forgetTree(Context c) {
        String old = p(c).getString("tree", null);
        if (old != null) release(c, Uri.parse(old));
        p(c).edit().remove("tree").putBoolean("auto", false).apply();
        cancelJob(c);
    }

    private static void release(Context c, Uri u) {
        // Die Anruf-Freigabe nie mit freigeben
        if (u.equals(CallImport.tree(c))) return;
        try {
            c.getContentResolver().releasePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (RuntimeException ignored) { }
    }

    /** Lesbarer Ordnername, z. B. „Documents/Sicherungen“. */
    static String label(Context c) {
        Uri u = tree(c);
        if (u == null) return null;
        try {
            String id = DocumentsContract.getTreeDocumentId(u);
            int i = id.indexOf(':');
            String path = i >= 0 ? id.substring(i + 1) : id;
            String vol = i >= 0 ? id.substring(0, i) : "";
            if (path.isEmpty()) path = "primary".equals(vol) ? "Interner Speicher" : vol;
            return path;
        } catch (RuntimeException e) {
            return u.getLastPathSegment();
        }
    }

    // ------------------------------------------------------------------ Einstellungen

    /** Passwort-Hinweis (kein Geheimnis, steht auch lesbar in jeder Sicherungsdatei). */
    static String hint(Context c) { return Backup.cleanHint(p(c).getString("hint", "")); }

    static void setHint(Context c, String h) { p(c).edit().putString("hint", Backup.cleanHint(h)).apply(); }

    static boolean media(Context c) { return p(c).getBoolean("media", false); }

    static void setMedia(Context c, boolean on) { p(c).edit().putBoolean("media", on).apply(); }

    static boolean auto(Context c) { return p(c).getBoolean("auto", false); }

    static void setAuto(Context c, boolean on) {
        p(c).edit().putBoolean("auto", on).apply();
        if (on) schedule(c); else cancelJob(c);
    }

    static int keep(Context c) { return BackupFiles.cleanKeep(p(c).getInt("keep", 14)); }

    static void setKeep(Context c, int k) { p(c).edit().putInt("keep", BackupFiles.cleanKeep(k)).apply(); }

    static boolean autoDelete(Context c) { return p(c).getBoolean("autodel", false); }

    static void setAutoDelete(Context c, boolean on) { p(c).edit().putBoolean("autodel", on).apply(); }

    static long lastBackup(Context c) { return p(c).getLong("last", 0); }

    /** Sicherung an anderem Ort zählt auch für die Erinnerung. */
    static void markBackup(Context c) { p(c).edit().putLong("last", System.currentTimeMillis()).apply(); }

    /** Automatik kann laufen: eingeschaltet, Ordner erreichbar, Passwort gemerkt. */
    static boolean autoReady(Context c) {
        return auto(c) && tree(c) != null && hasPassword(c);
    }

    // ------------------------------------------------------------------ gemerktes Passwort

    static boolean hasPassword(Context c) {
        return c.getSharedPreferences(PW_PREFS, Context.MODE_PRIVATE).getString("ct", null) != null;
    }

    /** Entschlüsseltes Passwort (Aufrufer überschreibt es nach Gebrauch) oder null. */
    static char[] password(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PW_PREFS, Context.MODE_PRIVATE);
        String data = sp.getString("ct", null), iv = sp.getString("iv", null);
        if (data == null || iv == null) return null;
        byte[] plain = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            plain = cipher.doFinal(Base64.decode(data, Base64.NO_WRAP));
            CharBuffer cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plain));
            char[] out = Arrays.copyOfRange(cb.array(), cb.position(), cb.limit());
            Arrays.fill(cb.array(), '\0');
            return out.length >= Backup.MIN_PASSWORD ? out : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    static boolean setPassword(Context c, char[] pw) {
        if (pw == null || pw.length < Backup.MIN_PASSWORD) return false;
        ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(pw));
        byte[] plain = Arrays.copyOfRange(bb.array(), bb.position(), bb.limit());
        Arrays.fill(bb.array(), (byte) 0);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] ct = cipher.doFinal(plain);
            return c.getSharedPreferences(PW_PREFS, Context.MODE_PRIVATE).edit()
                    .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                    .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .commit();
        } catch (Exception e) {
            return false;
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    static void removePassword(Context c) {
        c.getSharedPreferences(PW_PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(ALIAS);
        } catch (Exception ignored) { }
        setAuto(c, false);
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            KeyStore.Entry e = ks.getEntry(ALIAS, null);
            if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
            ks.deleteEntry(ALIAS);
        }
        if (!create) throw new IllegalStateException("kein Schlüssel");
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    // ------------------------------------------------------------------ Dateien im Ordner

    static final class Entry {
        final String name;
        final Uri uri;
        final long size, modified;

        Entry(String name, Uri uri, long size, long modified) {
            this.name = name;
            this.uri = uri;
            this.size = size;
            this.modified = modified;
        }

        boolean auto() { return BackupFiles.isAuto(name); }

        String day() { return BackupFiles.day(name); }
    }

    /** Alle Sicherungsdateien im Ordner (nur passende Namen), neueste zuerst. */
    static List<Entry> all(Context c) {
        List<Entry> out = new ArrayList<>();
        Uri t = tree(c);
        if (t == null) return out;
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(t, DocumentsContract.getTreeDocumentId(t));
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cur = c.getContentResolver().query(kids, cols, null, null, null)) {
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < 500) {
                String name = cur.getString(1);
                if (!BackupFiles.isBackup(name)) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cur.getString(4))) continue;
                Uri u = DocumentsContract.buildDocumentUriUsingTree(t, cur.getString(0));
                out.add(new Entry(name, u, cur.isNull(2) ? -1 : cur.getLong(2), cur.isNull(3) ? 0 : cur.getLong(3)));
            }
        } catch (RuntimeException ignored) { }
        out.sort((a, b) -> {
            int d = b.day().compareTo(a.day());
            return d != 0 ? d : Long.compare(b.modified, a.modified);
        });
        return out;
    }

    /** Für die Auswahl beim Wiederherstellen: höchstens 20. */
    static List<Entry> list(Context c) {
        List<Entry> all = all(c);
        return all.size() > MAX_LIST ? new ArrayList<>(all.subList(0, MAX_LIST)) : all;
    }

    private static Uri find(List<Entry> all, String name) {
        for (Entry e : all) if (e.name.equals(name)) return e.uri;
        return null;
    }

    // ------------------------------------------------------------------ Sichern

    static final class Outcome {
        boolean ok;
        String name;
        /** Bei Automatik: so viele älteste müssten weg, Nutzer soll gefragt werden. */
        List<Entry> needConfirm;
        Exception error;
    }

    /**
     * Sichert in den gemerkten Ordner. auto = automatische Datei; deleteOld = älteste automatische entfernen,
     * wenn das Aufbewahrungslimit erreicht ist. Läuft im Hintergrund-Thread.
     */
    static Outcome backup(Context ctx, char[] pw, boolean auto, boolean deleteOld) {
        Context c = ctx.getApplicationContext();
        Outcome o = new Outcome();
        if (!RUNNING.compareAndSet(false, true)) {
            o.error = new IllegalStateException("läuft schon");
            return o;
        }
        Uri newDoc = null;
        boolean created = false;
        try {
            Uri t = tree(c);
            if (t == null) throw new IllegalStateException("kein Ordner");
            String day = today();
            String name = auto ? BackupFiles.autoName(day) : BackupFiles.manualName(day);
            List<Entry> all = all(c);
            if (auto) {
                List<String> names = new ArrayList<>();
                for (Entry e : all) names.add(e.name);
                List<String> del = BackupFiles.toDelete(names, keep(c), day);
                if (!del.isEmpty()) {
                    if (!deleteOld) {
                        o.needConfirm = new ArrayList<>();
                        for (String n : del) {
                            Uri u = find(all, n);
                            if (u != null) o.needConfirm.add(new Entry(n, u, -1, 0));
                        }
                        return o;
                    }
                    for (String n : del) {
                        Uri u = find(all, n);
                        if (u != null && BackupFiles.isAuto(n)) {
                            try { DocumentsContract.deleteDocument(c.getContentResolver(), u); } catch (Exception ignored) { }
                        }
                    }
                }
            }
            newDoc = find(all, name); // gleicher Tag: überschreiben
            if (newDoc == null) {
                Uri dir = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t));
                newDoc = DocumentsContract.createDocument(c.getContentResolver(), dir, "application/octet-stream", name);
                if (newDoc == null) throw new java.io.IOException("Datei");
                created = true;
            }
            BackupManager.create(c, newDoc, pw, hint(c), media(c), (d, n) -> { });
            SharedPreferences.Editor e = p(c).edit().putLong("last", System.currentTimeMillis()).remove("err_day");
            if (auto) e.putString("auto_day", day);
            e.apply();
            o.ok = true;
            o.name = name;
        } catch (Exception e) {
            o.error = e;
            if (created && newDoc != null) {
                try { DocumentsContract.deleteDocument(c.getContentResolver(), newDoc); } catch (Exception ignored) { }
            }
        } finally {
            RUNNING.set(false);
        }
        return o;
    }

    static boolean running() { return RUNNING.get(); }

    /** Heute schon automatisch gesichert oder versucht (kein endloses Wiederholen nach Fehlern)? */
    static boolean autoDoneToday(Context c) {
        String d = today();
        return d.equals(p(c).getString("auto_day", "")) || d.equals(p(c).getString("err_day", ""));
    }

    /** Automatische Sicherung, falls fällig. Liefert null, wenn nichts zu tun war. */
    static Outcome autoIfDue(Context c, boolean deleteOld) {
        if (!autoReady(c) || autoDoneToday(c)) return null;
        if (Store.all(c).isEmpty()) return null;
        char[] pw = password(c);
        if (pw == null) return null;
        try {
            Outcome o = backup(c, pw, true, deleteOld || autoDelete(c));
            if (o.error != null && !(o.error instanceof IllegalStateException && running())) markFailed(c);
            return o;
        } finally {
            Arrays.fill(pw, '\0');
        }
    }

    static void markFailed(Context c) {
        p(c).edit().putString("err_day", today()).apply();
    }

    /** Nutzer hat „Heute nicht“ gewählt. */
    static void skipToday(Context c) {
        p(c).edit().putString("err_day", today()).putString("hint_day", today()).apply();
    }

    /** Höchstens ein Hinweis am Tag (Fehler oder Erinnerung). */
    static boolean hintAllowed(Context c) {
        if (today().equals(p(c).getString("hint_day", ""))) return false;
        p(c).edit().putString("hint_day", today()).apply();
        return true;
    }

    /** Erinnern, wenn es Daten gibt und die letzte Sicherung über 30 Tage alt ist. */
    static boolean remindDue(Context c, boolean hasData) {
        if (!hasData) return false;
        long last = lastBackup(c);
        long first = p(c).getLong("first_seen", 0);
        if (first == 0) {
            p(c).edit().putLong("first_seen", System.currentTimeMillis()).apply();
            return false;
        }
        long ref = last > 0 ? last : first;
        return System.currentTimeMillis() - ref > REMIND_MS;
    }

    /** Willkommens-Angebot „Aus Sicherung wiederherstellen“ nur einmal, nur ohne Daten. */
    static boolean offerRestore(Context c, boolean hasData) {
        if (p(c).getBoolean("welcome", false)) return false;
        p(c).edit().putBoolean("welcome", true).apply();
        return !hasData;
    }

    // ------------------------------------------------------------------ Hintergrund

    static void schedule(Context c) {
        if (!auto(c)) return;
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        try {
            if (js.getPendingJob(JOB_ID) != null) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, BackupJob.class))
                    .setPeriodic(6 * 60 * 60_000L)
                    .setRequiresBatteryNotLow(true)
                    .setPersisted(true)
                    .build());
        } catch (RuntimeException ignored) { }
    }

    static void cancelJob(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js != null) {
            try { js.cancel(JOB_ID); } catch (RuntimeException ignored) { }
        }
    }
}
