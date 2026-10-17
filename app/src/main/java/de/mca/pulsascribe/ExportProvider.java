package de.mca.pulsascribe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Gibt exportierte Dateien zum Teilen frei – nur lesend, nur aus cache/export, nur mit befristeter
 * Freigabe (nicht exportiert, grantUriPermissions). Ersetzt die FileProvider-Bibliothek.
 */
public final class ExportProvider extends ContentProvider {
    static final String AUTHORITY = "de.mca.pulsascribe.export";
    private static final Pattern DIR = Pattern.compile("[a-z0-9]{16}");
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} ()_\\-]{1,60}\\.(txt|docx|pdf|srt)");

    static File root(Context c) {
        File d = new File(c.getCacheDir(), "export");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static Uri uriFor(String dir, String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(dir).appendPath(name).build();
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        if (!AUTHORITY.equals(uri.getAuthority()) || seg.size() != 2 || !DIR.matcher(seg.get(0)).matches()
                || !NAME.matcher(seg.get(1)).matches()) {
            throw new FileNotFoundException("unbekannt");
        }
        File f = new File(new File(root(getContext()), seg.get(0)), seg.get(1));
        if (!f.isFile()) throw new FileNotFoundException("fehlt");
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("nur lesen");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        File f;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String p = uri.getLastPathSegment();
        if (p == null) return null;
        if (p.endsWith(".txt")) return "text/plain";
        if (p.endsWith(".srt")) return "application/x-subrip";
        if (p.endsWith(".pdf")) return "application/pdf";
        if (p.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }

    @Override
    public int delete(Uri uri, String sel, String[] args) { throw new UnsupportedOperationException(); }

    @Override
    public int update(Uri uri, ContentValues values, String sel, String[] args) { throw new UnsupportedOperationException(); }
}
