package de.mca.pulsascribe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Bilder und Textdateien für „Teilen → Zusammenfassen“: Bild wird verkleinert (max. 1600 px)
 * als JPEG im privaten Speicher abgelegt, Text auf 100.000 Zeichen begrenzt gelesen.
 */
final class ImagePrep {
    private ImagePrep() {}

    static final int MAX_SIDE = 1600;
    static final long MAX_INPUT = 40L * 1024 * 1024;
    static final int MAX_PIXELS = 12000;

    static void toJpeg(Context c, Uri src, File out) throws IOException {
        Bitmap bmp;
        try {
            ImageDecoder.Source s = ImageDecoder.createSource(c.getContentResolver(), src);
            bmp = ImageDecoder.decodeBitmap(s, (decoder, info, source) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                if (w <= 0 || h <= 0 || w > MAX_PIXELS || h > MAX_PIXELS) throw new IllegalArgumentException("Bildgröße");
                float scale = Math.min(1f, MAX_SIDE / (float) Math.max(w, h));
                decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } catch (IOException | RuntimeException e) {
            throw new IOException("Bild nicht lesbar");
        }
        try (OutputStream os = new FileOutputStream(out)) {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 85, os)) throw new IOException("jpeg");
        } finally {
            bmp.recycle();
        }
    }

    /** Liest eine Textdatei (UTF-8) bis max. Zeichen; Rest wird abgeschnitten. */
    static String readText(Context c, Uri src, int maxChars) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(src)) {
            if (in == null) throw new IOException("null");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            long limit = Math.min(MAX_INPUT, maxChars * 4L);
            while ((n = in.read(buf)) > 0 && bos.size() < limit) bos.write(buf, 0, n);
            String s = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            return s.length() > maxChars ? s.substring(0, maxChars) : s;
        }
    }

    /** Für die Vorschau im Transkript: kleines Bild aus der gespeicherten Datei. */
    static Bitmap thumb(File f, int maxSide) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }
}
